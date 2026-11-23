package com.nuvio.app.features.streamcache.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.streamcache.StreamCacheBackend
import com.nuvio.app.features.streamcache.StreamCacheContext
import com.nuvio.app.features.streamcache.StreamCacheRepository
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Port of StreamCacheBridgeImpl.swift (+ the Kotlin iOS adapter StreamCacheBackend.ios.kt).
// Owner: AndroidStreamCacheBackend.kt.

/**
 * Android implementation of the shared [StreamCacheBackend] contract. All interface methods are
 * called on the MAIN thread (StreamCacheRepository contract) and must return quickly: disk
 * metadata only, never network I/O (ServerSocket bind is local and fast).
 *
 * Wiring (constructor):
 *  - `rootDir = File(context.filesDir, ROOT_DIR_NAME)` (persistent; mkdirs),
 *  - `preferences = context.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)`,
 *  - [maintenance]: single-thread daemon ScheduledExecutorService ("NuvioStreamCache-maint"),
 *    shared by the store (saves/eviction) and the filler (auto-fill delay, retry back-off),
 *  - [connectionExecutor]: cached pool of daemon threads ("NuvioStreamCache-conn-N"),
 *  - [notifier] → [store] → [upstream] → [proxy] → [filler],
 *  - [installLifecycleObserver].
 *
 * JSON: [getConfigJson] / [listEntriesJson] encode the shared `StreamCacheConfig` /
 * `List<StreamCacheEntry>` data classes with `StreamCacheRepository.json` (done inside the store),
 * so field names always match commonMain.
 */
internal class AndroidStreamCacheBackend private constructor(
    context: Context,
) : StreamCacheBackend {
    private val appContext: Context = context.applicationContext ?: context

    private val mainHandler = Handler(Looper.getMainLooper())

    val maintenance: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("NuvioStreamCache-maint", numbered = false))
    val connectionExecutor: ExecutorService =
        Executors.newCachedThreadPool(daemonThreadFactory("NuvioStreamCache-conn", numbered = true))
    val notifier: StreamCacheNotifier = StreamCacheNotifier(mainHandler)
    val store: StreamCacheStore = StreamCacheStore(
        rootDir = File(appContext.filesDir, ROOT_DIR_NAME).apply { mkdirs() },
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        notifier = notifier,
        maintenance = maintenance,
    )
    val upstream: StreamCacheUpstream = StreamCacheUpstream()
    val proxy: StreamCacheProxyServer = StreamCacheProxyServer(store, upstream, connectionExecutor)
    val filler: StreamCacheFiller = StreamCacheFiller(store, upstream, maintenance)

    init {
        installLifecycleObserver()
    }

    /**
     * Returns the URL the player should load. Returns [sourceUrl] unchanged when the cache does
     * not apply: disabled; not http(s); no host or [isLoopbackHost]; path ends with .m3u8/.mpd;
     * blank `context.cacheKey`; `!store.hasEnoughFreeSpace()`; prepareEntry / fileName fails;
     * proxy cannot listen. Otherwise: `store.prepareEntry(cacheKey, contextJson =
     * StreamCacheRepository.json.encodeToString(context), fileNameHint = context.fileName,
     * sourceUrl, headers)`, `proxy.registerSession(...)`, `filler.scheduleAutoFill(id)` when
     * autoFillWhilePlaying, and returns `proxy.playbackUrl(port, session)`.
     */
    override fun preparePlaybackUrl(
        sourceUrl: String,
        headers: Map<String, String>,
        context: StreamCacheContext,
    ): String {
        return try {
            if (!store.isEnabled) return sourceUrl
            // toHttpUrlOrNull only accepts http/https (case-insensitive) and strips IPv6 brackets.
            val url = sourceUrl.trim().toHttpUrlOrNull() ?: return sourceUrl
            val host = url.host
            if (host.isEmpty() || isLoopbackHost(host)) return sourceUrl
            val path = url.encodedPath.lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return sourceUrl
            val cacheKey = context.cacheKey
            if (cacheKey.isBlank()) return sourceUrl
            if (!store.hasEnoughFreeSpace()) {
                Log.i(TAG, "low disk space; streaming without cache")
                return sourceUrl
            }

            val contextJson = StreamCacheRepository.json.encodeToString(StreamCacheContext.serializer(), context)
            val id = store.prepareEntry(
                cacheKey = cacheKey,
                contextJson = contextJson,
                fileNameHint = context.fileName,
                sourceUrl = sourceUrl,
                headers = headers,
            ) ?: return sourceUrl
            val fileName = store.fileName(id) ?: return sourceUrl

            val port = proxy.ensureListening()
            if (port == null) {
                Log.w(TAG, "proxy unavailable; streaming without cache")
                return sourceUrl
            }
            val session = proxy.registerSession(
                entryId = id,
                upstreamUrl = sourceUrl,
                headers = headers,
                fileName = fileName,
            )
            if (store.autoFillWhilePlaying) {
                filler.scheduleAutoFill(id)
            }
            proxy.playbackUrl(port, session)
        } catch (error: Throwable) {
            // Never break playback because of the cache: fall back to direct streaming.
            Log.w(TAG, "preparePlaybackUrl failed; streaming without cache", error)
            sourceUrl
        }
    }

    /** `proxy.releasePlaybackUrl(playbackUrl)` (file sessions and proxy sessions). */
    override fun releasePlaybackUrl(playbackUrl: String) {
        runCatching { proxy.releasePlaybackUrl(playbackUrl) }
            .onFailure { Log.w(TAG, "releasePlaybackUrl failed", it) }
    }

    /**
     * Plays a cached entry without any stream lookup (offline-capable). Null when the entry is
     * missing or has 0 cached bytes. Complete entry and `store.prepareFilePlayback(id)` →
     * `Uri.fromFile(dataFile).toString()` ("file:///data/user/0/.../stream_cache/<id>/data.mkv"),
     * registered with `proxy.registerFileSession(id, url)`. Partial → proxy session from the
     * stored sourceUrl/headers (upstreamUrl may be null), auto-fill scheduled when a sourceUrl is
     * stored and the setting is on. Works regardless of the "enabled" setting.
     */
    override fun entryPlaybackUrl(id: String): String? {
        return try {
            val info = store.entryPlaybackInfo(id) ?: return null
            if (info.cachedBytes <= 0L) return null

            if (info.isComplete && store.prepareFilePlayback(id)) {
                val fileUrl = Uri.fromFile(info.dataFile).toString()
                proxy.registerFileSession(id, fileUrl)
                return fileUrl
            }

            val port = proxy.ensureListening()
            if (port == null) {
                Log.w(TAG, "proxy unavailable; cannot play cached entry $id")
                return null
            }
            val session = proxy.registerSession(
                entryId = id,
                upstreamUrl = info.sourceUrl,
                headers = info.headers,
                fileName = info.fileName,
            )
            if (info.sourceUrl != null && store.autoFillWhilePlaying) {
                filler.scheduleAutoFill(id)
            }
            proxy.playbackUrl(port, session)
        } catch (error: Throwable) {
            Log.w(TAG, "entryPlaybackUrl failed for $id", error)
            null
        }
    }

    override fun getConfigJson(): String = store.configJson()

    override fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
    }

    override fun setMaxBytes(maxBytes: Long) {
        store.setMaxBytes(maxBytes)
    }

    override fun setAutoFillWhilePlaying(enabled: Boolean) {
        store.setAutoFillWhilePlaying(enabled)
    }

    override fun setLookAheadMinutes(minutes: Int) {
        store.setLookAheadMinutes(minutes)
    }

    override fun listEntriesJson(): String = store.listEntriesJson()

    /** `filler.cancelFill(id)` then `store.deleteEntry(id)`. */
    override fun deleteEntry(id: String) {
        filler.cancelFill(id)
        store.deleteEntry(id)
    }

    /** `filler.cancelAll()` then `store.clearAll()`. */
    override fun clearAll() {
        filler.cancelAll()
        store.clearAll()
    }

    override fun setPinned(id: String, pinned: Boolean) {
        store.setPinned(id, pinned)
    }

    override fun startFill(id: String) {
        filler.startFill(id)
    }

    override fun cancelFill(id: String) {
        filler.cancelFill(id)
    }

    /** `store.exportEntry(id, destinationPath)` (null on success, else message). */
    override fun exportEntry(id: String, destinationPath: String): String? =
        try {
            store.exportEntry(id, destinationPath)
        } catch (error: Throwable) {
            Log.w(TAG, "exportEntry failed for $id", error)
            "Could not move the file: ${error.message ?: error.javaClass.simpleName}"
        }

    /** `notifier.setListener(listener)` (≤ 2 Hz, main thread). */
    override fun setChangeListener(listener: (() -> Unit)?) {
        notifier.setListener(listener)
    }

    /**
     * Current look-ahead for the Android players (read by PlayerEngine.android.kt on each load):
     * `store.lookAheadMinutes` (0 = player default).
     */
    fun lookAheadMinutes(): Int = store.lookAheadMinutes

    /**
     * `ProcessLifecycleOwner` observer (added on the main thread):
     *  - ON_STOP (app backgrounded) → `store.flushAll()` on [maintenance]. The proxy and fills
     *    keep running in-process (background playback / PiP keep streaming through it).
     *  - ON_START (foregrounded) → `proxy.restartIfNeeded()` (rebinds only if the accept loop died).
     */
    private fun installLifecycleObserver() {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> runCatching {
                    maintenance.execute {
                        runCatching { store.flushAll() }
                            .onFailure { Log.w(TAG, "flushAll failed", it) }
                    }
                }
                Lifecycle.Event.ON_START -> runCatching { proxy.restartIfNeeded() }
                    .onFailure { Log.w(TAG, "proxy restart failed", it) }
                else -> Unit
            }
        }
        val install = {
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
                .onFailure { Log.w(TAG, "could not observe the process lifecycle", it) }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) install() else mainHandler.post(install)
    }

    companion object {
        /**
         * Gate flipped to `true` by the integrator once every engine file is implemented. While
         * false, [getOrNull] returns null, so the shared UI stays hidden on Android and the
         * TODO() skeleton can never be reached at runtime.
         */
        const val IMPLEMENTATION_READY: Boolean = true

        const val ROOT_DIR_NAME: String = "stream_cache"
        const val PREFERENCES_NAME: String = "nuvio_stream_cache"

        private const val TAG = "NuvioStreamCache"

        private val instanceLock = Any()

        @Volatile
        private var instance: AndroidStreamCacheBackend? = null

        /**
         * Lazily creates the singleton with the application Context from
         * `PlayerSettingsStorage.applicationContext()` (initialised in MainActivity.onCreate).
         * Null when [IMPLEMENTATION_READY] is false, the context is not available yet (the
         * repository asks again next time), or construction throws (logged).
         */
        @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
        fun getOrNull(): AndroidStreamCacheBackend? {
            if (!IMPLEMENTATION_READY) return null
            instance?.let { return it }
            val context = PlayerSettingsStorage.applicationContext() ?: return null
            return try {
                get(context)
            } catch (error: Throwable) {
                Log.e(TAG, "stream cache unavailable", error)
                null
            }
        }

        /** Creates / returns the singleton for an explicit [context] (tests, early init). */
        fun get(context: Context): AndroidStreamCacheBackend {
            instance?.let { return it }
            synchronized(instanceLock) {
                instance?.let { return it }
                val created = AndroidStreamCacheBackend(context)
                instance = created
                return created
            }
        }

        /**
         * localhost, *.localhost, 127.*, ::1, 0.0.0.0, :: (brackets stripped, case-insensitive)
         * — the P2P engine and other local servers are never cached.
         */
        fun isLoopbackHost(host: String): Boolean {
            val normalized = host.lowercase().trim('[', ']')
            return normalized == "localhost" ||
                normalized.endsWith(".localhost") ||
                normalized.startsWith("127.") ||
                normalized == "::1" ||
                normalized == "0.0.0.0" ||
                normalized == "::"
        }

        private fun daemonThreadFactory(name: String, numbered: Boolean): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                val threadName = if (numbered) "$name-${counter.incrementAndGet()}" else name
                Thread(runnable, threadName).apply { isDaemon = true }
            }
        }
    }
}
