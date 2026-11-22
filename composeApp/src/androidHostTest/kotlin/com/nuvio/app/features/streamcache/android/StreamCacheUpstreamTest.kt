package com.nuvio.app.features.streamcache.android

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamCacheUpstreamTest {
    @Test
    fun parsesStandardContentRange() {
        assertEquals(
            StreamCacheContentRange(0, 1023, 4096),
            StreamCacheUpstream.parseContentRange("bytes 0-1023/4096"),
        )
    }

    @Test
    fun parsesLargeOffsets() {
        assertEquals(
            StreamCacheContentRange(5_000_000_000L, 5_999_999_999L, 12_884_901_888L),
            StreamCacheUpstream.parseContentRange("bytes 5000000000-5999999999/12884901888"),
        )
    }

    @Test
    fun unknownTotalIsNull() {
        assertEquals(
            StreamCacheContentRange(100, 199, null),
            StreamCacheUpstream.parseContentRange("bytes 100-199/*"),
        )
    }

    @Test
    fun toleratesEqualsCaseAndWhitespace() {
        assertEquals(
            StreamCacheContentRange(10, 20, 30),
            StreamCacheUpstream.parseContentRange("  Bytes=10-20/30 "),
        )
        assertEquals(
            StreamCacheContentRange(10, 20, 30),
            StreamCacheUpstream.parseContentRange("BYTES 10 - 20 / 30"),
        )
    }

    @Test
    fun rejectsMalformedValues() {
        assertNull(StreamCacheUpstream.parseContentRange(null))
        assertNull(StreamCacheUpstream.parseContentRange(""))
        assertNull(StreamCacheUpstream.parseContentRange("items 0-1/2"))
        assertNull(StreamCacheUpstream.parseContentRange("bytes 0-1"))
        assertNull(StreamCacheUpstream.parseContentRange("bytes */4096"))
        assertNull(StreamCacheUpstream.parseContentRange("bytes a-b/10"))
        assertNull(StreamCacheUpstream.parseContentRange("bytes 5/10"))
        assertNull(StreamCacheUpstream.parseContentRange("bytes -5/10"))
    }

    @Test
    fun detectsManifests() {
        assertTrue(StreamCacheUpstream.isManifest("application/vnd.apple.mpegurl"))
        assertTrue(StreamCacheUpstream.isManifest("Application/X-MPEGURL; charset=utf-8"))
        assertTrue(StreamCacheUpstream.isManifest("application/dash+xml"))
        assertFalse(StreamCacheUpstream.isManifest("video/mp4"))
        assertFalse(StreamCacheUpstream.isManifest(null))
    }

    @Test
    fun detectsEncodedBodies() {
        assertTrue(StreamCacheUpstream.isEncoded("gzip"))
        assertTrue(StreamCacheUpstream.isEncoded("br"))
        assertFalse(StreamCacheUpstream.isEncoded("identity"))
        assertFalse(StreamCacheUpstream.isEncoded(" Identity "))
        assertFalse(StreamCacheUpstream.isEncoded(""))
        assertFalse(StreamCacheUpstream.isEncoded(null))
    }

    @Test
    fun classifiesOfflineErrors() {
        assertTrue(StreamCacheUpstream.isOfflineError(UnknownHostException("example.com")))
        assertTrue(StreamCacheUpstream.isOfflineError(NoRouteToHostException("no route")))
        assertTrue(
            StreamCacheUpstream.isOfflineError(
                ConnectException("failed to connect: connect failed: ENETUNREACH (Network is unreachable)"),
            ),
        )
        assertTrue(StreamCacheUpstream.isOfflineError(IOException("wrapped", UnknownHostException("x"))))
        assertFalse(StreamCacheUpstream.isOfflineError(SocketTimeoutException("timeout")))
        assertFalse(StreamCacheUpstream.isOfflineError(ConnectException("Connection refused")))
        assertFalse(StreamCacheUpstream.isOfflineError(IOException("Canceled")))
    }
}
