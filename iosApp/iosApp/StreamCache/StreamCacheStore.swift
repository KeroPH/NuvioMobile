import Foundation
import CryptoKit
import ComposeApp

// MARK: - Persisted metadata

/// Contents of `<root>/<id>/meta.json`.
struct StreamCacheMeta: Codable {
    var id: String
    var cacheKey: String
    /// Verbatim StreamCacheContext JSON handed over by Kotlin.
    var contextJson: String
    /// Display / export file name (sanitised, always has an extension).
    var fileName: String
    /// Name of the sparse data file inside the entry directory (`data.<ext>`).
    var dataFileName: String
    var mimeType: String?
    /// -1 while unknown.
    var totalBytes: Int64
    /// Cached spans as `[[start, endExclusive], ...]`.
    var ranges: [[Int64]]
    var pinned: Bool
    var createdAtMs: Int64
    var lastAccessMs: Int64
    /// Last upstream URL + headers (used by the filler).
    var sourceUrl: String?
    var headers: [String: String]
    var fillError: String?
    /// True when the file extension was guessed (no name/extension was known).
    var extensionGuessed: Bool?
}

// MARK: - In-memory entry

/// One cache entry. `meta`, `ranges`, `generation`, `activeSessions`, `isFilling` and `dirty`
/// are guarded by the store lock; `handle` and `ioClosed` by `ioLock`.
/// Lock order is always ioLock -> store lock (never the reverse).
final class StreamCacheEntryState {
    var meta: StreamCacheMeta
    var ranges: StreamCacheRangeSet
    /// Bumped whenever cached data is discarded; writes carrying an older generation are dropped.
    var generation: Int = 0
    var activeSessions: Int = 0
    var isFilling: Bool = false
    var dirty: Bool = false
    /// Most recent byte offset the proxy served/fetched for this entry (in memory only; -1 = none).
    /// The filler starts from this region instead of byte 0.
    var playheadOffset: Int64 = -1

    let directory: URL
    let dataURL: URL

    let ioLock = NSLock()
    var handle: FileHandle?
    var ioClosed: Bool = false

    init(meta: StreamCacheMeta, ranges: StreamCacheRangeSet, directory: URL) {
        self.meta = meta
        self.ranges = ranges
        self.directory = directory
        self.dataURL = directory.appendingPathComponent(meta.dataFileName, isDirectory: false)
    }

    var isComplete: Bool {
        ranges.covers(total: meta.totalBytes)
    }
}

/// Snapshot of what the filler needs to fetch the next missing span.
struct StreamCacheFillSource {
    let url: URL
    let headers: [String: String]
    let totalBytes: Int64
    let generation: Int
    /// First missing span; nil when complete (or unknown total).
    let gap: StreamCacheSpan?
}

// MARK: - Change notifications

/// Throttled (≤ 2 Hz), main-thread change notifications with a guaranteed trailing call.
/// `notify()` is cheap and may be called from any thread at high frequency.
final class StreamCacheNotifier {
    static let shared = StreamCacheNotifier()

    private let lock = NSLock()
    private var pending = false
    private var lastFire: TimeInterval = 0
    /// Accessed on the main thread only.
    private var listener: StreamCacheChangeListener?

    private let minInterval: TimeInterval = 0.5

    func setListener(_ newListener: StreamCacheChangeListener?) {
        if Thread.isMainThread {
            listener = newListener
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.listener = newListener
            }
        }
    }

    func notify() {
        let now = Date().timeIntervalSince1970
        lock.lock()
        if pending {
            lock.unlock()
            return
        }
        pending = true
        let delay = max(0, lastFire + minInterval - now)
        lock.unlock()
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            self.pending = false
            self.lastFire = Date().timeIntervalSince1970
            self.lock.unlock()
            self.listener?.onChanged()
        }
    }
}

// MARK: - Store

/// Disk-backed entries (`Application Support/StreamCache/<id>/data.<ext>` + `meta.json`),
/// config (UserDefaults), LRU eviction. All public methods are thread-safe.
final class StreamCacheStore {
    static let shared = StreamCacheStore()

    static let defaultMaxBytes: Int64 = 10_737_418_240 // 10 GiB
    static let minFreeBytes: Int64 = 2_147_483_648    // 2 GiB

    private static let enabledKey = "nuvio.streamcache.enabled"
    private static let maxBytesKey = "nuvio.streamcache.maxBytes"
    private static let autoFillKey = "nuvio.streamcache.autoFill"
    private static let lookAheadMinutesKey = "nuvio.streamcache.lookAheadMinutes"
    static let defaultAutoFill = true
    static let defaultLookAheadMinutes = 10
    static let maxLookAheadMinutes = 60
    /// The filler starts this far past the playhead so it doesn't race the player's own
    /// requests for the bytes it needs right now.
    static let fillLeadBytes: Int64 = 32 * 1024 * 1024
    private static let metaFileName = "meta.json"
    private static let knownVideoExtensions: Set<String> = [
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "wmv",
        "flv", "mpg", "mpeg", "ogv", "3gp", "divx", "vob",
    ]

    let rootURL: URL

    private let lock = NSLock()
    private var entries: [String: StreamCacheEntryState] = [:]
    private let maintenanceQueue = DispatchQueue(label: "com.nuvio.streamcache.maintenance", qos: .utility)
    private var saveScheduled = false
    private var evictionScheduled = false
    private var cachedFreeDisk: Int64 = -1
    private var freeDiskCheckedAt: TimeInterval = 0

    private init() {
        let fileManager = FileManager.default
        let base = (try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fileManager.temporaryDirectory
        var root = base.appendingPathComponent("StreamCache", isDirectory: true)
        try? fileManager.createDirectory(at: root, withIntermediateDirectories: true, attributes: nil)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? root.setResourceValues(values)
        rootURL = root
        loadEntries()
    }

    // MARK: Helpers

    static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// First 24 lowercase hex chars of SHA-256(cacheKey).
    static func entryId(forCacheKey cacheKey: String) -> String {
        let digest = SHA256.hash(data: Data(cacheKey.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return String(hex.prefix(24))
    }

    /// "video/mp4; charset=..." -> "video/mp4"
    static func normalizedMime(_ raw: String?) -> String? {
        guard let raw = raw else { return nil }
        let base = raw.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first.map { String($0) } ?? raw
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func extensionForMime(_ mime: String?) -> String {
        guard let mime = mime else { return "mkv" }
        if mime.contains("mp4") || mime.contains("m4v") { return "mp4" }
        if mime.contains("quicktime") { return "mov" }
        if mime.contains("webm") { return "webm" }
        if mime.contains("mp2t") { return "ts" }
        if mime.contains("x-msvideo") || mime.contains("avi") { return "avi" }
        return "mkv"
    }

    private static func sanitizeFileName(_ raw: String) -> String {
        let forbidden: Set<Character> = ["/", "\\", ":", "*", "?", "\"", "<", ">", "|"]
        var result = ""
        for character in raw {
            if forbidden.contains(character) || character.isNewline {
                result.append("_")
            } else if let ascii = character.asciiValue, ascii < 32 || ascii == 127 {
                result.append("_")
            } else {
                result.append(character)
            }
        }
        result = result.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        return result
    }

    /// Builds the entry's file name: context fileName, else last URL path component; sanitised,
    /// length-limited, and always ending in a video extension.
    private static func makeFileName(hint: String?, sourceUrl: URL, mime: String?) -> (name: String, guessed: Bool) {
        var raw = hint?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if raw.isEmpty {
            raw = sourceUrl.lastPathComponent
        }
        var name = sanitizeFileName(raw)
        if name.isEmpty || name == "/" {
            name = "video"
        }
        var ext = (name as NSString).pathExtension.lowercased()
        var guessed = false
        if !knownVideoExtensions.contains(ext) {
            let urlExt = sourceUrl.pathExtension.lowercased()
            if knownVideoExtensions.contains(urlExt) {
                ext = urlExt
            } else {
                ext = extensionForMime(mime)
                guessed = true
            }
            name += "." + ext
        }
        if name.count > 150 {
            let base = (name as NSString).deletingPathExtension
            name = String(base.prefix(140)) + "." + ext
        }
        return (name, guessed)
    }

    private func metaURL(for directory: URL) -> URL {
        directory.appendingPathComponent(StreamCacheStore.metaFileName, isDirectory: false)
    }

    /// Must be called with the store lock held.
    private func snapshotMetaLocked(_ entry: StreamCacheEntryState) -> StreamCacheMeta {
        var meta = entry.meta
        meta.ranges = entry.ranges.arrays
        return meta
    }

    private func writeMeta(_ meta: StreamCacheMeta, directory: URL) {
        do {
            let data = try JSONEncoder().encode(meta)
            try data.write(to: metaURL(for: directory), options: .atomic)
        } catch {
            // Directory may have been removed concurrently (delete/export); that's fine.
        }
    }

    // MARK: Loading

    private func loadEntries() {
        let fileManager = FileManager.default
        guard let children = try? fileManager.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return }
        var loaded: [String: StreamCacheEntryState] = [:]
        for directory in children {
            let isDirectory = (try? directory.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            guard isDirectory else { continue }
            guard let data = try? Data(contentsOf: metaURL(for: directory)),
                  let meta = try? JSONDecoder().decode(StreamCacheMeta.self, from: data),
                  meta.id == directory.lastPathComponent,
                  !meta.dataFileName.isEmpty,
                  !meta.cacheKey.isEmpty else {
                print("[StreamCache] discarding broken entry \(directory.lastPathComponent)")
                try? fileManager.removeItem(at: directory)
                continue
            }
            var ranges = StreamCacheRangeSet(arrays: meta.ranges)
            let dataURL = directory.appendingPathComponent(meta.dataFileName, isDirectory: false)
            let attributes = try? fileManager.attributesOfItem(atPath: dataURL.path)
            let fileSize = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
            // Never trust spans beyond what's physically in the file.
            ranges.clamp(to: fileSize)
            if meta.totalBytes > 0 {
                ranges.clamp(to: meta.totalBytes)
            }
            let entry = StreamCacheEntryState(meta: meta, ranges: ranges, directory: directory)
            if ranges != StreamCacheRangeSet(arrays: meta.ranges) {
                entry.dirty = true
            }
            loaded[meta.id] = entry
        }
        lock.lock()
        entries = loaded
        let needsSave = loaded.values.contains { $0.dirty }
        lock.unlock()
        if needsSave { scheduleSave() }
        if !loaded.isEmpty {
            print("[StreamCache] loaded \(loaded.count) entries")
        }
    }

    // MARK: Config

    var isEnabled: Bool {
        (UserDefaults.standard.object(forKey: StreamCacheStore.enabledKey) as? Bool) ?? true
    }

    func setEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: StreamCacheStore.enabledKey)
        StreamCacheNotifier.shared.notify()
    }

    var maxBytes: Int64 {
        (UserDefaults.standard.object(forKey: StreamCacheStore.maxBytesKey) as? NSNumber)?.int64Value
            ?? StreamCacheStore.defaultMaxBytes
    }

    func setMaxBytes(_ value: Int64) {
        UserDefaults.standard.set(NSNumber(value: max(0, value)), forKey: StreamCacheStore.maxBytesKey)
        maintenanceQueue.async { [weak self] in
            self?.evictIfNeeded()
        }
        StreamCacheNotifier.shared.notify()
    }

    /// Start a background fill of the whole file when playback of a not-yet-complete entry begins.
    var autoFillWhilePlaying: Bool {
        (UserDefaults.standard.object(forKey: StreamCacheStore.autoFillKey) as? Bool)
            ?? StreamCacheStore.defaultAutoFill
    }

    func setAutoFillWhilePlaying(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: StreamCacheStore.autoFillKey)
        StreamCacheNotifier.shared.notify()
    }

    /// mpv read-ahead in minutes (0 = mpv defaults), clamped to 0...60.
    var lookAheadMinutes: Int {
        let stored = (UserDefaults.standard.object(forKey: StreamCacheStore.lookAheadMinutesKey) as? NSNumber)?.intValue
            ?? StreamCacheStore.defaultLookAheadMinutes
        return min(max(stored, 0), StreamCacheStore.maxLookAheadMinutes)
    }

    func setLookAheadMinutes(_ minutes: Int) {
        let clamped = min(max(minutes, 0), StreamCacheStore.maxLookAheadMinutes)
        UserDefaults.standard.set(NSNumber(value: clamped), forKey: StreamCacheStore.lookAheadMinutesKey)
        StreamCacheNotifier.shared.notify()
    }

    /// Free space for important usage, cached for `maxAge` seconds. -1 when unknown.
    func freeDiskBytes(maxAge: TimeInterval = 5) -> Int64 {
        let now = Date().timeIntervalSince1970
        lock.lock()
        if now - freeDiskCheckedAt < maxAge {
            let cached = cachedFreeDisk
            lock.unlock()
            return cached
        }
        lock.unlock()
        var value: Int64 = -1
        if let resourceValues = try? rootURL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let capacity = resourceValues.volumeAvailableCapacityForImportantUsage {
            value = capacity
        }
        lock.lock()
        cachedFreeDisk = value
        freeDiskCheckedAt = now
        lock.unlock()
        return value
    }

    /// False when free disk space is below `minFreeBytes` (caching pauses, pass-through continues).
    func hasEnoughFreeSpace() -> Bool {
        let free = freeDiskBytes()
        return free < 0 || free >= StreamCacheStore.minFreeBytes
    }

    var usedBytes: Int64 {
        lock.lock()
        defer { lock.unlock() }
        var sum: Int64 = 0
        for entry in entries.values {
            sum += entry.ranges.totalLength
        }
        return sum
    }

    func configJSON() -> String {
        let object: [String: Any] = [
            "enabled": isEnabled,
            "maxBytes": NSNumber(value: maxBytes),
            "minFreeBytes": NSNumber(value: StreamCacheStore.minFreeBytes),
            "usedBytes": NSNumber(value: usedBytes),
            "freeDiskBytes": NSNumber(value: freeDiskBytes(maxAge: 1)),
            "autoFillWhilePlaying": autoFillWhilePlaying,
            "lookAheadMinutes": NSNumber(value: lookAheadMinutes),
        ]
        return StreamCacheStore.jsonString(object) ?? "{}"
    }

    static func jsonString(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: []) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: Entries (bridge-facing)

    /// Creates or updates the entry for `cacheKey`. Returns the entry id, or nil on disk failure.
    func prepareEntry(cacheKey: String, contextJson: String, fileNameHint: String?,
                      sourceUrl: URL, headers: [String: String]) -> String? {
        let id = StreamCacheStore.entryId(forCacheKey: cacheKey)
        let now = StreamCacheStore.nowMs()

        lock.lock()
        if let existing = entries[id] {
            existing.meta.contextJson = contextJson
            existing.meta.sourceUrl = sourceUrl.absoluteString
            existing.meta.headers = headers
            existing.meta.lastAccessMs = now
            existing.dirty = true
            lock.unlock()
            scheduleSave()
            StreamCacheNotifier.shared.notify()
            return id
        }
        lock.unlock()

        let directory = rootURL.appendingPathComponent(id, isDirectory: true)
        let fileManager = FileManager.default
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        } catch {
            print("[StreamCache] cannot create entry dir: \(error.localizedDescription)")
            return nil
        }
        let naming = StreamCacheStore.makeFileName(hint: fileNameHint, sourceUrl: sourceUrl, mime: nil)
        let ext = (naming.name as NSString).pathExtension.lowercased()
        let meta = StreamCacheMeta(
            id: id,
            cacheKey: cacheKey,
            contextJson: contextJson,
            fileName: naming.name,
            dataFileName: "data." + (ext.isEmpty ? "mkv" : ext),
            mimeType: nil,
            totalBytes: -1,
            ranges: [],
            pinned: false,
            createdAtMs: now,
            lastAccessMs: now,
            sourceUrl: sourceUrl.absoluteString,
            headers: headers,
            fillError: nil,
            extensionGuessed: naming.guessed
        )
        let entry = StreamCacheEntryState(meta: meta, ranges: StreamCacheRangeSet(), directory: directory)
        lock.lock()
        if let raced = entries[id] {
            // Another thread created it meanwhile; keep that one.
            raced.meta.sourceUrl = sourceUrl.absoluteString
            raced.meta.headers = headers
            raced.dirty = true
            lock.unlock()
            scheduleSave()
            return id
        }
        entries[id] = entry
        lock.unlock()
        writeMeta(meta, directory: directory)
        StreamCacheNotifier.shared.notify()
        return id
    }

    func fileName(id: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return entries[id]?.meta.fileName
    }

    /// What `entryPlaybackUrl` needs to play an entry straight from the cache (no stream lookup).
    struct EntryPlaybackInfo {
        let cachedBytes: Int64
        let isComplete: Bool
        let dataURL: URL
        let fileName: String
        /// Stored upstream URL/headers (may be nil for old/broken metadata).
        let sourceUrl: URL?
        let headers: [String: String]
    }

    func entryPlaybackInfo(id: String) -> EntryPlaybackInfo? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[id] else { return nil }
        var source: URL?
        if let raw = entry.meta.sourceUrl, !raw.isEmpty {
            source = URL(string: raw)
        }
        return EntryPlaybackInfo(
            cachedBytes: entry.ranges.totalLength,
            isComplete: entry.isComplete,
            dataURL: entry.dataURL,
            fileName: entry.meta.fileName,
            sourceUrl: source,
            headers: entry.meta.headers
        )
    }

    /// Prepares a complete entry's data file to be opened directly by the player: flushes pending
    /// writes and cuts a stale tail beyond the real size (the sparse file may be longer).
    /// Returns false when the entry is gone, not complete, or the file is missing.
    func prepareFilePlayback(id: String) -> Bool {
        lock.lock()
        let found = entries[id]
        lock.unlock()
        guard let entry = found else { return false }

        entry.ioLock.lock()
        defer { entry.ioLock.unlock() }
        lock.lock()
        let complete = entries[id] === entry && entry.isComplete
        let total = entry.meta.totalBytes
        lock.unlock()
        guard complete, total > 0, !entry.ioClosed else { return false }
        let path = entry.dataURL.path
        let attributes = try? FileManager.default.attributesOfItem(atPath: path)
        guard let fileSize = (attributes?[.size] as? NSNumber)?.int64Value, fileSize >= total else {
            return false
        }
        if fileSize > total {
            if let handle = openHandleLocked(entry) {
                try? handle.truncate(atOffset: UInt64(total))
            }
        }
        if let handle = entry.handle {
            try? handle.synchronize()
        }
        return true
    }

    func touch(id: String) {
        lock.lock()
        if let entry = entries[id] {
            entry.meta.lastAccessMs = StreamCacheStore.nowMs()
            entry.dirty = true
        }
        lock.unlock()
        scheduleSave()
    }

    /// A playback session started (+1) or ended (-1) for this entry.
    func markActive(id: String, delta: Int) {
        lock.lock()
        guard let entry = entries[id] else {
            lock.unlock()
            return
        }
        entry.activeSessions = max(0, entry.activeSessions + delta)
        entry.meta.lastAccessMs = StreamCacheStore.nowMs()
        entry.dirty = true
        lock.unlock()
        if delta < 0 {
            flush(id: id)
        } else {
            scheduleSave()
        }
        StreamCacheNotifier.shared.notify()
    }

    // MARK: Entries (proxy / filler facing)

    /// Total size, mime and current generation; nil if the entry no longer exists.
    func serveInfo(id: String) -> (totalBytes: Int64, mimeType: String?, generation: Int)? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[id] else { return nil }
        return (entry.meta.totalBytes, entry.meta.mimeType, entry.generation)
    }

    /// Records the upstream total size (and mime). If a different total was stored before, the
    /// cached data is discarded and the entry restarts. Returns the current generation, or nil
    /// if the entry is gone.
    func setTotal(id: String, totalBytes: Int64, mimeType rawMime: String?) -> Int? {
        lock.lock()
        let found = entries[id]
        lock.unlock()
        guard let entry = found, totalBytes > 0 else { return nil }
        let mime = StreamCacheStore.normalizedMime(rawMime)

        entry.ioLock.lock()
        lock.lock()
        var needsTruncate = false
        var changed = false
        if entry.meta.totalBytes != totalBytes {
            if entry.meta.totalBytes > 0 || !entry.ranges.isEmpty {
                print("[StreamCache] size changed for \(id) (\(entry.meta.totalBytes) -> \(totalBytes)); restarting entry")
                entry.ranges.removeAll()
                entry.generation += 1
                needsTruncate = true
            }
            entry.meta.totalBytes = totalBytes
            changed = true
        }
        if let mime = mime, mime != "application/octet-stream", entry.meta.mimeType != mime {
            entry.meta.mimeType = mime
            if entry.meta.extensionGuessed == true {
                let ext = StreamCacheStore.extensionForMime(mime)
                let base = (entry.meta.fileName as NSString).deletingPathExtension
                entry.meta.fileName = base + "." + ext
                entry.meta.extensionGuessed = false
            }
            changed = true
        }
        if changed { entry.dirty = true }
        let generation = entry.generation
        lock.unlock()
        // Drop the old bytes even when no handle is open yet, so a later export never carries a
        // stale tail beyond the new total size.
        if needsTruncate, !entry.ioClosed,
           FileManager.default.fileExists(atPath: entry.dataURL.path),
           let handle = openHandleLocked(entry) {
            try? handle.truncate(atOffset: 0)
        }
        entry.ioLock.unlock()

        if changed {
            scheduleSave()
            StreamCacheNotifier.shared.notify()
        }
        return generation
    }

    /// Exclusive end of the cached span containing `position`, if cached.
    func cachedEnd(id: String, containing position: Int64) -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return entries[id]?.ranges.span(containing: position)?.end
    }

    /// Start of the next cached span after `position`.
    func nextCachedStart(id: String, after position: Int64) -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return entries[id]?.ranges.nextStart(after: position)
    }

    /// Must be called with `entry.ioLock` held.
    private func openHandleLocked(_ entry: StreamCacheEntryState) -> FileHandle? {
        if let handle = entry.handle { return handle }
        let fileManager = FileManager.default
        if !fileManager.fileExists(atPath: entry.dataURL.path) {
            guard fileManager.createFile(atPath: entry.dataURL.path, contents: nil, attributes: nil) else {
                return nil
            }
        }
        guard let handle = try? FileHandle(forUpdating: entry.dataURL) else { return nil }
        entry.handle = handle
        return handle
    }

    /// Reads cached bytes from the data file. Caller must have checked the span is cached.
    func read(id: String, offset: Int64, length: Int) -> Data? {
        guard length > 0 else { return Data() }
        lock.lock()
        let found = entries[id]
        lock.unlock()
        guard let entry = found else { return nil }
        entry.ioLock.lock()
        defer { entry.ioLock.unlock() }
        guard !entry.ioClosed, let handle = openHandleLocked(entry) else { return nil }
        do {
            try handle.seek(toOffset: UInt64(offset))
            return try handle.read(upToCount: length)
        } catch {
            print("[StreamCache] read failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// Writes bytes at `offset` and, only after the write succeeded, marks them cached.
    /// Returns false when nothing was cached (low disk, entry gone/reset, I/O error).
    @discardableResult
    func write(id: String, generation: Int, offset: Int64, data: Data) -> Bool {
        guard !data.isEmpty, offset >= 0, hasEnoughFreeSpace() else { return false }
        lock.lock()
        let found = entries[id]
        let generationMatches = found?.generation == generation
        lock.unlock()
        guard let entry = found, generationMatches else { return false }

        entry.ioLock.lock()
        guard !entry.ioClosed, let handle = openHandleLocked(entry) else {
            entry.ioLock.unlock()
            return false
        }
        do {
            try handle.seek(toOffset: UInt64(offset))
            try handle.write(contentsOf: data)
        } catch {
            entry.ioLock.unlock()
            print("[StreamCache] write failed: \(error.localizedDescription)")
            return false
        }
        lock.lock()
        var recorded = false
        if entry.generation == generation, entries[id] === entry {
            entry.ranges.insert(start: offset, end: offset + Int64(data.count))
            entry.dirty = true
            recorded = true
        }
        lock.unlock()
        entry.ioLock.unlock()

        if recorded {
            scheduleSave()
            scheduleEvictionCheck()
            StreamCacheNotifier.shared.notify()
        }
        return recorded
    }

    func fillSource(id: String) -> StreamCacheFillSource? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[id],
              let raw = entry.meta.sourceUrl,
              let url = URL(string: raw) else { return nil }
        let total = entry.meta.totalBytes
        // Fill from just ahead of where the player is reading, then wrap around to earlier gaps.
        let anchor: Int64 = entry.playheadOffset >= 0 ? entry.playheadOffset + StreamCacheStore.fillLeadBytes : 0
        return StreamCacheFillSource(
            url: url,
            headers: entry.meta.headers,
            totalBytes: total,
            generation: entry.generation,
            gap: total > 0 ? entry.ranges.firstGap(total: total, from: anchor) : nil
        )
    }

    /// Records the byte offset the proxy is currently serving for this entry (cheap; called often).
    func notePlayhead(id: String, offset: Int64) {
        lock.lock()
        entries[id]?.playheadOffset = offset
        lock.unlock()
    }

    /// True when an automatic "fill while playing" may start for this entry: it exists, is still
    /// being played, its size is known (the proxy's ranged probe succeeded, so the server honours
    /// Range), it is not complete and not already filling.
    func canAutoFill(id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[id] else { return false }
        return entry.activeSessions > 0
            && entry.meta.sourceUrl != nil
            && entry.meta.totalBytes > 0
            && !entry.isFilling
            && !entry.isComplete
    }

    /// Marks the entry as filling. Returns false if missing or already filling.
    func beginFill(id: String) -> Bool {
        lock.lock()
        guard let entry = entries[id], !entry.isFilling else {
            lock.unlock()
            return false
        }
        entry.isFilling = true
        entry.meta.fillError = nil
        entry.dirty = true
        lock.unlock()
        scheduleSave()
        StreamCacheNotifier.shared.notify()
        return true
    }

    func endFill(id: String, error: String?) {
        lock.lock()
        if let entry = entries[id] {
            entry.isFilling = false
            entry.meta.fillError = error
            entry.dirty = true
        }
        lock.unlock()
        flush(id: id)
        StreamCacheNotifier.shared.notify()
    }

    func setPinned(id: String, pinned: Bool) {
        lock.lock()
        if let entry = entries[id] {
            entry.meta.pinned = pinned
            entry.dirty = true
        }
        lock.unlock()
        flush(id: id)
        StreamCacheNotifier.shared.notify()
        if !pinned {
            scheduleEvictionCheck()
        }
    }

    func entryExists(id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return entries[id] != nil
    }

    // MARK: Listing

    func listEntriesJSON() -> String {
        lock.lock()
        let sorted = entries.values.sorted { $0.meta.lastAccessMs > $1.meta.lastAccessMs }
        var array: [[String: Any]] = []
        array.reserveCapacity(sorted.count)
        for entry in sorted {
            let meta = entry.meta
            var mimeValue: Any = NSNull()
            if let mime = meta.mimeType { mimeValue = mime }
            var fillErrorValue: Any = NSNull()
            if let fillError = meta.fillError { fillErrorValue = fillError }
            let item: [String: Any] = [
                "id": meta.id,
                "contextJson": meta.contextJson,
                "fileName": meta.fileName,
                "mimeType": mimeValue,
                "totalBytes": NSNumber(value: meta.totalBytes),
                "cachedBytes": NSNumber(value: entry.ranges.totalLength),
                "isComplete": entry.isComplete,
                "pinned": meta.pinned,
                "isActive": entry.activeSessions > 0,
                "isFilling": entry.isFilling,
                "fillError": fillErrorValue,
                "createdAtMs": NSNumber(value: meta.createdAtMs),
                "lastAccessMs": NSNumber(value: meta.lastAccessMs),
            ]
            array.append(item)
        }
        lock.unlock()
        return StreamCacheStore.jsonString(array) ?? "[]"
    }

    // MARK: Removal / export

    /// Closes the file handle and deletes the entry directory. The entry must already be
    /// removed from `entries`.
    private func closeAndRemove(_ entry: StreamCacheEntryState) {
        entry.ioLock.lock()
        entry.ioClosed = true
        if let handle = entry.handle {
            try? handle.close()
        }
        entry.handle = nil
        entry.ioLock.unlock()
        try? FileManager.default.removeItem(at: entry.directory)
    }

    func deleteEntry(id: String) {
        lock.lock()
        let removed = entries.removeValue(forKey: id)
        lock.unlock()
        guard let entry = removed else { return }
        closeAndRemove(entry)
        StreamCacheNotifier.shared.notify()
    }

    func clearAll() {
        lock.lock()
        let all = Array(entries.values)
        entries.removeAll()
        lock.unlock()
        for entry in all {
            closeAndRemove(entry)
        }
        // Remove anything else left in the root (stray dirs), keep the root itself.
        if let children = try? FileManager.default.contentsOfDirectory(at: rootURL, includingPropertiesForKeys: nil, options: []) {
            for child in children {
                try? FileManager.default.removeItem(at: child)
            }
        }
        StreamCacheNotifier.shared.notify()
    }

    /// Moves the data file of a complete, idle entry to `destinationPath` and removes the entry.
    /// Returns "" on success, otherwise an error message.
    func exportEntry(id: String, destinationPath: String) -> String {
        lock.lock()
        guard let entry = entries[id] else {
            lock.unlock()
            return "Cache entry not found"
        }
        guard entry.isComplete else {
            lock.unlock()
            return "This stream is not fully cached yet"
        }
        guard entry.activeSessions == 0 else {
            lock.unlock()
            return "Playing — try again after closing the player"
        }
        guard !entry.isFilling else {
            lock.unlock()
            return "This stream is still downloading"
        }
        let exportTotal = entry.meta.totalBytes
        entries.removeValue(forKey: id)
        lock.unlock()

        entry.ioLock.lock()
        if !entry.ioClosed, exportTotal > 0, let handle = openHandleLocked(entry) {
            // The sparse file may be longer than the media (stale bytes); cut it to the real size.
            try? handle.truncate(atOffset: UInt64(exportTotal))
        }
        entry.ioClosed = true
        if let handle = entry.handle {
            try? handle.synchronize()
            try? handle.close()
        }
        entry.handle = nil
        entry.ioLock.unlock()

        let fileManager = FileManager.default
        let destination = URL(fileURLWithPath: destinationPath)
        do {
            try fileManager.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true,
                attributes: nil
            )
            if fileManager.fileExists(atPath: destination.path) {
                try fileManager.removeItem(at: destination)
            }
            try fileManager.moveItem(at: entry.dataURL, to: destination)
        } catch {
            // Put the entry back so nothing is lost.
            entry.ioLock.lock()
            entry.ioClosed = false
            entry.ioLock.unlock()
            lock.lock()
            if entries[id] == nil {
                entries[id] = entry
            }
            lock.unlock()
            StreamCacheNotifier.shared.notify()
            return "Could not move the file: \(error.localizedDescription)"
        }
        try? fileManager.removeItem(at: entry.directory)
        print("[StreamCache] exported \(id)")
        StreamCacheNotifier.shared.notify()
        return ""
    }

    // MARK: Persistence

    /// Writes this entry's metadata now.
    func flush(id: String) {
        lock.lock()
        guard let entry = entries[id] else {
            lock.unlock()
            return
        }
        let meta = snapshotMetaLocked(entry)
        let directory = entry.directory
        entry.dirty = false
        lock.unlock()
        writeMeta(meta, directory: directory)
    }

    /// Writes all dirty metadata now.
    func flushAll() {
        saveDirtyEntries()
    }

    /// Debounced metadata save (at most every 2 s).
    private func scheduleSave() {
        lock.lock()
        if saveScheduled {
            lock.unlock()
            return
        }
        saveScheduled = true
        lock.unlock()
        maintenanceQueue.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            self?.saveDirtyEntries()
        }
    }

    private func saveDirtyEntries() {
        lock.lock()
        saveScheduled = false
        var pending: [(StreamCacheMeta, URL)] = []
        for entry in entries.values where entry.dirty {
            pending.append((snapshotMetaLocked(entry), entry.directory))
            entry.dirty = false
        }
        lock.unlock()
        for (meta, directory) in pending {
            writeMeta(meta, directory: directory)
        }
    }

    // MARK: Eviction

    /// Throttled size-limit check (at most every 3 s).
    func scheduleEvictionCheck() {
        lock.lock()
        if evictionScheduled {
            lock.unlock()
            return
        }
        evictionScheduled = true
        lock.unlock()
        maintenanceQueue.asyncAfter(deadline: .now() + 3.0) { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            self.evictionScheduled = false
            self.lock.unlock()
            self.evictIfNeeded()
        }
    }

    /// Deletes least-recently-accessed entries that are not pinned, active or filling until
    /// the used size fits `maxBytes` (0 = unlimited).
    private func evictIfNeeded() {
        let limit = maxBytes
        guard limit > 0 else { return }
        lock.lock()
        var used: Int64 = 0
        for entry in entries.values {
            used += entry.ranges.totalLength
        }
        guard used > limit else {
            lock.unlock()
            return
        }
        let candidates = entries.values
            .filter { !$0.meta.pinned && $0.activeSessions == 0 && !$0.isFilling }
            .sorted { $0.meta.lastAccessMs < $1.meta.lastAccessMs }
        var victims: [StreamCacheEntryState] = []
        for candidate in candidates {
            if used <= limit { break }
            victims.append(candidate)
            entries.removeValue(forKey: candidate.meta.id)
            used -= candidate.ranges.totalLength
        }
        lock.unlock()
        guard !victims.isEmpty else { return }
        for victim in victims {
            closeAndRemove(victim)
        }
        print("[StreamCache] evicted \(victims.count) entries to fit the size limit")
        StreamCacheNotifier.shared.notify()
    }
}
