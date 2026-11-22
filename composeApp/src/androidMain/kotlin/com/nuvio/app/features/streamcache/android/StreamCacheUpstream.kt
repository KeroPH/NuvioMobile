package com.nuvio.app.features.streamcache.android

import com.nuvio.app.core.network.IPv4FirstDns
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// Port of StreamCacheUpstream.swift + StreamCacheHTTP response-side helpers. Swift is
// delegate/callback based; Android uses blocking OkHttp calls on the caller's thread
// (proxy connection thread or fill thread), so backpressure is natural.
// Owner: StreamCacheUpstream.kt.

/** Parsed `Content-Range: bytes s-e/total` (total null when `*`). */
internal data class StreamCacheContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long?,
)

/** Thrown by [StreamCacheUpstream.open] / body reads. [isOffline] = no network at all. */
internal class StreamCacheUpstreamException(
    message: String,
    val isOffline: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * One open upstream response (headers received, body not yet consumed). Reading [body] blocks;
 * [cancel] may be called from ANY thread to abort a blocked read (Call.cancel()), which makes
 * the reader throw IOException. [close] releases the connection; both are idempotent.
 */
internal class StreamCacheUpstreamResponse(
    val status: Int,
    /** Raw Content-Type header. */
    val contentType: String?,
    /** Content-Length, -1 when unknown. */
    val contentLength: Long,
    /** Raw Content-Range header (forwarded verbatim in pass-through). */
    val rawContentRange: String?,
    /** Parsed [rawContentRange]. */
    val contentRange: StreamCacheContentRange?,
    val contentEncoding: String?,
    val acceptRanges: String?,
    val body: InputStream,
    private val call: Call,
    private val response: Response,
) : Closeable {
    /** Content-Encoding present and not "identity" (bytes would not match the file). */
    val isEncoded: Boolean
        get() = StreamCacheUpstream.isEncoded(contentEncoding)

    /** HLS / DASH manifest by content type (never cached). */
    val isManifest: Boolean
        get() = StreamCacheUpstream.isManifest(contentType)

    private val closed = AtomicBoolean(false)

    /** Aborts the call from any thread. */
    fun cancel() {
        try {
            call.cancel()
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            response.close()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Wraps the OkHttp body stream so transport failures surface as [StreamCacheUpstreamException]
 * (with [StreamCacheUpstreamException.isOffline] set when the network dropped mid-body).
 */
private class StreamCacheUpstreamBodyStream(source: InputStream) : FilterInputStream(source) {
    override fun read(): Int = guard { `in`.read() }

    override fun read(b: ByteArray): Int = guard { `in`.read(b, 0, b.size) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = guard { `in`.read(b, off, len) }

    override fun skip(n: Long): Long = guard { `in`.skip(n) }

    override fun available(): Int = guard { `in`.available() }

    private inline fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: StreamCacheUpstreamException) {
            throw e
        } catch (e: IOException) {
            throw StreamCacheUpstreamException(
                e.message ?: e.javaClass.simpleName,
                isOffline = StreamCacheUpstream.isOfflineError(e),
                cause = e,
            )
        }
    }
}

/**
 * Shared streaming HTTP client for the proxy and the filler (one OkHttpClient, no cache,
 * connect/read timeout 30 s, followRedirects = true; OkHttp keeps Range and custom headers on
 * redirects — it only drops Authorization on cross-host redirects, same as the player would).
 * Thread-safe; [open] blocks the calling thread until response headers arrive.
 */
internal class StreamCacheUpstream(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * Executes a request synchronously. Forwards [headers] except [EXCLUDED_HEADERS]
     * (case-insensitive), always sets `Accept-Encoding: identity` (disables OkHttp transparent
     * gzip so byte offsets match the stored file) and `Range: <range>` when [range] != null.
     * [method] is "GET" or "HEAD". Returns any status (caller decides); throws
     * [StreamCacheUpstreamException] on transport failure (isOffline per [isOfflineError]) or on
     * an invalid URL.
     */
    @Throws(StreamCacheUpstreamException::class)
    fun open(
        url: String,
        headers: Map<String, String>,
        range: String?,
        method: String = "GET",
    ): StreamCacheUpstreamResponse {
        val httpUrl = url.trim().toHttpUrlOrNull()
            ?: throw StreamCacheUpstreamException("Invalid upstream URL")
        val builder = Request.Builder().url(httpUrl)
        for ((name, value) in headers) {
            if (name.isBlank() || name.lowercase() in EXCLUDED_HEADERS) continue
            try {
                builder.header(name, value)
            } catch (_: IllegalArgumentException) {
                // Header name/value OkHttp refuses (e.g. non-ASCII); skip rather than fail playback.
            }
        }
        // Byte offsets must match the stored file: never let the transport decode content.
        // An explicit Accept-Encoding also disables OkHttp's transparent gzip.
        builder.header("Accept-Encoding", "identity")
        if (range != null) builder.header("Range", range)
        val upperMethod = method.uppercase()
        if (upperMethod == "HEAD") builder.head() else builder.get()

        val call = client.newCall(builder.build())
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw StreamCacheUpstreamException(
                e.message ?: e.javaClass.simpleName,
                isOffline = isOfflineError(e),
                cause = e,
            )
        } catch (e: RuntimeException) {
            throw StreamCacheUpstreamException(e.message ?: e.javaClass.simpleName, cause = e)
        }

        val responseBody = response.body
        val rawBody: InputStream = responseBody?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        val contentLength = response.header("Content-Length")?.trim()?.toLongOrNull()
            ?: responseBody?.contentLength()?.takeIf { it >= 0 }
            ?: -1L
        val rawContentRange = response.header("Content-Range")
        return StreamCacheUpstreamResponse(
            status = response.code,
            contentType = response.header("Content-Type"),
            contentLength = contentLength,
            rawContentRange = rawContentRange,
            contentRange = parseContentRange(rawContentRange),
            contentEncoding = response.header("Content-Encoding"),
            acceptRanges = response.header("Accept-Ranges"),
            body = StreamCacheUpstreamBodyStream(rawBody),
            call = call,
            response = response,
        )
    }

    companion object {
        /** Headers never forwarded from the player's header map (hop-by-hop / set by us). */
        val EXCLUDED_HEADERS: Set<String> = setOf(
            "range", "host", "content-length", "connection", "accept-encoding",
            "transfer-encoding", "if-range", "keep-alive", "upgrade",
        )

        const val CONNECT_TIMEOUT_MS: Long = 30_000L
        const val READ_TIMEOUT_MS: Long = 30_000L

        /**
         * No OkHttp cache, retryOnConnectionFailure = true, Dispatcher/ConnectionPool sized for
         * ≥ 16 concurrent ranged requests per host, IPv4-first DNS
         * (`com.nuvio.app.core.network.IPv4FirstDns`) like the rest of the app.
         */
        fun defaultClient(): OkHttpClient = sharedClient

        private val sharedClient: OkHttpClient by lazy {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            }
            OkHttpClient.Builder()
                .cache(null)
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .dns(IPv4FirstDns())
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                // Idle timeout between bytes (like URLSession timeoutIntervalForRequest); a whole
                // response may stream for hours, so no call timeout.
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        /** Port of Swift `parseContentRange`: `bytes s-e/total` (also tolerates `bytes=`), total null for `*`. */
        fun parseContentRange(value: String?): StreamCacheContentRange? {
            val raw = value?.trim()?.lowercase() ?: return null
            if (!raw.startsWith("bytes")) return null
            val text = raw.substring(5).trim(' ', '=')
            val halves = text.split("/", limit = 2)
            if (halves.size != 2) return null
            val bounds = halves[0].split("-", limit = 2)
            if (bounds.size != 2) return null
            val start = bounds[0].trim().toLongOrNull() ?: return null
            val end = bounds[1].trim().toLongOrNull() ?: return null
            val total = halves[1].trim().toLongOrNull()
            return StreamCacheContentRange(start = start, endInclusive = end, total = total)
        }

        /** Content type contains "mpegurl" or "dash+xml". */
        fun isManifest(contentType: String?): Boolean {
            val type = contentType?.lowercase() ?: return false
            return type.contains("mpegurl") || type.contains("dash+xml")
        }

        /** Content-Encoding present and not "identity". */
        fun isEncoded(contentEncoding: String?): Boolean {
            val encoding = contentEncoding?.trim()?.lowercase() ?: return false
            return encoding.isNotEmpty() && encoding != "identity"
        }

        /**
         * True when [error] (or a cause) means "no network": UnknownHostException,
         * ConnectException with ENETUNREACH, SocketException "Network is unreachable",
         * NoRouteToHostException. (Android equivalent of URLError.notConnectedToInternet.)
         */
        fun isOfflineError(error: Throwable): Boolean {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                when (current) {
                    is StreamCacheUpstreamException -> if (current.isOffline) return true
                    is UnknownHostException -> return true
                    is NoRouteToHostException -> return true
                    is ConnectException, is SocketException -> if (isUnreachableMessage(current.message)) return true
                }
                // android.system.ErrnoException (cause of ConnectException) is not an IOException.
                if (isUnreachableMessage(current.message) &&
                    current.javaClass.name == "android.system.ErrnoException"
                ) {
                    return true
                }
                val next = current.cause
                if (next === current) break
                current = next
                depth++
            }
            return false
        }

        private fun isUnreachableMessage(message: String?): Boolean {
            val text = message?.lowercase() ?: return false
            return text.contains("enetunreach") || text.contains("network is unreachable")
        }
    }
}
