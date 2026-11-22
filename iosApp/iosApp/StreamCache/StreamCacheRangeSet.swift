import Foundation

/// A half-open byte span `[start, end)`.
struct StreamCacheSpan: Equatable {
    var start: Int64
    var end: Int64

    var length: Int64 { end - start }
}

/// Sorted, merged (non-overlapping, non-adjacent) set of cached byte spans.
/// Value type; callers synchronise access (the store guards it with its lock).
struct StreamCacheRangeSet: Equatable {
    private(set) var spans: [StreamCacheSpan] = []

    init() {}

    /// Builds a set from the persisted `[[start, endExclusive], ...]` form, ignoring junk.
    init(arrays: [[Int64]]) {
        for pair in arrays where pair.count == 2 {
            insert(start: pair[0], end: pair[1])
        }
    }

    /// Persisted form: `[[start, endExclusive], ...]`.
    var arrays: [[Int64]] {
        spans.map { [$0.start, $0.end] }
    }

    var isEmpty: Bool { spans.isEmpty }

    /// Sum of all cached bytes.
    var totalLength: Int64 {
        var sum: Int64 = 0
        for span in spans { sum += span.length }
        return sum
    }

    /// Adds `[start, end)` and merges overlapping/adjacent spans.
    mutating func insert(start: Int64, end: Int64) {
        guard start >= 0, end > start else { return }
        var newStart = start
        var newEnd = end
        var result: [StreamCacheSpan] = []
        result.reserveCapacity(spans.count + 1)
        var inserted = false
        for span in spans {
            if span.end < newStart {
                result.append(span)
            } else if span.start > newEnd {
                if !inserted {
                    result.append(StreamCacheSpan(start: newStart, end: newEnd))
                    inserted = true
                }
                result.append(span)
            } else {
                newStart = min(newStart, span.start)
                newEnd = max(newEnd, span.end)
            }
        }
        if !inserted {
            result.append(StreamCacheSpan(start: newStart, end: newEnd))
        }
        spans = result
    }

    mutating func removeAll() {
        spans.removeAll()
    }

    /// Drops everything at or beyond `limit` (used to clamp to the real data-file size).
    mutating func clamp(to limit: Int64) {
        var result: [StreamCacheSpan] = []
        for span in spans where span.start < limit {
            result.append(StreamCacheSpan(start: span.start, end: min(span.end, limit)))
        }
        spans = result.filter { $0.end > $0.start }
    }

    /// The span containing `position`, if cached.
    func span(containing position: Int64) -> StreamCacheSpan? {
        for span in spans {
            if span.start > position { return nil }
            if position < span.end { return span }
        }
        return nil
    }

    /// Start of the first cached span that begins after `position`.
    func nextStart(after position: Int64) -> Int64? {
        for span in spans where span.start > position {
            return span.start
        }
        return nil
    }

    /// True when `[0, total)` is fully covered.
    func covers(total: Int64) -> Bool {
        guard total > 0, let first = spans.first else { return false }
        return first.start <= 0 && first.end >= total
    }

    /// First missing span inside `[0, total)`, or nil when complete.
    func firstGap(total: Int64) -> StreamCacheSpan? {
        guard total > 0 else { return nil }
        var position: Int64 = 0
        for span in spans {
            if span.start > position {
                return StreamCacheSpan(start: position, end: min(span.start, total))
            }
            position = max(position, span.end)
            if position >= total { return nil }
        }
        return position < total ? StreamCacheSpan(start: position, end: total) : nil
    }
}
