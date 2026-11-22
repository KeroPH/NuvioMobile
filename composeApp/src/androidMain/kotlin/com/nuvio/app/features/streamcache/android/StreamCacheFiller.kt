package com.nuvio.app.features.streamcache.android

import android.os.Process
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// Port of StreamCacheFiller.swift (+ BridgeImpl.scheduleAutoFill).
// Owner: StreamCacheFiller.kt.

private const val FILL_LOG_TAG = "StreamCache"

/** android.util.Log is unavailable in plain JVM unit tests; never let logging throw. */
private fun fillLog(message: String) {
    try {
        Log.i(FILL_LOG_TAG, message)
    } catch (_: Throwable) {
        println("[$FILL_LOG_TAG] $message")
    }
}

/**
 * "Finish download": fetches the missing spans of entries in the background, one job (one
 * daemon thread, "NuvioStreamCache-fill-<id>") per entry. In-process only: runs while the app
 * process lives (no WorkManager); if Android kills the process the fill simply stops and the
 * entry keeps what was written (isFilling is memory-only, so it reads as not filling next launch).
 * Thread-safe.
 */
internal class StreamCacheFiller(
    private val store: StreamCacheStore,
    private val upstream: StreamCacheUpstream,
    /** Used for delayed auto-fill starts and retry back-off. */
    private val scheduler: ScheduledExecutorService,
) {
    private val lock = Any()
    private val jobs: HashMap<String, StreamCacheFillJob> = HashMap()

    /** No-op if a job exists or `store.beginFill(id)` fails; else creates and starts a job. */
    fun startFill(id: String) {
        val job: StreamCacheFillJob
        synchronized(lock) {
            if (jobs.containsKey(id)) return
            if (!store.beginFill(id)) return
            job = StreamCacheFillJob(id, store, upstream, this)
            jobs[id] = job
        }
        fillLog("fill started for $id")
        try {
            job.start()
        } catch (t: Throwable) {
            // Could not spawn the thread (OOM etc.): undo the bookkeeping.
            jobFinished(job, t.message ?: "Download failed")
        }
    }

    /** Cancels the job (if any) and `store.endFill(id, null)`. */
    fun cancelFill(id: String) {
        val job = synchronized(lock) { jobs.remove(id) } ?: return
        job.cancel()
        store.endFill(id, null)
    }

    /** Cancels every job (`endFill(id, null)` each). */
    fun cancelAll() {
        val all = synchronized(lock) {
            val copy = HashMap(jobs)
            jobs.clear()
            copy
        }
        for ((id, job) in all) {
            job.cancel()
            store.endFill(id, null)
        }
    }

    /**
     * "Fill while playing": after [delayMs] on [scheduler], starts a fill only if
     * `store.isEnabled && store.autoFillWhilePlaying && store.hasEnoughFreeSpace() &&
     * store.canAutoFill(id)`. The fill keeps running after playback ends; turning the setting
     * off does not cancel it.
     */
    fun scheduleAutoFill(id: String, delayMs: Long = AUTO_FILL_DELAY_MS) {
        val task = Runnable {
            try {
                if (store.isEnabled &&
                    store.autoFillWhilePlaying &&
                    store.hasEnoughFreeSpace() &&
                    store.canAutoFill(id)
                ) {
                    fillLog("auto-fill while playing for $id")
                    startFill(id)
                }
            } catch (t: Throwable) {
                fillLog("auto-fill for $id failed to start: ${t.message}")
            }
        }
        try {
            scheduler.schedule(task, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            // Scheduler shut down (backend torn down) — nothing to do.
            fillLog("auto-fill for $id not scheduled: ${t.message}")
        }
    }

    /** Called by a job when it ends; ignored unless [job] is still the current job for its id. */
    internal fun jobFinished(job: StreamCacheFillJob, error: String?) {
        val isCurrent = synchronized(lock) {
            if (jobs[job.id] === job) {
                jobs.remove(job.id)
                true
            } else {
                false
            }
        }
        if (!isCurrent) return
        if (error != null) {
            fillLog("fill for ${job.id} stopped: $error")
        } else {
            fillLog("fill for ${job.id} complete")
        }
        store.endFill(job.id, error)
    }

    companion object {
        /** Delay before an automatic fill starts, so the player's first requests get the bandwidth. */
        const val AUTO_FILL_DELAY_MS: Long = 8_000L
    }
}

/**
 * One fill operation (blocking loop on its own thread). Requests ≤ [CHUNK_SIZE] spans one after
 * another and streams them to disk. Before every chunk the gap is recomputed from
 * `store.fillSource(id)` (starting just ahead of the proxy's playhead, wrapping to earlier
 * gaps); while receiving, the request is abandoned (no retry penalty) as soon as it runs into
 * bytes someone else (the live proxy) already cached.
 *
 * Loop (port of Swift requestNext / handleResponse / handleData / handleCompletion):
 *  - stop if cancelled; `!store.hasEnoughFreeSpace()` → "Not enough free storage space";
 *    fillSource null → (entryExists ? "No stream link stored — play it from the stream list to
 *    refresh the link" : "Cache entry no longer exists"); known total and gap null → done (null).
 *  - range = gap.start .. min(gap.end, gap.start + CHUNK_SIZE) - 1 (unknown total: 0..CHUNK_SIZE-1).
 *  - status != 206: 401/403/410 → [EXPIRED_MESSAGE]; 404 → "Link no longer available (HTTP 404)
 *    — play it again to refresh the link"; 200 → "The server does not support resuming
 *    downloads"; else "Server error (HTTP n)". Content-Range missing or start mismatch →
 *    "Unexpected server response". New total != known → `store.setTotal` (null → "Cache entry
 *    no longer exists"); if a known total changed → restart from the next gap. Total still
 *    unknown → "The server did not report the file size".
 *  - body: `store.write(...)`; on false → entry gone / low disk / generation changed (restart) /
 *    "Could not write to storage". After each write, if position <= end and
 *    `store.cachedEnd(id, position) != null` → abandon request, restart (no retry penalty).
 *  - transport error: offline ([StreamCacheUpstream.isOfflineError]) → [OFFLINE_MESSAGE]
 *    immediately; error / no progress → retry up to [MAX_RETRIES] with retries*2 s back-off,
 *    then fail with the error message ("Download failed"); progress resets retries.
 *  - finish → `filler.jobFinished(this, error)`.
 */
internal class StreamCacheFillJob(
    val id: String,
    private val store: StreamCacheStore,
    private val upstream: StreamCacheUpstream,
    private val filler: StreamCacheFiller,
) : Runnable {
    @Volatile
    private var cancelled = false

    /** Current upstream response so [cancel] can abort a blocked read. */
    @Volatile
    private var current: StreamCacheUpstreamResponse? = null

    private var thread: Thread? = null

    /** Released by [cancel]; back-off waits on it so a cancel wakes them without interrupts. */
    private val cancelSignal = CountDownLatch(1)

    /** Outcome of one ranged request. */
    private sealed class ChunkOutcome {
        /** Request finished (body consumed / ended); [madeProgress] = at least one byte stored. */
        data class Done(val madeProgress: Boolean) : ChunkOutcome()

        /** Pick the next gap immediately, no retry penalty (overlap with proxy, reset). */
        object Restart : ChunkOutcome()

        /** Stop the job with this message. */
        data class Fail(val message: String) : ChunkOutcome()

        /** Transport error (retryable). */
        data class Transport(val error: Throwable) : ChunkOutcome()

        /** Job was cancelled while the request ran. */
        object Cancelled : ChunkOutcome()
    }

    /** Starts [run] on a new daemon thread. */
    fun start() {
        val t = Thread(this, "NuvioStreamCache-fill-$id")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Sets cancelled, cancels the current upstream call, interrupts back-off sleeps. Any thread. */
    fun cancel() {
        cancelled = true
        cancelSignal.countDown()
        try {
            current?.cancel()
        } catch (_: Throwable) {
        }
    }

    override fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {
            // Not on Android (unit tests) — keep default priority.
        }
        val error: String? = try {
            fillLoop()
        } catch (t: Throwable) {
            if (cancelled) null else (t.message ?: "Download failed")
        }
        // Harmless when cancelled: the filler already dropped this job, so it is ignored.
        if (!cancelled) {
            filler.jobFinished(this, error)
        }
    }

    /** Returns the final error (null = complete or cancelled). */
    private fun fillLoop(): String? {
        var retries = 0
        while (true) {
            if (cancelled) return null
            if (!store.hasEnoughFreeSpace()) return "Not enough free storage space"
            val source = store.fillSource(id)
                ?: return if (store.entryExists(id)) {
                    "No stream link stored — play it from the stream list to refresh the link"
                } else {
                    "Cache entry no longer exists"
                }
            val start: Long
            val end: Long
            if (source.totalBytes > 0) {
                val gap = source.gap ?: return null
                start = gap.start
                end = minOf(gap.end, gap.start + CHUNK_SIZE) - 1
            } else {
                start = 0L
                end = CHUNK_SIZE - 1
            }

            when (val outcome = fetchChunk(source, start, end)) {
                is ChunkOutcome.Cancelled -> return null
                is ChunkOutcome.Restart -> continue
                is ChunkOutcome.Fail -> return outcome.message
                is ChunkOutcome.Done -> {
                    if (outcome.madeProgress) {
                        retries = 0
                        continue
                    }
                    // No bytes stored (empty body): retry like Swift's !madeProgress path.
                    if (retries < MAX_RETRIES) {
                        retries += 1
                        if (backOff(retries)) return null
                        continue
                    }
                    return "Download failed"
                }
                is ChunkOutcome.Transport -> {
                    if (isOffline(outcome.error)) {
                        // No network (e.g. airplane mode while playing from the cache): stop right
                        // away with a single error instead of retrying.
                        return OFFLINE_MESSAGE
                    }
                    if (retries < MAX_RETRIES) {
                        retries += 1
                        if (backOff(retries)) return null
                        continue
                    }
                    return outcome.error.message?.takeIf { it.isNotBlank() } ?: "Download failed"
                }
            }
        }
    }

    /** Waits retries*2 s; returns true when cancelled meanwhile. */
    private fun backOff(retries: Int): Boolean {
        if (cancelled) return true
        return try {
            cancelSignal.await(retries * 2_000L, TimeUnit.MILLISECONDS) || cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }
    }

    private fun isOffline(error: Throwable): Boolean =
        (error is StreamCacheUpstreamException && error.isOffline) ||
            StreamCacheUpstream.isOfflineError(error)

    /** One ranged GET `bytes=start-end`, streamed to the store. */
    private fun fetchChunk(source: StreamCacheFillSource, start: Long, end: Long): ChunkOutcome {
        var knownTotal = source.totalBytes
        var generation = source.generation

        val response = try {
            upstream.open(source.url, source.headers, "bytes=$start-$end", "GET")
        } catch (e: Throwable) {
            return if (cancelled) ChunkOutcome.Cancelled else ChunkOutcome.Transport(e)
        }
        current = response
        // cancel() may have run between open() returning and `current` being published.
        if (cancelled) {
            abandon(response)
            return ChunkOutcome.Cancelled
        }

        var position = start
        try {
            val status = response.status
            if (status != 206) {
                abandon(response)
                return ChunkOutcome.Fail(
                    when (status) {
                        401, 403, 410 -> EXPIRED_MESSAGE
                        404 -> "Link no longer available (HTTP 404) — play it again to refresh the link"
                        200 -> "The server does not support resuming downloads"
                        else -> "Server error (HTTP $status)"
                    },
                )
            }
            val contentRange = response.contentRange
            if (contentRange == null || contentRange.start != start || response.isEncoded) {
                // Encoded bodies would not match the file's byte offsets.
                abandon(response)
                return ChunkOutcome.Fail("Unexpected server response")
            }
            val total = contentRange.total
            if (total != null && total != knownTotal) {
                val newGeneration = store.setTotal(id, total, response.contentType)
                if (newGeneration == null) {
                    abandon(response)
                    return ChunkOutcome.Fail("Cache entry no longer exists")
                }
                val sizeChanged = knownTotal > 0
                knownTotal = total
                generation = newGeneration
                if (sizeChanged) {
                    // Entry was reset; start over from the first gap.
                    abandon(response)
                    return ChunkOutcome.Restart
                }
            }
            if (knownTotal <= 0) {
                // Without a total size the gaps can't be computed (would refetch [0, 8 MiB) forever).
                abandon(response)
                return ChunkOutcome.Fail("The server did not report the file size")
            }

            val buffer = ByteArray(COPY_BUFFER)
            val body = response.body
            while (position <= end) {
                if (cancelled) {
                    abandon(response)
                    return ChunkOutcome.Cancelled
                }
                val remaining = end - position + 1
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val n = body.read(buffer, 0, want)
                if (n < 0) break
                if (n == 0) continue
                if (cancelled) {
                    abandon(response)
                    return ChunkOutcome.Cancelled
                }
                if (!store.write(id, generation, position, buffer, 0, n)) {
                    abandon(response)
                    if (!store.entryExists(id)) return ChunkOutcome.Fail("Cache entry no longer exists")
                    if (!store.hasEnoughFreeSpace()) return ChunkOutcome.Fail("Not enough free storage space")
                    val info = store.serveInfo(id)
                    if (info != null && info.generation != generation) return ChunkOutcome.Restart
                    return ChunkOutcome.Fail("Could not write to storage")
                }
                position += n
                // The live proxy may have cached the bytes ahead of us meanwhile: stop this request
                // and pick the next gap instead of downloading them twice (no retry penalty).
                if (position <= end && store.cachedEnd(id, position) != null) {
                    abandon(response)
                    return ChunkOutcome.Restart
                }
            }
            return ChunkOutcome.Done(madeProgress = position > start)
        } catch (e: IOException) {
            if (cancelled) return ChunkOutcome.Cancelled
            abandon(response)
            return ChunkOutcome.Transport(e)
        } finally {
            current = null
            try {
                response.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** Aborts a response whose remaining body we don't want (drops the connection). */
    private fun abandon(response: StreamCacheUpstreamResponse) {
        try {
            response.cancel()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val CHUNK_SIZE: Long = 8L * 1024L * 1024L
        const val MAX_RETRIES: Int = 3
        const val COPY_BUFFER: Int = 64 * 1024
        const val EXPIRED_MESSAGE: String = "Link expired — play it again to refresh the link"
        const val OFFLINE_MESSAGE: String = "No internet connection"
    }
}
