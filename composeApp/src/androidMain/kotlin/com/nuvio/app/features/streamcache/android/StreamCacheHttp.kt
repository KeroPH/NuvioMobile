package com.nuvio.app.features.streamcache.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Client-side (player → proxy) HTTP/1.1 helpers. Port of the request parsing / range helpers in
// StreamCacheProxyConnection.swift and the response helpers of StreamCacheHTTP (Upstream.swift).
// Owner: StreamCacheHttp.kt. Pure functions, no state; unit-testable on the JVM.

/** One parsed request head from the player (ExoPlayer / libmpv / ffmpeg). */
internal data class StreamCacheHttpRequest(
    /** Upper-cased method ("GET", "HEAD", ...). */
    val method: String,
    /** Raw request target as sent, e.g. `/s/<token>/<file>?q=1`. */
    val target: String,
    /** [target] without the query. */
    val path: String,
    /** Query INCLUDING the leading '?', or "" when absent. */
    val query: String,
    /** Header names lower-cased; values trimmed; last occurrence wins. */
    val headers: Map<String, String>,
) {
    val rangeHeader: String? get() = headers["range"]
}

/** Parsed client `Range` header (first spec only; multi-range is treated as its first spec). */
internal sealed interface StreamCacheRangeRequest {
    /** No / unparseable / non-bytes Range → full 200 response. */
    data object Whole : StreamCacheRangeRequest

    /** `bytes=a-b` (end inclusive) or `bytes=a-` (end null). */
    data class From(val start: Long, val endInclusive: Long?) : StreamCacheRangeRequest

    /** `bytes=-n` (last n bytes). */
    data class Suffix(val count: Long) : StreamCacheRangeRequest
}

/** Inclusive byte range to serve. */
internal data class StreamCacheByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

internal object StreamCacheHttp {
    const val MAX_REQUEST_HEADER_BYTES: Int = 64 * 1024

    /**
     * Blocking read of one request head from [input] up to and excluding CRLFCRLF (decoded as
     * UTF-8, falling back to ISO-8859-1). Returns null on EOF before a complete head, when the
     * head exceeds [MAX_REQUEST_HEADER_BYTES], or when [parseRequestHead] fails. Must not read
     * past the head (read byte-wise or via a small pushback buffer: requests have no body).
     */
    fun readRequestHead(input: InputStream): StreamCacheHttpRequest? {
        val buffer = ByteArrayOutputStream(1024)
        // Rolling window of the last four bytes, to detect CRLFCRLF without re-scanning.
        var window = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            window = (window shl 8) or (b and 0xFF)
            if (window == HEAD_TERMINATOR && buffer.size() >= 4) {
                val bytes = buffer.toByteArray()
                val head = bytes.copyOfRange(0, bytes.size - 4)
                return parseRequestHead(decodeHead(head))
            }
            if (buffer.size() > MAX_REQUEST_HEADER_BYTES) return null
        }
    }

    /**
     * Parses `METHOD SP target SP version CRLF (name: value CRLF)*` (without the final CRLFCRLF).
     * Null when the request line has fewer than 2 parts. Lines without ':' are ignored.
     */
    fun parseRequestHead(text: String): StreamCacheHttpRequest? {
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val name = trimHorizontal(line.substring(0, colon)).lowercase(Locale.ROOT)
            val value = trimHorizontal(line.substring(colon + 1))
            headers[name] = value
        }

        val queryIndex = target.indexOf('?')
        val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = if (queryIndex >= 0) target.substring(queryIndex) else ""
        return StreamCacheHttpRequest(
            method = method,
            target = target,
            path = path,
            query = query,
            headers = headers,
        )
    }

    /**
     * Port of Swift `parseRange`: case-insensitive `bytes=` prefix required; first spec only;
     * `-n` → Suffix(n) (non-numeric → Whole); `a-` → From(a, null); `a-b` with b >= a →
     * From(a, b); negative start, b < a or junk → Whole.
     */
    fun parseRange(header: String?): StreamCacheRangeRequest {
        if (header == null) return StreamCacheRangeRequest.Whole
        val normalized = trimHorizontal(header).lowercase(Locale.ROOT)
        if (!normalized.startsWith("bytes=")) return StreamCacheRangeRequest.Whole
        // Swift `split(separator:)` omits empty subsequences.
        val firstSpec = normalized.substring(6).split(',').firstOrNull { it.isNotEmpty() }
            ?: return StreamCacheRangeRequest.Whole
        val spec = trimHorizontal(firstSpec)
        if (spec.startsWith("-")) {
            val count = spec.substring(1).toLongOrNull() ?: return StreamCacheRangeRequest.Whole
            return StreamCacheRangeRequest.Suffix(count)
        }
        val parts = spec.split("-", limit = 2)
        if (parts.size != 2) return StreamCacheRangeRequest.Whole
        val start = trimHorizontal(parts[0]).toLongOrNull() ?: return StreamCacheRangeRequest.Whole
        if (start < 0) return StreamCacheRangeRequest.Whole
        val endText = trimHorizontal(parts[1])
        if (endText.isEmpty()) return StreamCacheRangeRequest.From(start, null)
        val end = endText.toLongOrNull() ?: return StreamCacheRangeRequest.Whole
        if (end < start) return StreamCacheRangeRequest.Whole
        return StreamCacheRangeRequest.From(start, end)
    }

    /**
     * Port of Swift `resolve`: inclusive range to serve against [total], or null → 416.
     * Whole → [0, total-1]; From(a, b) → null if a >= total, else [a, min(b ?: total-1, total-1)];
     * Suffix(n) → null if n <= 0, else [max(0, total-n), total-1]; total <= 0 → null.
     */
    fun resolveRange(request: StreamCacheRangeRequest, total: Long): StreamCacheByteRange? {
        if (total <= 0) return null
        return when (request) {
            StreamCacheRangeRequest.Whole -> StreamCacheByteRange(0, total - 1)
            is StreamCacheRangeRequest.From -> {
                if (request.start >= total) {
                    null
                } else {
                    StreamCacheByteRange(
                        request.start,
                        minOf(request.endInclusive ?: (total - 1), total - 1),
                    )
                }
            }
            is StreamCacheRangeRequest.Suffix -> {
                if (request.count <= 0) {
                    null
                } else {
                    StreamCacheByteRange(maxOf(0L, total - request.count), total - 1)
                }
            }
        }
    }

    /** Reason phrases for 200, 206, 301, 302, 304, 400, 401, 403, 404, 405, 410, 416, 429, 500, 502, 503; else "Status". */
    fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    /** mkv→video/x-matroska, mp4/m4v→video/mp4, webm, mov→video/quicktime, ts/m2ts/mts→video/mp2t, avi→video/x-msvideo, else application/octet-stream. */
    fun mimeTypeForFileName(fileName: String): String {
        // NSString.pathExtension: text after the last '.' of the last path component.
        val lastComponent = fileName.trimEnd('/').substringAfterLast('/')
        val dot = lastComponent.lastIndexOf('.')
        val extension = if (dot >= 0) lastComponent.substring(dot + 1).lowercase(Locale.ROOT) else ""
        return when (extension) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            else -> "application/octet-stream"
        }
    }

    /**
     * Writes `HTTP/1.1 <status> <reason>\r\n`, each header, then `Connection: close\r\n\r\n`
     * (ISO-8859-1 / ASCII) and flushes. Every proxy response is `Connection: close`.
     */
    fun writeResponseHead(output: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val text = StringBuilder(256)
        text.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        for ((name, value) in headers) {
            text.append(name).append(": ").append(value).append("\r\n")
        }
        text.append("Connection: close\r\n\r\n")
        output.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Body-less response: [writeResponseHead] with `Content-Length: 0`. */
    fun writeSimpleResponse(output: OutputStream, status: Int) {
        writeResponseHead(output, status, listOf("Content-Length" to "0"))
    }

    /**
     * Headers for a main-file response: Content-Type, Content-Length (range length),
     * Accept-Ranges: bytes, plus `Content-Range: bytes s-e/total` when [request] is not Whole.
     * Status is 200 for Whole, else 206 (see [rangedStatus]).
     */
    fun rangedResponseHeaders(
        request: StreamCacheRangeRequest,
        range: StreamCacheByteRange,
        total: Long,
        mimeType: String,
    ): List<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>(4)
        headers.add("Content-Type" to mimeType)
        headers.add("Content-Length" to range.length.toString())
        headers.add("Accept-Ranges" to "bytes")
        if (request !is StreamCacheRangeRequest.Whole) {
            headers.add("Content-Range" to "bytes ${range.start}-${range.endInclusive}/$total")
        }
        return headers
    }

    /** 200 for [StreamCacheRangeRequest.Whole], 206 otherwise. */
    fun rangedStatus(request: StreamCacheRangeRequest): Int =
        if (request is StreamCacheRangeRequest.Whole) 200 else 206

    /** 416 headers: `Content-Range: bytes * /total` (no space) and `Content-Length: 0`. */
    fun unsatisfiableHeaders(total: Long): List<Pair<String, String>> = listOf(
        "Content-Range" to "bytes */$total",
        "Content-Length" to "0",
    )

    /** "\r\n\r\n" as a big-endian Int, compared against the rolling four-byte window. */
    private const val HEAD_TERMINATOR: Int = 0x0D0A0D0A

    /** Strict UTF-8 decode (Swift `String(data:encoding:.utf8)` fails on bad bytes), else Latin-1. */
    private fun decodeHead(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /** Swift `trimmingCharacters(in: .whitespaces)`: spaces and tabs only, not newlines. */
    private fun trimHorizontal(text: String): String = text.trim { it == ' ' || it == '\t' }
}
