import Foundation
import Network

/// One `preparePlaybackUrl` / `entryPlaybackUrl` call: maps a random token to an entry + upstream request.
final class StreamCacheSession {
    let token: String
    let entryId: String
    /// nil when an entry is played from the cache without a stored upstream URL: cached ranges
    /// are served, requests touching uncached ranges fail.
    let upstreamURL: URL?
    let headers: [String: String]
    /// Entry file name (the last path component of the playback URL).
    let fileName: String

    init(token: String, entryId: String, upstreamURL: URL?, headers: [String: String], fileName: String) {
        self.token = token
        self.entryId = entryId
        self.upstreamURL = upstreamURL
        self.headers = headers
        self.fileName = fileName
    }
}

/// Loopback HTTP/1.1 server (Network.framework) serving `http://127.0.0.1:<port>/s/<token>/<file>`.
/// Request handling lives in `StreamCacheProxyConnection`.
final class StreamCacheProxy {
    static let shared = StreamCacheProxy()

    private let lock = NSLock()
    /// Serialises listener (re)starts; never taken while `lock` is held.
    private let startLock = NSLock()
    private var listener: NWListener?
    private var port: UInt16 = 0
    /// Last successfully bound port; reused on restart so existing playback URLs keep working.
    private var lastPort: UInt16 = 0
    private var sessions: [String: StreamCacheSession] = [:]
    /// `file://` playback URLs handed out for complete entries (`entryPlaybackUrl`), keyed by the
    /// returned URL string: entry id + number of players currently using it. Each one holds an
    /// "active" mark on the entry (no eviction/export) until `releasePlaybackURL`.
    private var fileSessions: [String: (entryId: String, count: Int)] = [:]
    private var connections: [ObjectIdentifier: StreamCacheProxyConnection] = [:]
    private var connectionTokens: [ObjectIdentifier: String] = [:]
    private let listenerQueue = DispatchQueue(label: "com.nuvio.streamcache.listener")

    private init() {}

    // MARK: Listener

    /// Returns the listening port, (re)starting the listener if needed. Blocks ≤ ~1.5 s.
    func ensureListening() -> UInt16? {
        startLock.lock()
        defer { startLock.unlock() }

        lock.lock()
        if listener != nil, port != 0 {
            let current = port
            lock.unlock()
            return current
        }
        let stale = listener
        listener = nil
        port = 0
        let preferred = lastPort
        lock.unlock()
        stale?.cancel()

        if preferred != 0, let reused = startListener(requestedPort: preferred, timeout: 0.5) {
            return reused
        }
        return startListener(requestedPort: 0, timeout: 1.0)
    }

    /// Restarts the listener in the background when sessions exist (e.g. after returning to
    /// the foreground, when iOS may have torn down the socket).
    func restartIfNeeded() {
        guard hasSessions else { return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            _ = self?.ensureListening()
        }
    }

    private func startListener(requestedPort: UInt16, timeout: TimeInterval) -> UInt16? {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let endpointPort: NWEndpoint.Port
        if requestedPort == 0 {
            endpointPort = .any
        } else {
            endpointPort = NWEndpoint.Port(rawValue: requestedPort) ?? .any
        }
        // Bind to loopback only: nothing outside the device can reach the proxy.
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(IPv4Address.loopback), port: endpointPort)

        let newListener: NWListener
        do {
            newListener = try NWListener(using: parameters)
        } catch {
            print("[StreamCache] proxy listener create failed: \(error)")
            return nil
        }

        let readySignal = DispatchSemaphore(value: 0)
        newListener.stateUpdateHandler = { [weak self, weak newListener] state in
            guard let self = self, let current = newListener else {
                readySignal.signal()
                return
            }
            switch state {
            case .ready:
                self.listenerBecameReady(current)
                readySignal.signal()
            case .failed(let error):
                print("[StreamCache] proxy listener failed: \(error)")
                self.listenerStopped(current)
                current.cancel()
                readySignal.signal()
            case .cancelled:
                self.listenerStopped(current)
                readySignal.signal()
            default:
                break
            }
        }
        newListener.newConnectionHandler = { [weak self] connection in
            guard let self = self else {
                connection.cancel()
                return
            }
            self.accept(connection)
        }

        lock.lock()
        listener = newListener
        port = 0
        lock.unlock()

        newListener.start(queue: listenerQueue)
        _ = readySignal.wait(timeout: .now() + timeout)

        lock.lock()
        let isReady = listener === newListener && port != 0
        let boundPort = port
        if !isReady, listener === newListener {
            listener = nil
            port = 0
        }
        lock.unlock()

        guard isReady else {
            newListener.stateUpdateHandler = nil
            newListener.cancel()
            return nil
        }
        print("[StreamCache] proxy listening on 127.0.0.1:\(boundPort)")
        return boundPort
    }

    private func listenerBecameReady(_ readyListener: NWListener) {
        lock.lock()
        if listener === readyListener, let assigned = readyListener.port?.rawValue {
            port = assigned
            lastPort = assigned
        }
        lock.unlock()
    }

    private func listenerStopped(_ stoppedListener: NWListener) {
        lock.lock()
        if listener === stoppedListener {
            listener = nil
            port = 0
        }
        lock.unlock()
    }

    // MARK: Sessions

    var hasSessions: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !sessions.isEmpty
    }

    /// Registers a direct-file playback URL (complete entry) and marks the entry active.
    /// Pair with `releasePlaybackURL(fileURLString)`.
    func registerFileSession(entryId: String, fileURLString: String) {
        lock.lock()
        if let existing = fileSessions[fileURLString] {
            fileSessions[fileURLString] = (entryId: existing.entryId, count: existing.count + 1)
        } else {
            fileSessions[fileURLString] = (entryId: entryId, count: 1)
        }
        lock.unlock()
        StreamCacheStore.shared.markActive(id: entryId, delta: 1)
    }

    func registerSession(entryId: String, upstreamURL: URL?, headers: [String: String], fileName: String) -> StreamCacheSession {
        let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let session = StreamCacheSession(
            token: token,
            entryId: entryId,
            upstreamURL: upstreamURL,
            headers: headers,
            fileName: fileName
        )
        lock.lock()
        sessions[token] = session
        lock.unlock()
        StreamCacheStore.shared.markActive(id: entryId, delta: 1)
        return session
    }

    func session(token: String) -> StreamCacheSession? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[token]
    }

    /// `http://127.0.0.1:<port>/s/<token>/<percent-encoded file name>`
    func playbackURL(port: UInt16, session: StreamCacheSession) -> String {
        var allowed = CharacterSet()
        allowed.insert(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        let encodedName = session.fileName.addingPercentEncoding(withAllowedCharacters: allowed) ?? "video"
        return "http://127.0.0.1:\(port)/s/\(session.token)/\(encodedName)"
    }

    /// Extracts the session token from a playback URL produced by `playbackURL`.
    static func token(fromPlaybackURL playbackURL: String) -> String? {
        guard let url = URL(string: playbackURL), let host = url.host,
              host == "127.0.0.1" || host == "localhost" else { return nil }
        let components = url.path.split(separator: "/", omittingEmptySubsequences: true).map { String($0) }
        guard components.count >= 2, components[0] == "s" else { return nil }
        return components[1]
    }

    /// Removes the session, cancels its connections (and their upstream tasks), flushes meta.
    func releasePlaybackURL(_ playbackURL: String) {
        lock.lock()
        var releasedFileEntry: String?
        if let fileSession = fileSessions[playbackURL] {
            releasedFileEntry = fileSession.entryId
            if fileSession.count <= 1 {
                fileSessions.removeValue(forKey: playbackURL)
            } else {
                fileSessions[playbackURL] = (entryId: fileSession.entryId, count: fileSession.count - 1)
            }
        }
        lock.unlock()
        if let entryId = releasedFileEntry {
            StreamCacheStore.shared.markActive(id: entryId, delta: -1)
            return
        }
        guard let token = StreamCacheProxy.token(fromPlaybackURL: playbackURL) else { return }
        lock.lock()
        let removed = sessions.removeValue(forKey: token)
        var toCancel: [StreamCacheProxyConnection] = []
        for (key, value) in connectionTokens where value == token {
            if let connection = connections[key] {
                toCancel.append(connection)
            }
        }
        lock.unlock()
        for connection in toCancel {
            connection.cancelFromOutside()
        }
        if let session = removed {
            StreamCacheStore.shared.markActive(id: session.entryId, delta: -1)
        }
    }

    // MARK: Connections

    private func accept(_ nwConnection: NWConnection) {
        let connection = StreamCacheProxyConnection(connection: nwConnection, proxy: self)
        lock.lock()
        connections[ObjectIdentifier(connection)] = connection
        lock.unlock()
        connection.start()
    }

    /// Links a connection to its session token. Returns false (without linking) if the session
    /// was released meanwhile, so the caller can reject the request instead of serving an orphan.
    @discardableResult
    func associate(_ connection: StreamCacheProxyConnection, token: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard sessions[token] != nil else { return false }
        connectionTokens[ObjectIdentifier(connection)] = token
        return true
    }

    func connectionClosed(_ connection: StreamCacheProxyConnection) {
        let key = ObjectIdentifier(connection)
        lock.lock()
        connections.removeValue(forKey: key)
        connectionTokens.removeValue(forKey: key)
        lock.unlock()
    }
}
