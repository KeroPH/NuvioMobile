import Foundation
import UIKit
import ComposeApp

/// Swift implementation of the Kotlin `NuvioStreamCacheBridge` (see Docs/stream-cache.md).
/// Registered from `NuvioPlayerRegistration.register()`. Called on the main thread.
final class StreamCacheBridgeImpl: NSObject, NuvioStreamCacheBridge {
    static let shared = StreamCacheBridgeImpl()

    private var observers: [NSObjectProtocol] = []

    private override init() {
        super.init()
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: nil
        ) { _ in
            StreamCacheStore.shared.flushAll()
        })
        observers.append(center.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: nil
        ) { _ in
            // iOS may have torn down the listening socket while suspended.
            StreamCacheProxy.shared.restartIfNeeded()
        })
    }

    // MARK: Helpers

    private static func jsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []) else { return nil }
        return object as? [String: Any]
    }

    private static func parseHeaders(_ text: String) -> [String: String] {
        guard let object = jsonObject(text) else { return [:] }
        var headers: [String: String] = [:]
        for (name, value) in object {
            if let string = value as? String {
                headers[name] = string
            } else if !(value is NSNull) {
                headers[name] = "\(value)"
            }
        }
        return headers
    }

    private static func isLoopbackHost(_ rawHost: String) -> Bool {
        let host = rawHost.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        return host == "localhost"
            || host.hasSuffix(".localhost")
            || host.hasPrefix("127.")
            || host == "::1"
            || host == "0.0.0.0"
            || host == "::"
    }

    // MARK: NuvioStreamCacheBridge

    func preparePlaybackUrl(sourceUrl: String, headersJson: String, contextJson: String) -> String {
        let store = StreamCacheStore.shared
        guard store.isEnabled else { return sourceUrl }
        guard let url = URL(string: sourceUrl),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty,
              !StreamCacheBridgeImpl.isLoopbackHost(host) else { return sourceUrl }
        let path = url.path.lowercased()
        if path.hasSuffix(".m3u8") || path.hasSuffix(".mpd") {
            return sourceUrl
        }
        guard let context = StreamCacheBridgeImpl.jsonObject(contextJson),
              let cacheKey = context["cacheKey"] as? String,
              !cacheKey.isEmpty else { return sourceUrl }
        guard store.hasEnoughFreeSpace() else {
            print("[StreamCache] low disk space; streaming without cache")
            return sourceUrl
        }

        let headers = StreamCacheBridgeImpl.parseHeaders(headersJson)
        let fileNameHint = context["fileName"] as? String
        guard let id = store.prepareEntry(
            cacheKey: cacheKey,
            contextJson: contextJson,
            fileNameHint: fileNameHint,
            sourceUrl: url,
            headers: headers
        ), let fileName = store.fileName(id: id) else { return sourceUrl }

        let proxy = StreamCacheProxy.shared
        guard let port = proxy.ensureListening() else {
            print("[StreamCache] proxy unavailable; streaming without cache")
            return sourceUrl
        }
        let session = proxy.registerSession(entryId: id, upstreamURL: url, headers: headers, fileName: fileName)
        if store.autoFillWhilePlaying {
            StreamCacheBridgeImpl.scheduleAutoFill(id: id)
        }
        return proxy.playbackURL(port: port, session: session)
    }

    /// Plays a cached entry without any stream lookup (works offline). Complete entry -> `file://`
    /// URL of the data file (held "in use" until `releasePlaybackUrl` with that URL); partial entry
    /// -> proxy URL backed by the stored upstream URL/headers. "" when missing / nothing cached.
    /// Works regardless of the "enabled" setting (the proxy then doesn't store new bytes).
    func entryPlaybackUrl(id: String) -> String {
        let store = StreamCacheStore.shared
        guard let info = store.entryPlaybackInfo(id: id), info.cachedBytes > 0 else { return "" }
        let proxy = StreamCacheProxy.shared

        if info.isComplete, store.prepareFilePlayback(id: id) {
            let fileURLString = URL(fileURLWithPath: info.dataURL.path).absoluteString
            proxy.registerFileSession(entryId: id, fileURLString: fileURLString)
            return fileURLString
        }

        guard let port = proxy.ensureListening() else {
            print("[StreamCache] proxy unavailable; cannot play cached entry \(id)")
            return ""
        }
        let session = proxy.registerSession(
            entryId: id,
            upstreamURL: info.sourceUrl,
            headers: info.headers,
            fileName: info.fileName
        )
        if info.sourceUrl != nil, store.autoFillWhilePlaying {
            StreamCacheBridgeImpl.scheduleAutoFill(id: id)
        }
        return proxy.playbackURL(port: port, session: session)
    }

    /// Delay before an automatic fill starts, so the player's own first requests get the bandwidth.
    private static let autoFillDelay: TimeInterval = 8

    /// "Fill while playing": after a short delay, downloads the rest of the file in the background.
    /// The fill keeps running after playback ends; turning the setting off does not cancel it.
    private static func scheduleAutoFill(id: String) {
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + autoFillDelay) {
            let store = StreamCacheStore.shared
            guard store.isEnabled,
                  store.autoFillWhilePlaying,
                  store.hasEnoughFreeSpace(),
                  store.canAutoFill(id: id) else { return }
            print("[StreamCache] auto-fill while playing for \(id)")
            StreamCacheFiller.shared.startFill(id: id)
        }
    }

    func releasePlaybackUrl(playbackUrl: String) {
        StreamCacheProxy.shared.releasePlaybackURL(playbackUrl)
    }

    func getConfigJson() -> String {
        StreamCacheStore.shared.configJSON()
    }

    func setEnabled(enabled: Bool) {
        StreamCacheStore.shared.setEnabled(enabled)
    }

    func setMaxBytes(maxBytes: Int64) {
        StreamCacheStore.shared.setMaxBytes(maxBytes)
    }

    func setAutoFillWhilePlaying(enabled: Bool) {
        StreamCacheStore.shared.setAutoFillWhilePlaying(enabled)
    }

    func setLookAheadMinutes(minutes: Int32) {
        StreamCacheStore.shared.setLookAheadMinutes(Int(minutes))
    }

    func listEntriesJson() -> String {
        StreamCacheStore.shared.listEntriesJSON()
    }

    func deleteEntry(id: String) {
        StreamCacheFiller.shared.cancelFill(id: id)
        StreamCacheStore.shared.deleteEntry(id: id)
    }

    func clearAll() {
        StreamCacheFiller.shared.cancelAll()
        StreamCacheStore.shared.clearAll()
    }

    func setPinned(id: String, pinned: Bool) {
        StreamCacheStore.shared.setPinned(id: id, pinned: pinned)
    }

    func startFill(id: String) {
        StreamCacheFiller.shared.startFill(id: id)
    }

    func cancelFill(id: String) {
        StreamCacheFiller.shared.cancelFill(id: id)
    }

    func exportEntry(id: String, destinationPath: String) -> String {
        StreamCacheStore.shared.exportEntry(id: id, destinationPath: destinationPath)
    }

    func setChangeListener(listener: StreamCacheChangeListener?) {
        StreamCacheNotifier.shared.setListener(listener)
    }
}
