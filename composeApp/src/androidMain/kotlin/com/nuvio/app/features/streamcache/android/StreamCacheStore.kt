package com.nuvio.app.features.streamcache.android

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.features.streamcache.StreamCacheConfig
import com.nuvio.app.features.streamcache.StreamCacheEntry
import com.nuvio.app.features.streamcache.StreamCacheRepository
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Port of iosApp/iosApp/StreamCache/StreamCacheStore.swift.
// Owner: StreamCacheStore.kt (this file only).

// region Persisted metadata

/**
 * Contents of `<root>/<id>/meta.json` (kotlinx.serialization, encoded with
 * [StreamCacheStore.metaJson]). Field names match the Swift `StreamCacheMeta` Codable so the
 * format is the same on both platforms.
 */
@Serializable
internal data class StreamCacheMeta(
    val id: String,
    val cacheKey: String,
    /** Verbatim StreamCacheContext JSON handed over by Kotlin common code. */
    val contextJson: String,
    /** Display / export file name (sanitised, always has a video extension). */
    val fileName: String,
    /** Name of the sparse data file inside the entry directory (`data.<ext>`). */
    val dataFileName: String,
    val mimeType: String? = null,
    /** -1 while unknown. */
    val totalBytes: Long = -1L,
    /** Cached spans as `[[start, endExclusive], ...]` (only refreshed when the meta is written). */
    val ranges: List<List<Long>> = emptyList(),
    val pinned: Boolean = false,
    val createdAtMs: Long = 0L,
    val lastAccessMs: Long = 0L,
    /** Last upstream URL + headers (used by the filler and by entryPlaybackUrl for partial entries). */
    val sourceUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val fillError: String? = null,
    /** True when the file extension was guessed (no name/extension was known). */
    val extensionGuessed: Boolean? = null,
)

// endregion

// region In-memory entry

/**
 * One cache entry. [meta] (immutable data class, replaced via copy), [ranges], [generation],
 * [activeSessions], [isFilling], [dirty], [playheadOffset] are guarded by the store lock;
 * [raf] and [ioClosed] by [ioLock].
 *
 * LOCK ORDER is always `ioLock -> store lock` (never take ioLock while holding the store lock).
 */
internal class StreamCacheEntryState(
    var meta: StreamCacheMeta,
    var ranges: StreamCacheRangeSet,
    val directory: File,
) {
    /** Bumped whenever cached data is discarded; writes carrying an older generation are dropped. */
    var generation: Int = 0
    var activeSessions: Int = 0
    var isFilling: Boolean = false
    var dirty: Boolean = false

    /**
     * Most recent byte offset the proxy served/fetched for this entry (memory only; -1 = none).
     * The filler starts from `playheadOffset + FILL_LEAD_BYTES` instead of byte 0.
     */
    var playheadOffset: Long = -1L

    val dataFile: File = File(directory, meta.dataFileName)

    val ioLock: ReentrantLock = ReentrantLock()

    /** Opened lazily in "rw" mode by StreamCacheStore.openFileLocked; guarded by [ioLock]. */
    var raf: RandomAccessFile? = null
    var ioClosed: Boolean = false

    /** `ranges.covers(meta.totalBytes)`; call with the store lock held. */
    val isComplete: Boolean
        get() = ranges.covers(meta.totalBytes)
}

/** Snapshot of what the filler needs to fetch the next missing span. */
internal data class StreamCacheFillSource(
    val url: String,
    val headers: Map<String, String>,
    val totalBytes: Long,
    val generation: Int,
    /** First missing span at/after playhead+lead (wrapping); null when complete or total unknown. */
    val gap: StreamCacheSpan?,
)

/** `serveInfo` result: total size (-1 unknown), mime and current generation. */
internal data class StreamCacheServeInfo(
    val totalBytes: Long,
    val mimeType: String?,
    val generation: Int,
)

/** What `entryPlaybackUrl` needs to play an entry straight from the cache (no stream lookup). */
internal data class StreamCacheEntryPlaybackInfo(
    val cachedBytes: Long,
    val isComplete: Boolean,
    val dataFile: File,
    val fileName: String,
    /** Stored upstream URL (null for old/broken metadata). */
    val sourceUrl: String?,
    val headers: Map<String, String>,
)

// endregion

// region Change notifications

/**
 * Throttled (≤ 2 Hz), main-thread change notifications with a guaranteed trailing call.
 * Port of `StreamCacheNotifier`. [notifyChanged] is cheap and may be called from any thread at
 * high frequency: the first call while nothing is pending posts `listener()` on [mainHandler]
 * after `max(0, lastFire + MIN_INTERVAL_MS - now)`; further calls before it fires are coalesced.
 */
internal class StreamCacheNotifier(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val lock = Any()
    private var pending = false
    private var lastFireMs = 0L

    /** Accessed on the main thread only (set via the main handler when called off-main). */
    private var listener: (() -> Unit)? = null

    /** Replaces the listener (null removes it). Safe from any thread. */
    fun setListener(newListener: (() -> Unit)?) {
        if (Looper.myLooper() == mainHandler.looper) {
            listener = newListener
        } else {
            mainHandler.post { listener = newListener }
        }
    }

    /** Schedules a throttled main-thread `listener()` call. Safe from any thread. */
    fun notifyChanged() {
        val now = SystemClock.uptimeMillis()
        val delay: Long
        synchronized(lock) {
            if (pending) return
            pending = true
            delay = maxOf(0L, lastFireMs + MIN_INTERVAL_MS - now)
        }
        val posted = mainHandler.postDelayed({
            synchronized(lock) {
                pending = false
                lastFireMs = SystemClock.uptimeMillis()
            }
            listener?.invoke()
        }, delay)
        if (!posted) {
            // Main looper is quitting; allow a later attempt.
            synchronized(lock) { pending = false }
        }
    }

    companion object {
        const val MIN_INTERVAL_MS: Long = 500L
    }
}

// endregion

// region Store

/**
 * Disk-backed entries (`<rootDir>/<id>/data.<ext>` + `meta.json`), config (SharedPreferences),
 * LRU eviction, export, playhead tracking. All public methods are thread-safe.
 * Port of `StreamCacheStore` (Swift singleton → one instance owned by AndroidStreamCacheBackend).
 *
 * - [rootDir] = `context.filesDir/stream_cache` (persistent; created if missing).
 * - [preferences] = `context.getSharedPreferences("nuvio_stream_cache", MODE_PRIVATE)` holding
 *   [KEY_ENABLED], [KEY_MAX_BYTES], [KEY_AUTO_FILL], [KEY_LOOK_AHEAD_MINUTES].
 * - [maintenance] = single-thread scheduled executor (daemon) for debounced saves (2 s) and
 *   throttled eviction checks (3 s).
 * - `init` calls [loadEntries] synchronously.
 */
internal class StreamCacheStore(
    val rootDir: File,
    private val preferences: SharedPreferences,
    private val notifier: StreamCacheNotifier,
    private val maintenance: ScheduledExecutorService,
) {
    private val lock = Any()
    private val entries: HashMap<String, StreamCacheEntryState> = HashMap()
    private var saveScheduled = false
    private var evictionScheduled = false
    private var cachedFreeDisk: Long = -1L
    private var freeDiskCheckedAtMs: Long = 0L

    /** Serialises meta.json writes (shared tmp file name per entry). */
    private val metaWriteLock = Any()

    init {
        loadEntries()
    }

    // region Loading

    /**
     * Scans [rootDir]: for each sub-directory reads `meta.json`; discards (deletes) directories
     * whose meta is unreadable, whose `id != dirName`, or with blank dataFileName/cacheKey.
     * Clamps ranges to the physical data-file length and to `totalBytes` (> 0); marks the entry
     * dirty when clamping changed the set; schedules a save if anything is dirty.
     */
    private fun loadEntries() {
        if (!rootDir.exists()) rootDir.mkdirs()
        val children = rootDir.listFiles() ?: return
        val loaded = HashMap<String, StreamCacheEntryState>()
        for (directory in children) {
            if (directory.name.startsWith(".")) continue
            if (!directory.isDirectory) continue
            val meta = runCatching {
                metaJson.decodeFromString(
                    StreamCacheMeta.serializer(),
                    File(directory, META_FILE_NAME).readText(Charsets.UTF_8),
                )
            }.getOrNull()
            if (meta == null ||
                meta.id != directory.name ||
                meta.dataFileName.isBlank() ||
                meta.cacheKey.isBlank()
            ) {
                log("discarding broken entry ${directory.name}")
                runCatching { directory.deleteRecursively() }
                continue
            }
            val ranges = StreamCacheRangeSet(meta.ranges)
            val dataFile = File(directory, meta.dataFileName)
            val fileSize = if (dataFile.isFile) dataFile.length() else 0L
            // Never trust spans beyond what's physically in the file.
            ranges.clamp(fileSize)
            if (meta.totalBytes > 0) {
                ranges.clamp(meta.totalBytes)
            }
            val entry = StreamCacheEntryState(meta = meta, ranges = ranges, directory = directory)
            if (ranges != StreamCacheRangeSet(meta.ranges)) {
                entry.dirty = true
            }
            loaded[meta.id] = entry
        }
        val needsSave: Boolean
        synchronized(lock) {
            entries.clear()
            entries.putAll(loaded)
            needsSave = loaded.values.any { it.dirty }
        }
        if (needsSave) scheduleSave()
        if (loaded.isNotEmpty()) {
            log("loaded ${loaded.size} entries")
        }
    }

    // endregion

    // region Config

    /** Default true. */
    val isEnabled: Boolean
        get() = runCatching { preferences.getBoolean(KEY_ENABLED, true) }.getOrDefault(true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        notifier.notifyChanged()
    }

    /** Default [DEFAULT_MAX_BYTES]; 0 = unlimited. */
    val maxBytes: Long
        get() = runCatching { preferences.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES) }
            .getOrDefault(DEFAULT_MAX_BYTES)

    /** Stores `max(0, value)`, runs an eviction check on [maintenance], notifies. */
    fun setMaxBytes(value: Long) {
        preferences.edit().putLong(KEY_MAX_BYTES, maxOf(0L, value)).apply()
        submitMaintenance(0L) { evictIfNeeded() }
        notifier.notifyChanged()
    }

    /** Default [DEFAULT_AUTO_FILL]. */
    val autoFillWhilePlaying: Boolean
        get() = runCatching { preferences.getBoolean(KEY_AUTO_FILL, DEFAULT_AUTO_FILL) }
            .getOrDefault(DEFAULT_AUTO_FILL)

    fun setAutoFillWhilePlaying(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_FILL, enabled).apply()
        notifier.notifyChanged()
    }

    /** Player look-ahead in minutes, clamped to `0..MAX_LOOK_AHEAD_MINUTES`; default 10. */
    val lookAheadMinutes: Int
        get() {
            val stored = runCatching {
                preferences.getInt(KEY_LOOK_AHEAD_MINUTES, DEFAULT_LOOK_AHEAD_MINUTES)
            }.getOrDefault(DEFAULT_LOOK_AHEAD_MINUTES)
            return stored.coerceIn(0, MAX_LOOK_AHEAD_MINUTES)
        }

    fun setLookAheadMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(0, MAX_LOOK_AHEAD_MINUTES)
        preferences.edit().putInt(KEY_LOOK_AHEAD_MINUTES, clamped).apply()
        notifier.notifyChanged()
    }

    /**
     * Free bytes on the volume of [rootDir] (`StatFs(rootDir.path).availableBytes`), cached for
     * [maxAgeMs]. -1 when unknown.
     */
    fun freeDiskBytes(maxAgeMs: Long = 5_000L): Long {
        val now = nowMs()
        synchronized(lock) {
            val age = now - freeDiskCheckedAtMs
            if (freeDiskCheckedAtMs > 0L && age >= 0L && age < maxAgeMs) {
                return cachedFreeDisk
            }
        }
        val value = runCatching { StatFs(rootDir.path).availableBytes }.getOrDefault(-1L)
        synchronized(lock) {
            cachedFreeDisk = value
            freeDiskCheckedAtMs = now
        }
        return value
    }

    /** False when free disk space is below [MIN_FREE_BYTES] (caching pauses, pass-through continues). */
    fun hasEnoughFreeSpace(): Boolean {
        val free = freeDiskBytes()
        return free < 0 || free >= MIN_FREE_BYTES
    }

    /** Sum of cached bytes of all entries. */
    val usedBytes: Long
        get() = synchronized(lock) { entries.values.sumOf { it.ranges.totalLength } }

    /**
     * JSON of `com.nuvio.app.features.streamcache.StreamCacheConfig` (encode that data class with
     * `StreamCacheRepository.json` so field names always match): enabled, maxBytes,
     * minFreeBytes = MIN_FREE_BYTES, usedBytes, freeDiskBytes (maxAge 1 s),
     * autoFillWhilePlaying, lookAheadMinutes.
     */
    fun configJson(): String {
        val config = StreamCacheConfig(
            enabled = isEnabled,
            maxBytes = maxBytes,
            minFreeBytes = MIN_FREE_BYTES,
            usedBytes = usedBytes,
            freeDiskBytes = freeDiskBytes(1_000L),
            autoFillWhilePlaying = autoFillWhilePlaying,
            lookAheadMinutes = lookAheadMinutes,
        )
        return runCatching {
            StreamCacheRepository.json.encodeToString(StreamCacheConfig.serializer(), config)
        }.getOrDefault("{}")
    }

    // endregion

    // region Entries (backend-facing)

    /**
     * Creates or updates the entry for [cacheKey]. Existing entry: replaces contextJson,
     * sourceUrl, headers, lastAccessMs (dirty + scheduleSave + notify). New entry: creates the
     * directory, file name via [makeFileName], `dataFileName = "data.<ext>"`, writes meta.json
     * immediately; handles the create race like Swift (keep the raced entry). Returns the entry
     * id, or null on disk failure.
     */
    fun prepareEntry(
        cacheKey: String,
        contextJson: String,
        fileNameHint: String?,
        sourceUrl: String,
        headers: Map<String, String>,
    ): String? {
        val id = entryId(cacheKey)
        val now = nowMs()

        val updatedExisting = synchronized(lock) {
            val existing = entries[id]
            if (existing != null) {
                existing.meta = existing.meta.copy(
                    contextJson = contextJson,
                    sourceUrl = sourceUrl,
                    headers = headers,
                    lastAccessMs = now,
                )
                existing.dirty = true
                true
            } else {
                false
            }
        }
        if (updatedExisting) {
            scheduleSave()
            notifier.notifyChanged()
            return id
        }

        val directory = File(rootDir, id)
        val dirOk = runCatching { directory.isDirectory || directory.mkdirs() }.getOrDefault(false)
        if (!dirOk) {
            log("cannot create entry dir ${directory.path}")
            return null
        }
        val (name, guessed) = makeFileName(fileNameHint, sourceUrl, null)
        val ext = name.substringAfterLast('.', "").lowercase()
        val meta = StreamCacheMeta(
            id = id,
            cacheKey = cacheKey,
            contextJson = contextJson,
            fileName = name,
            dataFileName = "data." + ext.ifEmpty { "mkv" },
            mimeType = null,
            totalBytes = -1L,
            ranges = emptyList(),
            pinned = false,
            createdAtMs = now,
            lastAccessMs = now,
            sourceUrl = sourceUrl,
            headers = headers,
            fillError = null,
            extensionGuessed = guessed,
        )
        val entry = StreamCacheEntryState(meta = meta, ranges = StreamCacheRangeSet(), directory = directory)
        val raced: Boolean
        synchronized(lock) {
            val other = entries[id]
            if (other != null) {
                // Another thread created it meanwhile; keep that one.
                other.meta = other.meta.copy(sourceUrl = sourceUrl, headers = headers)
                other.dirty = true
                raced = true
            } else {
                entries[id] = entry
                raced = false
            }
        }
        if (raced) {
            scheduleSave()
            return id
        }
        writeMeta(meta, directory)
        notifier.notifyChanged()
        return id
    }

    fun fileName(id: String): String? = synchronized(lock) { entries[id]?.meta?.fileName }

    fun entryPlaybackInfo(id: String): StreamCacheEntryPlaybackInfo? = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized null
        StreamCacheEntryPlaybackInfo(
            cachedBytes = entry.ranges.totalLength,
            isComplete = entry.isComplete,
            dataFile = entry.dataFile,
            fileName = entry.meta.fileName,
            sourceUrl = entry.meta.sourceUrl?.takeIf { it.isNotBlank() },
            headers = entry.meta.headers,
        )
    }

    /**
     * Prepares a complete entry's data file to be opened directly by the player (file:// URL):
     * under ioLock, re-checks the entry is still registered and complete, the file length is
     * >= totalBytes, cuts a stale tail beyond totalBytes (`raf.setLength(total)`) and syncs
     * (`raf.fd.sync()`). False when gone, incomplete or the file is missing/short.
     */
    fun prepareFilePlayback(id: String): Boolean {
        val entry = synchronized(lock) { entries[id] } ?: return false
        entry.ioLock.withLock {
            val complete: Boolean
            val total: Long
            synchronized(lock) {
                complete = entries[id] === entry && entry.isComplete
                total = entry.meta.totalBytes
            }
            if (!complete || total <= 0 || entry.ioClosed) return false
            val file = entry.dataFile
            if (!file.isFile) return false
            val fileSize = file.length()
            if (fileSize < total) return false
            if (fileSize > total) {
                val raf = openFileLocked(entry)
                if (raf != null) {
                    try {
                        raf.setLength(total)
                    } catch (e: IOException) {
                        log("truncate failed: ${e.message}")
                    }
                }
            }
            entry.raf?.let { raf ->
                try {
                    raf.fd.sync()
                } catch (ignored: Exception) {
                }
            }
            return true
        }
    }

    /** Updates lastAccessMs (dirty + scheduleSave). */
    fun touch(id: String) {
        synchronized(lock) {
            val entry = entries[id]
            if (entry != null) {
                entry.meta = entry.meta.copy(lastAccessMs = nowMs())
                entry.dirty = true
            }
        }
        scheduleSave()
    }

    /**
     * A playback session started (+1) or ended (-1) for this entry. activeSessions is clamped at
     * 0; updates lastAccessMs; on -1 flushes meta now, else schedules a save; notifies.
     */
    fun markActive(id: String, delta: Int) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            entry.activeSessions = maxOf(0, entry.activeSessions + delta)
            entry.meta = entry.meta.copy(lastAccessMs = nowMs())
            entry.dirty = true
        }
        if (delta < 0) {
            flush(id)
        } else {
            scheduleSave()
        }
        notifier.notifyChanged()
    }

    // endregion

    // region Entries (proxy / filler facing)

    /** Total size, mime and current generation; null if the entry no longer exists. */
    fun serveInfo(id: String): StreamCacheServeInfo? = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized null
        StreamCacheServeInfo(entry.meta.totalBytes, entry.meta.mimeType, entry.generation)
    }

    /**
     * Records the upstream total size (and mime, normalised; "application/octet-stream" ignored).
     * If a different total was stored before (or ranges exist), the cached data is discarded:
     * ranges cleared, generation++, data file truncated to 0 under ioLock (even if no RAF is
     * open yet). When the extension was guessed, a newly learned mime fixes the display file
     * name extension (data file name unchanged). Returns the current generation, or null if the
     * entry is gone or `totalBytes <= 0`.
     */
    fun setTotal(id: String, totalBytes: Long, mimeType: String?): Int? {
        val entry = synchronized(lock) { entries[id] } ?: return null
        if (totalBytes <= 0) return null
        val mime = normalizedMime(mimeType)

        var changed = false
        val generation: Int
        entry.ioLock.withLock {
            var needsTruncate = false
            synchronized(lock) {
                var meta = entry.meta
                if (meta.totalBytes != totalBytes) {
                    if (meta.totalBytes > 0 || !entry.ranges.isEmpty) {
                        log("size changed for $id (${meta.totalBytes} -> $totalBytes); restarting entry")
                        entry.ranges.removeAll()
                        entry.generation += 1
                        needsTruncate = true
                    }
                    meta = meta.copy(totalBytes = totalBytes)
                    changed = true
                }
                if (mime != null && mime != "application/octet-stream" && meta.mimeType != mime) {
                    meta = meta.copy(mimeType = mime)
                    if (meta.extensionGuessed == true) {
                        val ext = extensionForMime(mime)
                        val base = deletingPathExtension(meta.fileName)
                        meta = meta.copy(fileName = "$base.$ext", extensionGuessed = false)
                    }
                    changed = true
                }
                entry.meta = meta
                if (changed) entry.dirty = true
                generation = entry.generation
            }
            // Drop the old bytes even when no RAF is open yet, so a later export never carries a
            // stale tail beyond the new total size.
            if (needsTruncate && !entry.ioClosed && entry.dataFile.exists()) {
                val raf = openFileLocked(entry)
                if (raf != null) {
                    try {
                        raf.setLength(0L)
                    } catch (e: IOException) {
                        log("truncate failed: ${e.message}")
                    }
                }
            }
        }

        if (changed) {
            scheduleSave()
            notifier.notifyChanged()
        }
        return generation
    }

    /** Exclusive end of the cached span containing [position], if cached. */
    fun cachedEnd(id: String, position: Long): Long? = synchronized(lock) {
        entries[id]?.ranges?.spanContaining(position)?.end
    }

    /** Start of the next cached span after [position]. */
    fun nextCachedStart(id: String, position: Long): Long? = synchronized(lock) {
        entries[id]?.ranges?.nextStartAfter(position)
    }

    /**
     * Reads exactly [length] cached bytes at [offset] into [buffer] starting at [bufferOffset]
     * (RandomAccessFile seek + readFully under ioLock). Caller must have checked the span is
     * cached. Returns false on I/O error / entry gone / closed.
     */
    fun read(id: String, offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Boolean {
        val entry = synchronized(lock) { entries[id] } ?: return false
        if (length <= 0) return true
        if (offset < 0 || bufferOffset < 0 || bufferOffset + length > buffer.size) return false
        entry.ioLock.withLock {
            if (entry.ioClosed) return false
            val raf = openFileLocked(entry) ?: return false
            return try {
                raf.seek(offset)
                raf.readFully(buffer, bufferOffset, length)
                true
            } catch (e: IOException) {
                log("read failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Writes [length] bytes of [buffer] (from [bufferOffset]) at [offset] and, only after the
     * write succeeded, inserts `[offset, offset+length)` into the ranges — but only if the
     * entry is still registered and its generation still equals [generation]. Returns false
     * when nothing was cached (low disk via [hasEnoughFreeSpace], entry gone/reset, I/O error).
     * On success: scheduleSave, scheduleEvictionCheck, notify.
     */
    fun write(
        id: String,
        generation: Int,
        offset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Boolean {
        if (length <= 0 || offset < 0 || bufferOffset < 0 || bufferOffset + length > buffer.size) {
            return false
        }
        if (!hasEnoughFreeSpace()) return false
        val entry = synchronized(lock) {
            val found = entries[id]
            if (found != null && found.generation == generation) found else null
        } ?: return false

        var recorded = false
        entry.ioLock.withLock {
            if (entry.ioClosed) return false
            val raf = openFileLocked(entry) ?: return false
            try {
                raf.seek(offset)
                raf.write(buffer, bufferOffset, length)
            } catch (e: IOException) {
                log("write failed: ${e.message}")
                return false
            }
            synchronized(lock) {
                if (entry.generation == generation && entries[id] === entry) {
                    entry.ranges.insert(offset, offset + length.toLong())
                    entry.dirty = true
                    recorded = true
                }
            }
        }

        if (recorded) {
            scheduleSave()
            scheduleEvictionCheck()
            notifier.notifyChanged()
        }
        return recorded
    }

    /**
     * Fill snapshot; null if the entry is gone or has no stored sourceUrl. Gap anchor =
     * `playheadOffset + FILL_LEAD_BYTES` when a playhead is known, else 0
     * (`ranges.firstGap(total, anchor)`); gap null when total unknown.
     */
    fun fillSource(id: String): StreamCacheFillSource? = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized null
        val url = entry.meta.sourceUrl?.takeIf { it.isNotBlank() } ?: return@synchronized null
        val total = entry.meta.totalBytes
        // Fill from just ahead of where the player is reading, then wrap around to earlier gaps.
        val anchor = if (entry.playheadOffset >= 0) entry.playheadOffset + FILL_LEAD_BYTES else 0L
        StreamCacheFillSource(
            url = url,
            headers = entry.meta.headers,
            totalBytes = total,
            generation = entry.generation,
            gap = if (total > 0) entry.ranges.firstGap(total, anchor) else null,
        )
    }

    /** Records the byte offset the proxy is currently serving for this entry (cheap; called often). */
    fun notePlayhead(id: String, offset: Long) {
        synchronized(lock) {
            entries[id]?.playheadOffset = offset
        }
    }

    /**
     * True when an automatic fill may start: entry exists, activeSessions > 0, sourceUrl stored,
     * totalBytes > 0, not filling, not complete.
     */
    fun canAutoFill(id: String): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        entry.activeSessions > 0 &&
            !entry.meta.sourceUrl.isNullOrBlank() &&
            entry.meta.totalBytes > 0 &&
            !entry.isFilling &&
            !entry.isComplete
    }

    /** Marks the entry as filling (clears fillError). False if missing or already filling. */
    fun beginFill(id: String): Boolean {
        synchronized(lock) {
            val entry = entries[id] ?: return false
            if (entry.isFilling) return false
            entry.isFilling = true
            entry.meta = entry.meta.copy(fillError = null)
            entry.dirty = true
        }
        scheduleSave()
        notifier.notifyChanged()
        return true
    }

    /** Clears isFilling, stores [error] as fillError, flushes meta, notifies. */
    fun endFill(id: String, error: String?) {
        synchronized(lock) {
            val entry = entries[id]
            if (entry != null) {
                entry.isFilling = false
                entry.meta = entry.meta.copy(fillError = error)
                entry.dirty = true
            }
        }
        flush(id)
        notifier.notifyChanged()
    }

    /** Persists pinned now; when unpinned schedules an eviction check. */
    fun setPinned(id: String, pinned: Boolean) {
        synchronized(lock) {
            val entry = entries[id]
            if (entry != null) {
                entry.meta = entry.meta.copy(pinned = pinned)
                entry.dirty = true
            }
        }
        flush(id)
        notifier.notifyChanged()
        if (!pinned) {
            scheduleEvictionCheck()
        }
    }

    fun entryExists(id: String): Boolean = synchronized(lock) { entries.containsKey(id) }

    // endregion

    // region Listing

    /**
     * JSON array of `com.nuvio.app.features.streamcache.StreamCacheEntry` (encode the list with
     * `StreamCacheRepository.json` + `ListSerializer(StreamCacheEntry.serializer())`), sorted by
     * lastAccessMs descending. isActive = activeSessions > 0; cachedBytes = ranges.totalLength.
     */
    fun listEntriesJson(): String {
        val list = synchronized(lock) {
            entries.values
                .sortedByDescending { it.meta.lastAccessMs }
                .map { entry ->
                    val meta = entry.meta
                    StreamCacheEntry(
                        id = meta.id,
                        contextJson = meta.contextJson,
                        fileName = meta.fileName,
                        mimeType = meta.mimeType,
                        totalBytes = meta.totalBytes,
                        cachedBytes = entry.ranges.totalLength,
                        isComplete = entry.isComplete,
                        pinned = meta.pinned,
                        isActive = entry.activeSessions > 0,
                        isFilling = entry.isFilling,
                        fillError = meta.fillError,
                        createdAtMs = meta.createdAtMs,
                        lastAccessMs = meta.lastAccessMs,
                    )
                }
        }
        return runCatching {
            StreamCacheRepository.json.encodeToString(ListSerializer(StreamCacheEntry.serializer()), list)
        }.getOrDefault("[]")
    }

    // endregion

    // region Removal / export

    /** Removes the entry from the map, closes its RAF (ioClosed = true) and deletes its dir. */
    fun deleteEntry(id: String) {
        val removed = synchronized(lock) { entries.remove(id) } ?: return
        closeAndRemove(removed)
        notifier.notifyChanged()
    }

    /** Removes all entries and everything else under [rootDir] (keeps the root). */
    fun clearAll() {
        val all = synchronized(lock) {
            val copy = entries.values.toList()
            entries.clear()
            copy
        }
        for (entry in all) {
            closeAndRemove(entry)
        }
        // Remove anything else left in the root (stray dirs), keep the root itself.
        rootDir.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
        notifier.notifyChanged()
    }

    /**
     * Moves the data file of a complete, idle (activeSessions == 0), non-filling entry to
     * [destinationPath] (parent dirs created; existing destination replaced) and removes the
     * entry. Truncates the stale tail to totalBytes and syncs/closes the RAF first. Move =
     * `File.renameTo` (same volume: filesDir → filesDir/downloads), falling back to copy+delete.
     * On failure the entry is put back (ioClosed = false). Returns null on success, else an
     * error message (same texts as Swift: "Cache entry not found", "This stream is not fully
     * cached yet", "Playing — try again after closing the player", "This stream is still
     * downloading", "Could not move the file: ...").
     */
    fun exportEntry(id: String, destinationPath: String): String? {
        val entry: StreamCacheEntryState
        val exportTotal: Long
        synchronized(lock) {
            val found = entries[id] ?: return "Cache entry not found"
            if (!found.isComplete) return "This stream is not fully cached yet"
            if (found.activeSessions != 0) return "Playing — try again after closing the player"
            if (found.isFilling) return "This stream is still downloading"
            exportTotal = found.meta.totalBytes
            entries.remove(id)
            entry = found
        }

        entry.ioLock.withLock {
            if (!entry.ioClosed && exportTotal > 0) {
                val raf = openFileLocked(entry)
                if (raf != null) {
                    // The sparse file may be longer than the media (stale bytes); cut it to the real size.
                    try {
                        raf.setLength(exportTotal)
                    } catch (e: IOException) {
                        log("truncate failed: ${e.message}")
                    }
                }
            }
            entry.ioClosed = true
            entry.raf?.let { raf ->
                runCatching { raf.fd.sync() }
                runCatching { raf.close() }
            }
            entry.raf = null
        }

        val source = entry.dataFile
        val destination = File(destinationPath)
        val failure: String? = try {
            destination.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("cannot create ${parent.path}")
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("cannot replace ${destination.path}")
            }
            if (!source.isFile) {
                throw IOException("cached data file is missing")
            }
            if (!source.renameTo(destination)) {
                try {
                    source.copyTo(destination, overwrite = true)
                } catch (e: Exception) {
                    runCatching { destination.delete() }
                    throw e
                }
                source.delete()
            }
            null
        } catch (e: Exception) {
            "Could not move the file: ${e.message ?: e.javaClass.simpleName}"
        }

        if (failure != null) {
            // Put the entry back so nothing is lost.
            entry.ioLock.withLock { entry.ioClosed = false }
            synchronized(lock) {
                if (!entries.containsKey(id)) {
                    entries[id] = entry
                }
            }
            notifier.notifyChanged()
            return failure
        }
        runCatching { entry.directory.deleteRecursively() }
        log("exported $id")
        notifier.notifyChanged()
        return null
    }

    // endregion

    // region Persistence

    /** Writes this entry's metadata now (atomic: write `meta.json.tmp` then rename). */
    fun flush(id: String) {
        val snapshot = synchronized(lock) {
            val entry = entries[id] ?: return
            entry.dirty = false
            snapshotMetaLocked(entry) to entry.directory
        }
        writeMeta(snapshot.first, snapshot.second)
    }

    /** Writes all dirty metadata now (call on app background). */
    fun flushAll() {
        saveDirtyEntries()
    }

    /** Debounced metadata save (at most every [SAVE_DEBOUNCE_MS]) on [maintenance]. */
    private fun scheduleSave() {
        synchronized(lock) {
            if (saveScheduled) return
            saveScheduled = true
        }
        if (!submitMaintenance(SAVE_DEBOUNCE_MS) { saveDirtyEntries() }) {
            synchronized(lock) { saveScheduled = false }
        }
    }

    private fun saveDirtyEntries() {
        val pending = ArrayList<Pair<StreamCacheMeta, File>>()
        synchronized(lock) {
            saveScheduled = false
            for (entry in entries.values) {
                if (entry.dirty) {
                    pending.add(snapshotMetaLocked(entry) to entry.directory)
                    entry.dirty = false
                }
            }
        }
        for ((meta, directory) in pending) {
            writeMeta(meta, directory)
        }
    }

    /** Must be called with the store lock held: meta copy with `ranges = entry.ranges.toArrays()`. */
    private fun snapshotMetaLocked(entry: StreamCacheEntryState): StreamCacheMeta =
        entry.meta.copy(ranges = entry.ranges.toArrays())

    /** Atomic write of meta.json; errors ignored (dir may have been removed concurrently). */
    private fun writeMeta(meta: StreamCacheMeta, directory: File) {
        synchronized(metaWriteLock) {
            try {
                // Directory may have been removed concurrently (delete/export); don't recreate it.
                if (!directory.isDirectory) return
                val text = metaJson.encodeToString(StreamCacheMeta.serializer(), meta)
                val target = File(directory, META_FILE_NAME)
                val tmp = File(directory, "$META_FILE_NAME.tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                    }
                }
            } catch (ignored: Exception) {
                // Directory may have been removed concurrently (delete/export); that's fine.
            }
        }
    }

    /**
     * Must be called with `entry.ioLock` held: returns the open RAF ("rw", creating the file),
     * or null when it cannot be opened.
     */
    private fun openFileLocked(entry: StreamCacheEntryState): RandomAccessFile? {
        entry.raf?.let { return it }
        return try {
            RandomAccessFile(entry.dataFile, "rw").also { entry.raf = it }
        } catch (e: Exception) {
            log("cannot open data file: ${e.message}")
            null
        }
    }

    /** Closes the RAF (ioClosed = true) and deletes the entry dir. Entry must be already unmapped. */
    private fun closeAndRemove(entry: StreamCacheEntryState) {
        entry.ioLock.withLock {
            entry.ioClosed = true
            entry.raf?.let { runCatching { it.close() } }
            entry.raf = null
        }
        runCatching { entry.directory.deleteRecursively() }
    }

    /** Runs [task] on [maintenance] after [delayMs]; false when the executor rejected it. */
    private fun submitMaintenance(delayMs: Long, task: () -> Unit): Boolean = try {
        maintenance.schedule(Runnable {
            try {
                task()
            } catch (t: Throwable) {
                log("maintenance task failed: ${t.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        true
    } catch (rejected: RejectedExecutionException) {
        false
    }

    // endregion

    // region Eviction

    /** Throttled size-limit check (at most every [EVICTION_THROTTLE_MS]) on [maintenance]. */
    fun scheduleEvictionCheck() {
        synchronized(lock) {
            if (evictionScheduled) return
            evictionScheduled = true
        }
        val submitted = submitMaintenance(EVICTION_THROTTLE_MS) {
            synchronized(lock) { evictionScheduled = false }
            evictIfNeeded()
        }
        if (!submitted) {
            synchronized(lock) { evictionScheduled = false }
        }
    }

    /**
     * Deletes least-recently-accessed entries that are not pinned, active or filling until
     * used <= maxBytes (0 = unlimited). Notifies when anything was evicted.
     */
    private fun evictIfNeeded() {
        val limit = maxBytes
        if (limit <= 0) return
        val victims = ArrayList<StreamCacheEntryState>()
        synchronized(lock) {
            var used = entries.values.sumOf { it.ranges.totalLength }
            if (used <= limit) return
            val candidates = entries.values
                .filter { !it.meta.pinned && it.activeSessions == 0 && !it.isFilling }
                .sortedBy { it.meta.lastAccessMs }
            for (candidate in candidates) {
                if (used <= limit) break
                victims.add(candidate)
                entries.remove(candidate.meta.id)
                used -= candidate.ranges.totalLength
            }
        }
        if (victims.isEmpty()) return
        for (victim in victims) {
            closeAndRemove(victim)
        }
        log("evicted ${victims.size} entries to fit the size limit")
        notifier.notifyChanged()
    }

    // endregion

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 10L * 1024L * 1024L * 1024L
        const val MIN_FREE_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_AUTO_FILL: Boolean = true
        const val DEFAULT_LOOK_AHEAD_MINUTES: Int = 10
        const val MAX_LOOK_AHEAD_MINUTES: Int = 60

        /** The filler starts this far past the playhead so it doesn't race the player's own requests. */
        const val FILL_LEAD_BYTES: Long = 32L * 1024L * 1024L

        const val SAVE_DEBOUNCE_MS: Long = 2_000L
        const val EVICTION_THROTTLE_MS: Long = 3_000L

        const val KEY_ENABLED: String = "nuvio.streamcache.enabled"
        const val KEY_MAX_BYTES: String = "nuvio.streamcache.maxBytes"
        const val KEY_AUTO_FILL: String = "nuvio.streamcache.autoFill"
        const val KEY_LOOK_AHEAD_MINUTES: String = "nuvio.streamcache.lookAheadMinutes"

        const val META_FILE_NAME: String = "meta.json"

        private const val TAG = "StreamCache"

        val KNOWN_VIDEO_EXTENSIONS: Set<String> = setOf(
            "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "wmv",
            "flv", "mpg", "mpeg", "ogv", "3gp", "divx", "vob",
        )

        /** Json for meta.json: ignoreUnknownKeys, encodeDefaults, explicitNulls = false. */
        val metaJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun nowMs(): Long = System.currentTimeMillis()

        /** Logcat helper that never throws (android.util.Log is a stub in JVM unit tests). */
        private fun log(message: String) {
            try {
                Log.i(TAG, message)
            } catch (ignored: Throwable) {
            }
        }

        /** First 24 lowercase hex chars of SHA-256(cacheKey UTF-8) — identical to iOS ids. */
        fun entryId(cacheKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val v = byte.toInt() and 0xff
                hex.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
            }
            return hex.substring(0, 24)
        }

        private const val HEX_DIGITS = "0123456789abcdef"

        /** "video/mp4; charset=..." -> "video/mp4"; null for blank. */
        fun normalizedMime(raw: String?): String? {
            if (raw == null) return null
            val trimmed = raw.substringBefore(';').trim().lowercase()
            return trimmed.ifEmpty { null }
        }

        /** mp4/m4v→mp4, quicktime→mov, webm, mp2t→ts, x-msvideo/avi→avi, else mkv (null → mkv). */
        fun extensionForMime(mime: String?): String {
            if (mime == null) return "mkv"
            return when {
                mime.contains("mp4") || mime.contains("m4v") -> "mp4"
                mime.contains("quicktime") -> "mov"
                mime.contains("webm") -> "webm"
                mime.contains("mp2t") -> "ts"
                mime.contains("x-msvideo") || mime.contains("avi") -> "avi"
                else -> "mkv"
            }
        }

        /** Replaces / \ : * ? " < > | newlines and control chars with '_'; trims whitespace and dots. */
        fun sanitizeFileName(raw: String): String {
            val forbidden = "/\\:*?\"<>|"
            val result = StringBuilder(raw.length)
            for (c in raw) {
                val code = c.code
                val isNewline = c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' ||
                    c == '\u0085' || c == '\u2028' || c == '\u2029'
                if (forbidden.indexOf(c) >= 0 || isNewline || code < 32 || code == 127) {
                    result.append('_')
                } else {
                    result.append(c)
                }
            }
            return result.toString().trim { it.isWhitespace() || it == '.' }
        }

        /**
         * Entry file name: [hint], else the last path segment of [sourceUrl]; sanitised
         * ("video" when empty); ensures a known video extension (from the name, else the URL
         * path, else [extensionForMime] → guessed = true); max 150 chars (base cut to 140).
         */
        fun makeFileName(hint: String?, sourceUrl: String, mime: String?): Pair<String, Boolean> {
            val urlLastSegment = lastPathSegment(sourceUrl)
            var raw = hint?.trim().orEmpty()
            if (raw.isEmpty()) {
                raw = urlLastSegment
            }
            var name = sanitizeFileName(raw)
            if (name.isEmpty() || name == "/") {
                name = "video"
            }
            var ext = name.substringAfterLast('.', "").lowercase()
            var guessed = false
            if (ext !in KNOWN_VIDEO_EXTENSIONS) {
                val urlExt = urlLastSegment.substringAfterLast('.', "").lowercase()
                if (urlExt in KNOWN_VIDEO_EXTENSIONS) {
                    ext = urlExt
                } else {
                    ext = extensionForMime(mime)
                    guessed = true
                }
                name += ".$ext"
            }
            if (name.length > 150) {
                val base = deletingPathExtension(name)
                name = base.take(140) + "." + ext
            }
            return name to guessed
        }

        /** Swift `deletingPathExtension` for a plain file name. */
        private fun deletingPathExtension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }

        /** Swift `URL.lastPathComponent` (decoded, trailing slashes ignored); "" when no path. */
        private fun lastPathSegment(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull()
                ?: run {
                    val stripped = url.substringBefore('#').substringBefore('?')
                    val afterScheme = if (stripped.contains("://")) stripped.substringAfter("://") else stripped
                    val slash = afterScheme.indexOf('/')
                    if (slash < 0) "" else afterScheme.substring(slash)
                }
            return path.trimEnd('/').substringAfterLast('/')
        }
    }
}

// endregion
