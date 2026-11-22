package com.nuvio.app.features.streamcache.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StreamCacheHttpTest {
    private val whole = StreamCacheRangeRequest.Whole

    @Test
    fun parseRangeAcceptsStandardForms() {
        assertEquals(StreamCacheRangeRequest.From(0, null), StreamCacheHttp.parseRange("bytes=0-"))
        assertEquals(StreamCacheRangeRequest.From(100, 199), StreamCacheHttp.parseRange("bytes=100-199"))
        assertEquals(StreamCacheRangeRequest.From(5, 5), StreamCacheHttp.parseRange("bytes=5-5"))
        assertEquals(StreamCacheRangeRequest.Suffix(500), StreamCacheHttp.parseRange("bytes=-500"))
    }

    @Test
    fun parseRangeIsCaseAndWhitespaceTolerant() {
        assertEquals(StreamCacheRangeRequest.From(10, 20), StreamCacheHttp.parseRange("  BYTES=10-20 "))
        assertEquals(StreamCacheRangeRequest.From(10, 20), StreamCacheHttp.parseRange("bytes= 10 - 20"))
        assertEquals(StreamCacheRangeRequest.From(7, null), StreamCacheHttp.parseRange("Bytes=7- "))
    }

    @Test
    fun parseRangeUsesFirstSpecOfMultiRange() {
        assertEquals(StreamCacheRangeRequest.From(0, 99), StreamCacheHttp.parseRange("bytes=0-99,200-299"))
        assertEquals(StreamCacheRangeRequest.From(200, 299), StreamCacheHttp.parseRange("bytes=,200-299"))
        assertEquals(StreamCacheRangeRequest.Suffix(10), StreamCacheHttp.parseRange("bytes=-10, 0-5"))
    }

    @Test
    fun parseRangeFallsBackToWholeForJunk() {
        assertEquals(whole, StreamCacheHttp.parseRange(null))
        assertEquals(whole, StreamCacheHttp.parseRange(""))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes="))
        assertEquals(whole, StreamCacheHttp.parseRange("items=0-10"))
        assertEquals(whole, StreamCacheHttp.parseRange("0-10"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=abc-10"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=10-abc"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=20-10"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=10"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=-"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=-abc"))
        assertEquals(whole, StreamCacheHttp.parseRange("bytes=5-10-15"))
    }

    @Test
    fun resolveRangeAgainstTotal() {
        assertEquals(StreamCacheByteRange(0, 999), StreamCacheHttp.resolveRange(whole, 1000))
        assertEquals(StreamCacheByteRange(100, 999), StreamCacheHttp.resolveRange(StreamCacheRangeRequest.From(100, null), 1000))
        assertEquals(StreamCacheByteRange(100, 199), StreamCacheHttp.resolveRange(StreamCacheRangeRequest.From(100, 199), 1000))
        // End past total is clipped.
        assertEquals(StreamCacheByteRange(900, 999), StreamCacheHttp.resolveRange(StreamCacheRangeRequest.From(900, 5000), 1000))
        // Start at/after total → 416.
        assertNull(StreamCacheHttp.resolveRange(StreamCacheRangeRequest.From(1000, null), 1000))
        // Suffix.
        assertEquals(StreamCacheByteRange(900, 999), StreamCacheHttp.resolveRange(StreamCacheRangeRequest.Suffix(100), 1000))
        assertEquals(StreamCacheByteRange(0, 999), StreamCacheHttp.resolveRange(StreamCacheRangeRequest.Suffix(5000), 1000))
        assertNull(StreamCacheHttp.resolveRange(StreamCacheRangeRequest.Suffix(0), 1000))
        // Unknown / empty total.
        assertNull(StreamCacheHttp.resolveRange(whole, 0))
        assertEquals(100L, StreamCacheByteRange(100, 199).length)
    }

    @Test
    fun parseRequestHeadSplitsTargetAndHeaders() {
        val request = StreamCacheHttp.parseRequestHead(
            "get /s/tok/movie.mkv?x=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nRANGE :  bytes=0- \r\nbogus line\r\nRange: bytes=5-",
        )
        assertNotNull(request)
        assertEquals("GET", request.method)
        assertEquals("/s/tok/movie.mkv?x=1", request.target)
        assertEquals("/s/tok/movie.mkv", request.path)
        assertEquals("?x=1", request.query)
        assertEquals("127.0.0.1", request.headers["host"])
        // Last occurrence wins.
        assertEquals("bytes=5-", request.rangeHeader)

        val noQuery = StreamCacheHttp.parseRequestHead("HEAD /s/tok/a.mp4 HTTP/1.1")
        assertNotNull(noQuery)
        assertEquals("", noQuery.query)
        assertEquals("/s/tok/a.mp4", noQuery.path)

        assertNull(StreamCacheHttp.parseRequestHead("GET"))
        assertNull(StreamCacheHttp.parseRequestHead(""))
    }

    @Test
    fun readRequestHeadStopsAtTerminator() {
        val bytes = "GET /s/t/f.mkv HTTP/1.1\r\nRange: bytes=0-\r\n\r\nEXTRA".toByteArray(Charsets.ISO_8859_1)
        val input = ByteArrayInputStream(bytes)
        val request = StreamCacheHttp.readRequestHead(input)
        assertNotNull(request)
        assertEquals("bytes=0-", request.rangeHeader)
        // Nothing past the head was consumed.
        assertEquals("EXTRA", input.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun readRequestHeadReturnsNullOnEofOrOversize() {
        assertNull(StreamCacheHttp.readRequestHead(ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n".toByteArray())))
        val huge = "GET / HTTP/1.1\r\nX: " + "a".repeat(StreamCacheHttp.MAX_REQUEST_HEADER_BYTES + 10) + "\r\n\r\n"
        assertNull(StreamCacheHttp.readRequestHead(ByteArrayInputStream(huge.toByteArray())))
    }

    @Test
    fun readRequestHeadFallsBackToLatin1() {
        val head = byteArrayOf(*"GET /s/t/caf".toByteArray(), 0xE9.toByte(), *" HTTP/1.1\r\n\r\n".toByteArray())
        val request = StreamCacheHttp.readRequestHead(ByteArrayInputStream(head))
        assertNotNull(request)
        assertEquals("/s/t/caf\u00e9", request.path)
    }

    @Test
    fun responseHelpers() {
        val out = ByteArrayOutputStream()
        StreamCacheHttp.writeSimpleResponse(out, 416)
        assertEquals(
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            out.toString(Charsets.ISO_8859_1.name()),
        )

        val range = StreamCacheByteRange(10, 19)
        assertEquals(
            listOf("Content-Type" to "video/mp4", "Content-Length" to "10", "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes 10-19/100"),
            StreamCacheHttp.rangedResponseHeaders(StreamCacheRangeRequest.From(10, 19), range, 100, "video/mp4"),
        )
        assertEquals(3, StreamCacheHttp.rangedResponseHeaders(whole, StreamCacheByteRange(0, 99), 100, "video/mp4").size)
        assertEquals(200, StreamCacheHttp.rangedStatus(whole))
        assertEquals(206, StreamCacheHttp.rangedStatus(StreamCacheRangeRequest.Suffix(5)))
        assertEquals(listOf("Content-Range" to "bytes */100", "Content-Length" to "0"), StreamCacheHttp.unsatisfiableHeaders(100))
        assertEquals("Status", StreamCacheHttp.reasonPhrase(418))

        assertEquals("video/x-matroska", StreamCacheHttp.mimeTypeForFileName("Movie.2024.MKV"))
        assertEquals("video/mp4", StreamCacheHttp.mimeTypeForFileName("a.m4v"))
        assertEquals("video/mp2t", StreamCacheHttp.mimeTypeForFileName("seg.m2ts"))
        assertEquals("application/octet-stream", StreamCacheHttp.mimeTypeForFileName("noext"))
        assertEquals("application/octet-stream", StreamCacheHttp.mimeTypeForFileName("dir.mkv/file"))
    }
}
