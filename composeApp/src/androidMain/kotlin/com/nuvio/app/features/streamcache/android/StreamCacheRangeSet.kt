package com.nuvio.app.features.streamcache.android

/**
 * A half-open byte span `[start, end)`.
 * Port of `StreamCacheSpan` (iosApp/iosApp/StreamCache/StreamCacheRangeSet.swift).
 */
internal data class StreamCacheSpan(
    val start: Long,
    /** Exclusive. */
    val end: Long,
) {
    val length: Long get() = end - start
}

/**
 * Sorted, merged (non-overlapping, non-adjacent) set of cached byte spans.
 * Port of `StreamCacheRangeSet` (Swift value type → here a mutable class).
 *
 * NOT thread-safe: the store guards every instance with its store lock. Use [copy] to hand a
 * snapshot to another thread. [equals]/[hashCode] compare the span lists (used by
 * StreamCacheStore.loadEntries to detect clamping → dirty).
 */
internal class StreamCacheRangeSet() {
    private val spanList: ArrayList<StreamCacheSpan> = ArrayList()

    /**
     * Builds a set from the persisted `[[start, endExclusive], ...]` form, ignoring junk
     * (pairs whose size != 2, negative starts, empty spans) by calling [insert] for each pair.
     */
    constructor(arrays: List<List<Long>>) : this() {
        for (pair in arrays) {
            if (pair.size == 2) insert(pair[0], pair[1])
        }
    }

    /** Current spans, ascending by start (read-only view / copy). */
    val spans: List<StreamCacheSpan>
        get() = ArrayList(spanList)

    val isEmpty: Boolean
        get() = spanList.isEmpty()

    /** Sum of all cached bytes. */
    val totalLength: Long
        get() {
            var sum = 0L
            for (span in spanList) sum += span.length
            return sum
        }

    /** Persisted form: `[[start, endExclusive], ...]` (stored in meta.json `ranges`). */
    fun toArrays(): List<List<Long>> = spanList.map { listOf(it.start, it.end) }

    /**
     * Adds `[start, end)` and merges overlapping AND adjacent spans (a span whose end == start
     * of the new one is merged, mirroring Swift `span.end < newStart` / `span.start > newEnd`).
     * No-op when `start < 0` or `end <= start`.
     */
    fun insert(start: Long, end: Long) {
        if (start < 0 || end <= start) return
        var newStart = start
        var newEnd = end
        val result = ArrayList<StreamCacheSpan>(spanList.size + 1)
        var inserted = false
        for (span in spanList) {
            if (span.end < newStart) {
                result.add(span)
            } else if (span.start > newEnd) {
                if (!inserted) {
                    result.add(StreamCacheSpan(newStart, newEnd))
                    inserted = true
                }
                result.add(span)
            } else {
                newStart = minOf(newStart, span.start)
                newEnd = maxOf(newEnd, span.end)
            }
        }
        if (!inserted) result.add(StreamCacheSpan(newStart, newEnd))
        spanList.clear()
        spanList.addAll(result)
    }

    fun removeAll() {
        spanList.clear()
    }

    /** Drops everything at or beyond [limit] (clamps to the real data-file size / total). */
    fun clamp(limit: Long) {
        val result = ArrayList<StreamCacheSpan>(spanList.size)
        for (span in spanList) {
            if (span.start >= limit) continue
            val clipped = StreamCacheSpan(span.start, minOf(span.end, limit))
            if (clipped.end > clipped.start) result.add(clipped)
        }
        spanList.clear()
        spanList.addAll(result)
    }

    /** The span containing [position] (`start <= position < end`), if cached. */
    fun spanContaining(position: Long): StreamCacheSpan? {
        for (span in spanList) {
            if (span.start > position) return null
            if (position < span.end) return span
        }
        return null
    }

    /** Start of the first cached span that begins strictly after [position]. */
    fun nextStartAfter(position: Long): Long? {
        for (span in spanList) {
            if (span.start > position) return span.start
        }
        return null
    }

    /** True when `[0, total)` is fully covered (false when total <= 0). */
    fun covers(total: Long): Boolean {
        if (total <= 0) return false
        val first = spanList.firstOrNull() ?: return false
        return first.start <= 0 && first.end >= total
    }

    /** First missing span inside `[0, total)`, or null when complete / total <= 0. */
    fun firstGap(total: Long): StreamCacheSpan? {
        if (total <= 0) return null
        var position = 0L
        for (span in spanList) {
            if (span.start > position) {
                return StreamCacheSpan(position, minOf(span.start, total))
            }
            position = maxOf(position, span.end)
            if (position >= total) return null
        }
        return if (position < total) StreamCacheSpan(position, total) else null
    }

    /**
     * First missing span at or after [anchor] (clipped to start at [anchor]); when there is none,
     * wraps around to [firstGap] from 0. Null when `[0, total)` is complete. Anchors outside
     * `(0, total)` behave like [firstGap].
     */
    fun firstGap(total: Long, anchor: Long): StreamCacheSpan? {
        if (total <= 0) return null
        if (anchor > 0 && anchor < total) {
            var position = anchor
            for (span in spanList) {
                if (span.end <= position) continue
                if (span.start > position) {
                    return StreamCacheSpan(position, minOf(span.start, total))
                }
                position = span.end
                if (position >= total) break
            }
            if (position < total) {
                return StreamCacheSpan(position, total)
            }
        }
        return firstGap(total)
    }

    /** Independent deep copy. */
    fun copy(): StreamCacheRangeSet {
        val result = StreamCacheRangeSet()
        result.spanList.addAll(spanList)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamCacheRangeSet) return false
        return spanList == other.spanList
    }

    override fun hashCode(): Int = spanList.hashCode()

    override fun toString(): String = "StreamCacheRangeSet($spanList)"
}
