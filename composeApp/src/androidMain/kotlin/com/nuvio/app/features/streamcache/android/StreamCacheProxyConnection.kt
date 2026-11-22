package com.nuvio.app.features.streamcache.android

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean

// Port of StreamCacheProxyConnection.swift. Swift is an event-driven state machine on a
// serial queue; Android is a straight-line blocking serve loop on one pool thread.
// Owner: StreamCacheProxyConnection.kt.

/**
 * One client (ExoPlayer / libmpv) TCP connection to the proxy. Handles exactly ONE HTTP
 * request, then closes (`Connection: close` on every response).
 *
 * [run] (on a connection-pool thread):
 *  1. `socket.soTimeout = CLIENT_READ_TIMEOUT_MS` only while reading the request head;
 *     `tcpNoDelay = true`. Read the head with [StreamCacheHttp.readRequestHead] (null → close).
 *  2. Method must be GET or HEAD (else 405). Target `/s/<token>/<rest>[?query]`; unknown
 *     token → 404. `server.associate(this, token)`.
 *  3. `<rest>` percent-decoded empty or == session.fileName → [serveMain]; otherwise it is a
 *     relative reference (manifest child): resolve `rest + query` against
 *     session.upstreamUrl ([resolveRelative], `java.net.URL(context, spec)`) → [servePassthrough]; no upstream → 404.
 *  4. Always: close upstream response, close socket, `server.connectionClosed(this)`.
 *
 * Serving the main file ([serveMain]), same rules as Swift:
 *  - `store.touch`; entry gone → pass-through of session.upstreamUrl (or 404).
 *  - `cacheWrites = store.isEnabled` (sessions from entryPlaybackUrl exist even when disabled:
 *    serve cached bytes, store nothing new).
 *  - mime = stored mime ?: [StreamCacheHttp.mimeTypeForFileName].
 *  - total known (> 0): no probe. If no upstream and the resolved start is not cached → 502.
 *    Send ranged head (416 if unsatisfiable; HEAD stops after the head), then [pump].
 *  - total unknown: PROBE upstream GET `Range: bytes=<start>-` (start = From.start else 0).
 *    Non 200/206 → forward status (400..599) else 502. Manifest content-type → pass-through
 *    using this response. 206 with Content-Range start == probe start, or 200 when start == 0
 *    (total = Content-Length), with total > 0 and not encoded → `store.setTotal` (null →
 *    cacheWrites = false), send ranged head, and if the client position == probe start and is
 *    not cached, REUSE the probe body as the first segment (segmentEnd = min(end,
 *    nextCachedStart-1)); otherwise close the probe and [pump]. Anything else → pass-through.
 *
 * [pump]: loop while position <= endInclusive: `store.notePlayhead(id, position)`;
 *  cached → read ≤ READ_CHUNK from disk ([StreamCacheStore.read]) and write to the socket
 *  (read failure → close); missing → [fetchSegment] for `[position, min(end, nextCachedStart-1)]`.
 *
 * [fetchSegment] / segment body copy (shared with the reused probe):
 *  - Response must be 206 with Content-Range start == requested start (or 200 when start == 0
 *    and Content-Length == total). A different Content-Range total → `store.setTotal(...)`
 *    (restarts the entry) and close. Other statuses → close (headers were already sent).
 *  - Copy loop: read ≤ COPY_BUFFER bytes, clip to segmentEnd, `store.write` when cacheWrites,
 *    write to the client, position += n, notePlayhead. Stop the request and return to [pump]
 *    when position > segmentEnd, or when cacheWrites and `store.cachedEnd(position) != null`
 *    (the filler cached the bytes ahead — serve them from disk instead).
 *  - Upstream EOF/IOException after progress → return to [pump] (fresh request for the rest,
 *    e.g. server-capped ranges or an idle timeout while the client wasn't reading);
 *    without progress → close.
 *  - Client write IOException (player seeked/stopped) → close immediately.
 *
 * Backpressure is natural: socket writes block while the player isn't reading; upstream reads
 * stop meanwhile (an upstream timeout during a long stall is handled by the resume rule above).
 *
 * Pass-through ([servePassthrough]): forward the client's raw Range header and method; send
 * status + Content-Type, Content-Length (only when not encoded), Content-Range, Accept-Ranges;
 * HEAD stops after the head; then copy the body (nothing is cached). Upstream failure before
 * the head → 502, after → close.
 *
 * Client disconnects are detected on the next socket write (the player stops reading → the
 * write throws), which tears down the connection and cancels the upstream call. A release of
 * the session ([cancelFromOutside]) closes the socket and cancels the upstream call directly,
 * so even a thread blocked in an upstream read is woken up.
 */
internal class StreamCacheProxyConnection(
    private val socket: Socket,
    private val server: StreamCacheProxyServer,
    private val store: StreamCacheStore,
    private val upstream: StreamCacheUpstream,
) : Runnable {
    /** Set once; read by [cancelFromOutside] from other threads. */
    @Volatile
    private var closed = false

    /** Current upstream response, so [cancelFromOutside] can abort a blocked read. */
    @Volatile
    private var currentUpstream: StreamCacheUpstreamResponse? = null

    // Serve state (connection thread only).
    private var session: StreamCacheSession? = null
    private var isHead = false
    private var rangeRequest: StreamCacheRangeRequest = StreamCacheRangeRequest.Whole
    private var rawRangeHeader: String? = null
    private var cacheWrites = true
    private var generation = 0
    private var totalBytes = -1L
    private var mimeType = "application/octet-stream"
    private var position = 0L
    private var endInclusive = -1L
    private var headersSent = false

    /** Guards the one-time teardown in [close]. */
    private val tornDown = AtomicBoolean(false)

    /** Shared disk/upstream buffer (allocated on first use, connection thread only). */
    private var bufferStorage: ByteArray? = null
    private val buffer: ByteArray
        get() = bufferStorage ?: ByteArray(READ_CHUNK).also { bufferStorage = it }

    /** Thrown when a write to the player fails (it seeked/stopped): unwinds to [run]. */
    private class ClientGoneException(cause: Throwable) : RuntimeException(cause)

    override fun run() {
        try {
            if (closed) return
            socket.tcpNoDelay = true
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            val request = StreamCacheHttp.readRequestHead(socket.getInputStream()) ?: return
            // Body streaming has no client timeout (writes block for backpressure instead).
            socket.soTimeout = 0
            if (closed) return
            handleRequest(request, socket.getOutputStream())
        } catch (_: ClientGoneException) {
            // Player went away (seek / stop): normal.
        } catch (error: IOException) {
            if (!closed) logDebug("connection ended: ${error.message}")
        } catch (error: Throwable) {
            logWarn("connection failed", error)
        } finally {
            close()
        }
    }

    /**
     * Called by the server when the session is released (any thread): marks closed, cancels
     * the current upstream call and closes the socket so blocked reads/writes throw. Idempotent.
     */
    fun cancelFromOutside() {
        closed = true
        currentUpstream?.let { runCatching { it.cancel() } }
        runCatching { socket.close() }
        // Upstream close + server bookkeeping happen on the connection thread ([run] → [close]),
        // so the server can call this while iterating its own connection set.
    }

    /** Tears everything down: upstream response, socket, server bookkeeping. Idempotent. */
    private fun close() {
        if (!tornDown.compareAndSet(false, true)) return
        closed = true
        currentUpstream?.let { response ->
            currentUpstream = null
            runCatching { response.cancel() }
            runCatching { response.close() }
        }
        // FIN after everything written (Swift: final message, then cancel).
        runCatching { if (!socket.isClosed) socket.shutdownOutput() }
        runCatching { socket.close() }
        runCatching { server.connectionClosed(this) }
    }

    // region Request

    private fun handleRequest(request: StreamCacheHttpRequest, output: OutputStream) {
        val method = request.method.uppercase()
        if (method != "GET" && method != "HEAD") {
            sendSimple(405, output)
            return
        }
        isHead = method == "HEAD"
        rawRangeHeader = request.rangeHeader
        rangeRequest = StreamCacheHttp.parseRange(rawRangeHeader)

        // Target: /s/<token>/<file name or relative resource>[?query]
        val components = request.path.split("/")
        if (components.size < 3 || components[1] != "s") {
            sendSimple(404, output)
            return
        }
        val token = components[2]
        val session = server.session(token)
        if (session == null) {
            sendSimple(404, output)
            return
        }
        this.session = session
        server.associate(this, token)
        if (closed) return

        val restRaw = if (components.size > 3) components.subList(3, components.size).joinToString("/") else ""
        val restDecoded = percentDecode(restRaw) ?: restRaw
        if (restDecoded.isEmpty() || restDecoded == session.fileName) {
            serveMain(session, output)
        } else {
            // Relative reference (e.g. from a manifest): resolve against the upstream URL.
            val base = session.upstreamUrl
            val resolved = base?.let { resolveRelative(it, restRaw + request.query) }
            if (resolved == null) {
                sendSimple(404, output)
                return
            }
            servePassthrough(resolved, output)
        }
    }

    // endregion

    // region Main file

    private fun serveMain(session: StreamCacheSession, output: OutputStream) {
        val id = session.entryId
        store.touch(id)
        val info = store.serveInfo(id)
        if (info == null) {
            // Entry was deleted while playing: plain pass-through (if we know the upstream).
            val upstreamUrl = session.upstreamUrl
            if (upstreamUrl != null) {
                servePassthrough(upstreamUrl, output)
            } else {
                sendSimple(404, output)
            }
            return
        }
        // Sessions created by `entryPlaybackUrl` exist even when caching is disabled: still serve
        // cached bytes, but don't store new ones.
        if (!store.isEnabled) cacheWrites = false
        generation = info.generation
        mimeType = info.mimeType ?: StreamCacheHttp.mimeTypeForFileName(session.fileName)
        totalBytes = info.totalBytes
        if (totalBytes > 0) {
            // Size known: no upstream probe. Cached spans are served from disk with zero network;
            // only gaps (in `pump`) go upstream.
            if (session.upstreamUrl == null && !isHead) {
                val resolved = StreamCacheHttp.resolveRange(rangeRequest, totalBytes)
                if (resolved != null && store.cachedEnd(id, resolved.start) == null) {
                    logWarn("uncached range requested and no upstream URL stored")
                    sendSimple(502, output)
                    return
                }
            }
            if (beginRangedResponse(output)) pump(output)
            return
        }
        val upstreamUrl = session.upstreamUrl
        if (upstreamUrl == null) {
            sendSimple(502, output)
            return
        }
        val probeStart = (rangeRequest as? StreamCacheRangeRequest.From)?.start ?: 0L
        val response = openUpstream(upstreamUrl, "bytes=$probeStart-", "GET")
        if (response == null) {
            sendSimple(502, output)
            return
        }
        handleProbe(response, probeStart, output)
    }

    /**
     * Handles the probe response (see class KDoc). Returns normally when the response was
     * fully served or the connection must close.
     */
    private fun handleProbe(response: StreamCacheUpstreamResponse, probeStart: Long, output: OutputStream) {
        val status = response.status
        if (status != 200 && status != 206) {
            logWarn("upstream answered HTTP $status")
            releaseUpstream(response, graceful = false)
            sendSimple(if (status in 400..599) status else 502, output)
            return
        }
        if (response.isManifest) {
            servePassthrough(session?.upstreamUrl.orEmpty(), output, existing = response)
            return
        }
        var upstreamTotal = -1L
        var startMatches = false
        val contentRange = response.contentRange
        if (status == 206 && contentRange != null) {
            startMatches = contentRange.start == probeStart
            upstreamTotal = contentRange.total ?: -1L
        } else if (status == 200) {
            // A 200 to `bytes=N-` with N > 0 means Range is not honoured: pass through.
            startMatches = probeStart == 0L
            upstreamTotal = if (probeStart == 0L) response.contentLength else -1L
        }
        val entryId = session?.entryId
        if (!startMatches || upstreamTotal <= 0 || response.isEncoded || entryId == null) {
            servePassthrough(session?.upstreamUrl.orEmpty(), output, existing = response)
            return
        }

        val newGeneration = store.setTotal(entryId, upstreamTotal, response.contentType)
        if (newGeneration != null) {
            generation = newGeneration
        } else {
            cacheWrites = false
        }
        totalBytes = upstreamTotal
        val mime = StreamCacheStore.normalizedMime(response.contentType)
        if (mime != null && mime != "application/octet-stream") mimeType = mime

        if (!beginRangedResponse(output)) {
            releaseUpstream(response, graceful = false)
            return
        }
        if (position == probeStart && store.cachedEnd(entryId, position) == null) {
            // Reuse the probe response as the first missing segment.
            val nextCached = store.nextCachedStart(entryId, position) ?: totalBytes
            val segmentEnd = minOf(endInclusive, nextCached - 1)
            if (!copySegment(response, probeStart, segmentEnd, output)) return
        } else {
            releaseUpstream(response, graceful = false)
        }
        pump(output)
    }

    /** Resolves [rangeRequest] against [totalBytes], writes the head. True when a body follows. */
    private fun beginRangedResponse(output: OutputStream): Boolean {
        val resolved = StreamCacheHttp.resolveRange(rangeRequest, totalBytes)
        if (resolved == null) {
            sendHead(416, StreamCacheHttp.unsatisfiableHeaders(totalBytes), output)
            return false
        }
        position = resolved.start
        endInclusive = resolved.endInclusive
        sendHead(
            StreamCacheHttp.rangedStatus(rangeRequest),
            StreamCacheHttp.rangedResponseHeaders(rangeRequest, resolved, totalBytes, mimeType),
            output,
        )
        return !isHead && !closed
    }

    /** Serves `[position, endInclusive]`: cached spans from disk, gaps via [fetchSegment]. */
    private fun pump(output: OutputStream) {
        val session = session ?: return
        val id = session.entryId
        val buf = buffer
        while (!closed) {
            if (position > endInclusive) return // complete; run() closes gracefully
            store.notePlayhead(id, position)
            val cachedEnd = store.cachedEnd(id, position)
            if (cachedEnd != null) {
                val length = minOf(READ_CHUNK.toLong(), cachedEnd - position, endInclusive + 1 - position).toInt()
                if (length <= 0) {
                    logWarn("empty cached span at $position; closing connection")
                    return
                }
                if (!store.read(id, position, buf, 0, length)) {
                    logWarn("disk read failed at $position; closing connection")
                    return
                }
                writeClient(output, buf, 0, length)
                position += length
                continue
            }
            // Missing: fetch up to the next cached span (or the requested end).
            val upstreamUrl = session.upstreamUrl
            if (upstreamUrl == null) {
                logWarn("gap at $position and no upstream URL stored; closing connection")
                return
            }
            val nextCached = store.nextCachedStart(id, position) ?: totalBytes
            val segmentEnd = minOf(endInclusive, nextCached - 1)
            if (!fetchSegment(upstreamUrl, position, segmentEnd, output)) return
        }
    }

    /**
     * Opens upstream `Range: bytes=<start>-<segmentEnd>` and validates it. Returns false when
     * the connection must close.
     */
    private fun fetchSegment(upstreamUrl: String, start: Long, segmentEnd: Long, output: OutputStream): Boolean {
        val response = openUpstream(upstreamUrl, "bytes=$start-$segmentEnd", "GET") ?: return false
        val status = response.status
        var acceptable = false
        val contentRange = response.contentRange
        if (status == 206 && contentRange != null) {
            val upstreamTotal = contentRange.total
            if (upstreamTotal != null && upstreamTotal != totalBytes) {
                logWarn("upstream size changed ($totalBytes -> $upstreamTotal); restarting entry")
                session?.entryId?.let { store.setTotal(it, upstreamTotal, null) }
                releaseUpstream(response, graceful = false)
                return false
            }
            acceptable = contentRange.start == start
        } else if (status == 200) {
            acceptable = start == 0L && response.contentLength == totalBytes
        }
        if (!acceptable) {
            logWarn("unexpected upstream response HTTP $status for segment at $start")
            releaseUpstream(response, graceful = false)
            return false
        }
        return copySegment(response, start, segmentEnd, output)
    }

    /**
     * Copies an accepted segment body (also used for a reused probe) to disk + client.
     * Returns true to continue with [pump], false to close. Always releases [response].
     */
    private fun copySegment(
        response: StreamCacheUpstreamResponse,
        segmentStart: Long,
        segmentEnd: Long,
        output: OutputStream,
    ): Boolean {
        var graceful = false
        try {
            val id = session?.entryId ?: return false
            val buf = buffer
            val body = response.body
            while (true) {
                if (closed) return false
                val remaining = segmentEnd - position + 1
                if (remaining <= 0) {
                    graceful = response.contentRange?.endInclusive == segmentEnd
                    return true
                }
                val want = minOf(COPY_BUFFER.toLong(), remaining).toInt()
                val n = try {
                    body.read(buf, 0, want)
                } catch (error: IOException) {
                    if (closed) return false
                    return if (position > segmentStart) {
                        // Upstream ended early (server-capped range, idle timeout while the player
                        // wasn't reading, ...) but made progress: continue with a fresh request.
                        logDebug("upstream segment interrupted at $position: ${error.message}; resuming")
                        true
                    } else {
                        logWarn("upstream segment failed: ${error.message}")
                        false
                    }
                }
                if (n < 0) {
                    // EOF before segmentEnd: resume with a fresh request if progress was made.
                    if (position <= segmentStart) logWarn("upstream segment at $segmentStart ended without data")
                    return position > segmentStart
                }
                if (n == 0) continue
                if (cacheWrites) store.write(id, generation, position, buf, 0, n)
                writeClient(output, buf, 0, n)
                position += n
                store.notePlayhead(id, position)
                if (position > segmentEnd) {
                    // Reached the next cached span (or the requested end): continue from disk.
                    graceful = response.contentRange?.endInclusive == segmentEnd
                    return true
                }
                if (cacheWrites && store.cachedEnd(id, position) != null) {
                    // The filler cached the bytes ahead meanwhile: serve them from disk instead of
                    // downloading them again (pump starts a new segment at the next gap).
                    return true
                }
            }
        } finally {
            releaseUpstream(response, graceful)
        }
    }

    // endregion

    // region Pass-through

    private fun servePassthrough(url: String, output: OutputStream, existing: StreamCacheUpstreamResponse? = null) {
        val response = existing ?: openUpstream(url, rawRangeHeader, if (isHead) "HEAD" else "GET")
        if (response == null) {
            sendSimple(502, output)
            return
        }
        var graceful = false
        try {
            val headers = ArrayList<Pair<String, String>>(4)
            response.contentType?.let { headers.add("Content-Type" to it) }
            if (response.contentLength >= 0 && !response.isEncoded) {
                headers.add("Content-Length" to response.contentLength.toString())
            }
            response.rawContentRange?.let { headers.add("Content-Range" to it) }
            response.acceptRanges?.let { headers.add("Accept-Ranges" to it) }
            sendHead(response.status, headers, output)
            if (isHead || closed) return
            val buf = buffer
            val body = response.body
            while (!closed) {
                val n = try {
                    body.read(buf, 0, COPY_BUFFER)
                } catch (error: IOException) {
                    if (!closed) logDebug("pass-through interrupted: ${error.message}")
                    return
                }
                if (n < 0) {
                    graceful = true
                    return
                }
                if (n > 0) writeClient(output, buf, 0, n)
            }
        } finally {
            releaseUpstream(response, graceful)
        }
    }

    // endregion

    // region Sending

    /** Writes the response head once; client failure unwinds via [ClientGoneException]. */
    private fun sendHead(status: Int, headers: List<Pair<String, String>>, output: OutputStream) {
        if (headersSent || closed) return
        headersSent = true
        try {
            StreamCacheHttp.writeResponseHead(output, status, headers)
        } catch (error: IOException) {
            closed = true
            throw ClientGoneException(error)
        }
    }

    /** Body-less error; if a head was already sent, just close. */
    private fun sendSimple(status: Int, output: OutputStream) {
        if (headersSent || closed) return // run() closes the connection
        headersSent = true
        try {
            StreamCacheHttp.writeSimpleResponse(output, status)
        } catch (error: IOException) {
            closed = true
            throw ClientGoneException(error)
        }
    }

    private fun writeClient(output: OutputStream, data: ByteArray, offset: Int, length: Int) {
        if (closed) throw ClientGoneException(IOException("connection closed"))
        try {
            output.write(data, offset, length)
        } catch (error: IOException) {
            closed = true
            throw ClientGoneException(error)
        }
    }

    // endregion

    // region Upstream

    /** Opens an upstream request with the session headers; null on failure or when closed. */
    private fun openUpstream(url: String, range: String?, method: String): StreamCacheUpstreamResponse? {
        val session = session ?: return null
        if (closed) return null
        val response = try {
            upstream.open(url, session.headers, range, method)
        } catch (error: IOException) {
            if (!closed) logWarn("upstream request failed: ${error.message}")
            return null
        } catch (error: RuntimeException) {
            logWarn("upstream request failed", error)
            return null
        }
        currentUpstream = response
        if (closed) {
            // Released while connecting: cancelFromOutside may have missed this response.
            releaseUpstream(response, graceful = false)
            return null
        }
        return response
    }

    /**
     * Closes [response]. Not [graceful] (body not fully consumed) → cancel first so OkHttp does
     * not try to drain the remaining body; graceful → plain close keeps the connection reusable.
     */
    private fun releaseUpstream(response: StreamCacheUpstreamResponse, graceful: Boolean) {
        if (currentUpstream === response) currentUpstream = null
        if (!graceful) runCatching { response.cancel() }
        runCatching { response.close() }
    }

    // endregion

    companion object {
        /** Disk read chunk while serving cached spans. */
        const val READ_CHUNK: Int = 512 * 1024

        /** Upstream → client copy buffer. */
        const val COPY_BUFFER: Int = 64 * 1024

        /** Timeout for reading the request head only (body streaming has no client timeout). */
        const val CLIENT_READ_TIMEOUT_MS: Int = 15_000

        private const val TAG = "StreamCache"

        /**
         * Swift `removingPercentEncoding`: decodes `%XX` sequences as UTF-8 ('+' stays '+').
         * Null on a malformed escape or invalid UTF-8.
         */
        internal fun percentDecode(value: String): String? {
            if (value.indexOf('%') < 0) return value
            val bytes = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val hi = Character.digit(value[i + 1], 16)
                    val lo = Character.digit(value[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                } else {
                    val codePoint = value.codePointAt(i)
                    val chars = Character.charCount(codePoint)
                    val encoded = value.substring(i, i + chars).toByteArray(Charsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    i += chars
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Resolves a relative reference (raw, still percent-encoded, with query) against the
         * upstream URL, like Swift `URL(string:relativeTo:)`. `java.net.URL(context, spec)` is
         * used rather than `URI.resolve` because it tolerates characters URI rejects and handles
         * an empty base path and `./` / `../` segments. Null when unresolvable.
         */
        internal fun resolveRelative(base: String, reference: String): String? =
            try {
                URL(URL(base), reference).toString()
            } catch (_: Exception) {
                null
            }

        private fun logDebug(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logWarn(message: String, error: Throwable? = null) {
            runCatching { if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message) }
        }
    }
}
