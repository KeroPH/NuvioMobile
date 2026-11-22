package com.nuvio.app.features.streamcache.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamCacheRangeSetTest {
    private fun set(vararg spans: Pair<Long, Long>): StreamCacheRangeSet =
        StreamCacheRangeSet().apply { spans.forEach { insert(it.first, it.second) } }

    @Test
    fun insertKeepsDisjointSpansSorted() {
        val ranges = set(50L to 60L, 0L to 10L, 20L to 30L)
        assertEquals(
            listOf(StreamCacheSpan(0, 10), StreamCacheSpan(20, 30), StreamCacheSpan(50, 60)),
            ranges.spans,
        )
        assertEquals(30L, ranges.totalLength)
    }

    @Test
    fun insertMergesOverlappingAndAdjacentSpans() {
        val adjacent = set(0L to 10L, 10L to 20L)
        assertEquals(listOf(StreamCacheSpan(0, 20)), adjacent.spans)

        val bridging = set(0L to 10L, 20L to 30L, 40L to 50L)
        bridging.insert(5, 45)
        assertEquals(listOf(StreamCacheSpan(0, 50)), bridging.spans)

        val inside = set(0L to 100L)
        inside.insert(10, 20)
        assertEquals(listOf(StreamCacheSpan(0, 100)), inside.spans)
    }

    @Test
    fun insertIgnoresInvalidSpans() {
        val ranges = StreamCacheRangeSet()
        ranges.insert(-1, 10)
        ranges.insert(10, 10)
        ranges.insert(20, 5)
        assertTrue(ranges.isEmpty)
    }

    @Test
    fun arraysRoundTripAndIgnoreJunk() {
        val ranges = StreamCacheRangeSet(
            listOf(listOf(0L, 10L), listOf(5L), listOf(-3L, 4L), listOf(30L, 30L), listOf(10L, 15L), listOf(1L, 2L, 3L)),
        )
        assertEquals(listOf(listOf(0L, 15L)), ranges.toArrays())
        assertEquals(ranges, StreamCacheRangeSet(ranges.toArrays()))
    }

    @Test
    fun clampDropsAndTrimsSpansBeyondLimit() {
        val ranges = set(0L to 10L, 20L to 40L, 50L to 60L)
        ranges.clamp(30)
        assertEquals(listOf(StreamCacheSpan(0, 10), StreamCacheSpan(20, 30)), ranges.spans)
        ranges.clamp(20)
        assertEquals(listOf(StreamCacheSpan(0, 10)), ranges.spans)
        ranges.clamp(0)
        assertTrue(ranges.isEmpty)
    }

    @Test
    fun lookupHelpers() {
        val ranges = set(10L to 20L, 30L to 40L)
        assertNull(ranges.spanContaining(5))
        assertEquals(StreamCacheSpan(10, 20), ranges.spanContaining(10))
        assertEquals(StreamCacheSpan(10, 20), ranges.spanContaining(19))
        assertNull(ranges.spanContaining(20))
        assertEquals(StreamCacheSpan(30, 40), ranges.spanContaining(35))
        assertNull(ranges.spanContaining(40))

        assertEquals(10L, ranges.nextStartAfter(0))
        assertEquals(30L, ranges.nextStartAfter(10))
        assertEquals(30L, ranges.nextStartAfter(25))
        assertNull(ranges.nextStartAfter(30))
    }

    @Test
    fun coversRequiresSingleSpanFromZero() {
        assertTrue(set(0L to 100L).covers(100))
        assertTrue(set(0L to 120L).covers(100))
        assertFalse(set(0L to 99L).covers(100))
        assertFalse(set(1L to 100L).covers(100))
        assertFalse(set(0L to 100L).covers(0))
        assertFalse(StreamCacheRangeSet().covers(10))
    }

    @Test
    fun firstGapFromZero() {
        assertEquals(StreamCacheSpan(0, 100), StreamCacheRangeSet().firstGap(100))
        assertEquals(StreamCacheSpan(0, 10), set(10L to 20L).firstGap(100))
        assertEquals(StreamCacheSpan(20, 30), set(0L to 20L, 30L to 100L).firstGap(100))
        assertEquals(StreamCacheSpan(50, 100), set(0L to 50L).firstGap(100))
        assertNull(set(0L to 100L).firstGap(100))
        assertNull(set(0L to 150L).firstGap(100))
        assertNull(StreamCacheRangeSet().firstGap(0))
        // A span starting beyond total still bounds the gap at total.
        assertEquals(StreamCacheSpan(0, 100), set(200L to 300L).firstGap(100))
    }

    @Test
    fun firstGapFromAnchorPrefersGapAtOrAfterAnchor() {
        val ranges = set(0L to 10L, 40L to 60L)
        // Anchor inside a gap: clipped to start at the anchor.
        assertEquals(StreamCacheSpan(25, 40), ranges.firstGap(100, 25))
        // Anchor inside a cached span: next gap after that span.
        assertEquals(StreamCacheSpan(60, 100), ranges.firstGap(100, 45))
        // Anchor exactly at a span end.
        assertEquals(StreamCacheSpan(10, 40), ranges.firstGap(100, 10))
    }

    @Test
    fun firstGapFromAnchorWrapsAroundToStart() {
        val ranges = set(20L to 30L, 50L to 100L)
        // Nothing missing at/after 60 → wrap to the first gap from 0.
        assertEquals(StreamCacheSpan(0, 20), ranges.firstGap(100, 60))

        val headCached = set(0L to 10L, 40L to 100L)
        assertEquals(StreamCacheSpan(10, 40), headCached.firstGap(100, 70))
    }

    @Test
    fun firstGapFromAnchorOutsideRangeBehavesLikeFirstGap() {
        val ranges = set(10L to 20L, 50L to 60L)
        assertEquals(ranges.firstGap(100), ranges.firstGap(100, 0))
        assertEquals(ranges.firstGap(100), ranges.firstGap(100, -5))
        assertEquals(ranges.firstGap(100), ranges.firstGap(100, 100))
        assertEquals(ranges.firstGap(100), ranges.firstGap(100, 500))
        assertNull(set(0L to 100L).firstGap(100, 50))
        assertNull(StreamCacheRangeSet().firstGap(0, 10))
    }

    @Test
    fun copyIsIndependentAndEqualityComparesSpans() {
        val original = set(0L to 10L)
        val copy = original.copy()
        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
        copy.insert(20, 30)
        assertNotEquals(original, copy)
        assertEquals(listOf(StreamCacheSpan(0, 10)), original.spans)
    }
}
