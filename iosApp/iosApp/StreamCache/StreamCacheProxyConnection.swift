import Foundation
import Network

/// One client (mpv/ffmpeg) TCP connection to the proxy. Handles exactly one HTTP request and
/// then closes (`Connection: close`). All mutable state is confined to `queue`.
///
/// Serving the main file:
///  - total size unknown -> "probe" upstream GET `Range: bytes=<start>-` to learn size/mime,
///    then reuse that response as the first network segment;
///  - loop over the requested range: cached span -> read from disk in 512 KiB chunks;
///    missing span -> upstream GET for exactly that span, written to disk while forwarded.
/// Backpressure: the upstream task is suspended while too many bytes are queued on the socket.
final class StreamCacheProxyConnection: StreamCacheUpstreamReceiver {
    private enum FetchKind {
        /// First request when the total size is unknown.
        case probe
        /// Fetching a missing span of the main file (cached while forwarded).
        case segment
        /// Uncached pass-through (manifests, relative resources, servers ignoring Range).
        case passthrough
    }

    private final class Fetch {
        let task: URLSessionDataTask
        var kind: FetchKind
        let start: Int64
        /// Inclusive end of the span we want from this response (segment kind).
        var segmentEnd: Int64
        var suspended = false

        init(task: URLSessionDataTask, kind: FetchKind, start: Int64, segmentEnd: Int64) {
            self.task = task
            self.kind = kind
            self.start = start
            self.segmentEnd = segmentEnd
        }
    }

    private enum RangeRequest {
        case whole
        case from(Int64, Int64?)
        case suffix(Int64)
    }

    private static let readChunk: Int64 = 512 * 1024
    /// Keep at most this much disk data queued on the socket while pumping cached spans.
    private static let diskHighWater = 2 * 1024 * 1024
    /// Suspend the upstream task above this many queued bytes, resume below `resumeThreshold`.
    private static let suspendThreshold = 4 * 1024 * 1024
    private static let resumeThreshold = 1 * 1024 * 1024
    private static let maxRequestHeaderBytes = 64 * 1024

    private let connection: NWConnection
    private let queue: DispatchQueue
    private weak var proxy: StreamCacheProxy?
    private let store = StreamCacheStore.shared

    // MARK: State (queue-confined)

    private var requestBuffer = Data()
    private var closed = false
    private var finishing = false
    private var finalized = false
    private var headersSent = false
    private var queuedBytes = 0
    private var isHead = false
    private var servingMain = false
    private var session: StreamCacheSession?
    private var rangeRequest: RangeRequest = .whole
    private var rawRangeHeader: String?
    private var cacheWrites = true
    private var generation = 0
    private var totalBytes: Int64 = -1
    private var mimeType = "application/octet-stream"
    private var position: Int64 = 0
    private var endInclusive: Int64 = -1
    private var fetch: Fetch?

    init(connection: NWConnection, proxy: StreamCacheProxy) {
        self.connection = connection
        self.proxy = proxy
        self.queue = DispatchQueue(label: "com.nuvio.streamcache.connection", qos: .userInitiated)
    }

    // MARK: Lifecycle

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .failed(_), .cancelled:
                self.close()
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.async { [weak self] in
            self?.receiveRequest()
        }
    }

    /// Called by the proxy when the session is released.
    func cancelFromOutside() {
        queue.async { [weak self] in
            self?.close()
        }
    }

    /// Tears everything down: upstream task, socket, proxy bookkeeping. Idempotent.
    private func close() {
        guard !closed else { return }
        closed = true
        cancelFetch()
        connection.cancel()
        proxy?.connectionClosed(self)
    }

    // MARK: Request parsing

    private func receiveRequest() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] content, _, isComplete, error in
            guard let self = self, !self.closed else { return }
            if let content = content, !content.isEmpty {
                self.requestBuffer.append(content)
            }
            if let headerEnd = self.requestBuffer.range(of: Data([13, 10, 13, 10])) {
                let head = self.requestBuffer.subdata(in: self.requestBuffer.startIndex..<headerEnd.lowerBound)
                self.requestBuffer = Data()
                self.handleRequest(head)
                self.watchForDisconnect()
                return
            }
            if error != nil || isComplete || self.requestBuffer.count > StreamCacheProxyConnection.maxRequestHeaderBytes {
                self.close()
                return
            }
            self.receiveRequest()
        }
    }

    /// After the request, any EOF/error from the client means it went away (seek/stop).
    private func watchForDisconnect() {
        guard !closed else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] _, _, isComplete, error in
            guard let self = self, !self.closed else { return }
            if isComplete || error != nil {
                self.close()
            } else {
                self.watchForDisconnect()
            }
        }
    }

    private func handleRequest(_ head: Data) {
        guard let text = String(data: head, encoding: .utf8) ?? String(data: head, encoding: .isoLatin1) else {
            sendSimple(status: 400)
            return
        }
        let lines = text.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            sendSimple(status: 400)
            return
        }
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 2 else {
            sendSimple(status: 400)
            return
        }
        let method = String(parts[0]).uppercased()
        let target = String(parts[1])

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }

        guard method == "GET" || method == "HEAD" else {
            sendSimple(status: 405)
            return
        }
        isHead = method == "HEAD"
        rawRangeHeader = headers["range"]
        rangeRequest = StreamCacheProxyConnection.parseRange(rawRangeHeader)

        // Target: /s/<token>/<file name or relative resource>[?query]
        var path = target
        var query = ""
        if let questionMark = target.firstIndex(of: "?") {
            path = String(target[target.startIndex..<questionMark])
            query = String(target[questionMark...])
        }
        let components = path.split(separator: "/", omittingEmptySubsequences: false).map { String($0) }
        guard components.count >= 3, components[1] == "s",
              let proxy = proxy, let session = proxy.session(token: components[2]) else {
            sendSimple(status: 404)
            return
        }
        self.session = session
        proxy.associate(self, token: components[2])

        let restRaw = components.count > 3 ? components[3...].joined(separator: "/") : ""
        let restDecoded = restRaw.removingPercentEncoding ?? restRaw
        if restDecoded.isEmpty || restDecoded == session.fileName {
            serveMain(session)
        } else {
            // Relative reference (e.g. from a manifest): resolve against the upstream URL.
            guard let resolved = URL(string: restRaw + query, relativeTo: session.upstreamURL)?.absoluteURL else {
                sendSimple(status: 404)
                return
            }
            startPassthrough(url: resolved)
        }
    }

    // MARK: Main file

    private func serveMain(_ session: StreamCacheSession) {
        servingMain = true
        store.touch(id: session.entryId)
        guard let info = store.serveInfo(id: session.entryId) else {
            // Entry was deleted while playing: plain pass-through.
            startPassthrough(url: session.upstreamURL)
            return
        }
        generation = info.generation
        mimeType = info.mimeType ?? StreamCacheHTTP.mimeType(forFileName: session.fileName)
        totalBytes = info.totalBytes
        if totalBytes > 0 {
            if beginRangedResponse() {
                pump()
            }
            return
        }
        let probeStart: Int64
        switch rangeRequest {
        case .from(let start, _):
            probeStart = start
        default:
            probeStart = 0
        }
        startFetch(url: session.upstreamURL, kind: .probe, start: probeStart, segmentEnd: -1,
                   range: "bytes=\(probeStart)-", method: "GET")
    }

    /// Resolves the client range against `totalBytes` and sends the response head.
    /// Returns true when a body must follow.
    private func beginRangedResponse() -> Bool {
        guard let resolved = StreamCacheProxyConnection.resolve(rangeRequest, total: totalBytes) else {
            sendHead(status: 416, headers: [
                ("Content-Range", "bytes */\(totalBytes)"),
                ("Content-Length", "0"),
            ])
            finishResponse()
            return false
        }
        position = resolved.start
        endInclusive = resolved.end
        let length = resolved.end - resolved.start + 1
        var headers: [(String, String)] = [
            ("Content-Type", mimeType),
            ("Content-Length", "\(length)"),
            ("Accept-Ranges", "bytes"),
        ]
        let status: Int
        if case .whole = rangeRequest {
            status = 200
        } else {
            status = 206
            headers.append(("Content-Range", "bytes \(resolved.start)-\(resolved.end)/\(totalBytes)"))
        }
        sendHead(status: status, headers: headers)
        if isHead {
            finishResponse()
            return false
        }
        return true
    }

    /// Serves the requested range: cached spans from disk, missing spans via upstream.
    private func pump() {
        guard !closed, !finishing, servingMain, headersSent, fetch == nil, let session = session else { return }
        let id = session.entryId
        while queuedBytes < StreamCacheProxyConnection.diskHighWater {
            if position > endInclusive {
                finishResponse()
                return
            }
            if let cachedEnd = store.cachedEnd(id: id, containing: position) {
                let length = Int(min(StreamCacheProxyConnection.readChunk, cachedEnd - position, endInclusive + 1 - position))
                guard let data = store.read(id: id, offset: position, length: length), data.count == length else {
                    print("[StreamCache] disk read failed at \(position); closing connection")
                    close()
                    return
                }
                send(data)
                position += Int64(length)
                continue
            }
            // Missing: fetch up to the next cached span (or the requested end).
            let nextCached = store.nextCachedStart(id: id, after: position) ?? totalBytes
            let segmentEnd = min(endInclusive, nextCached - 1)
            startFetch(url: session.upstreamURL, kind: .segment, start: position, segmentEnd: segmentEnd,
                       range: "bytes=\(position)-\(segmentEnd)", method: "GET")
            return
        }
        // Socket buffer full; `didDrain` calls pump again.
    }

    // MARK: Pass-through

    private func startPassthrough(url: URL) {
        servingMain = false
        startFetch(url: url, kind: .passthrough, start: 0, segmentEnd: -1,
                   range: rawRangeHeader, method: isHead ? "HEAD" : "GET")
    }

    private func sendPassthroughHead(_ response: HTTPURLResponse) {
        var headers: [(String, String)] = []
        if let contentType = response.value(forHTTPHeaderField: "Content-Type") {
            headers.append(("Content-Type", contentType))
        }
        let encoded = response.value(forHTTPHeaderField: "Content-Encoding") != nil
        if response.expectedContentLength >= 0, !encoded {
            headers.append(("Content-Length", "\(response.expectedContentLength)"))
        }
        if let contentRange = response.value(forHTTPHeaderField: "Content-Range") {
            headers.append(("Content-Range", contentRange))
        }
        if let acceptRanges = response.value(forHTTPHeaderField: "Accept-Ranges") {
            headers.append(("Accept-Ranges", acceptRanges))
        }
        sendHead(status: response.statusCode, headers: headers)
        if isHead {
            cancelFetch()
            finishResponse()
        }
    }

    // MARK: Upstream

    private func startFetch(url: URL, kind: FetchKind, start: Int64, segmentEnd: Int64, range: String?, method: String) {
        guard !closed, let session = session else { return }
        let task = StreamCacheUpstream.shared.makeTask(
            url: url,
            headers: session.headers,
            range: range,
            method: method,
            receiver: self
        )
        fetch = Fetch(task: task, kind: kind, start: start, segmentEnd: segmentEnd)
        task.resume()
    }

    private func cancelFetch() {
        if let current = fetch {
            fetch = nil
            // Make sure a task paused by backpressure actually completes (so the upstream
            // client drops its reference to us); data delivered meanwhile is ignored.
            if current.suspended {
                current.suspended = false
                current.task.resume()
            }
            current.task.cancel()
        }
    }

    private func applyBackpressure(_ current: Fetch) {
        if !current.suspended, queuedBytes >= StreamCacheProxyConnection.suspendThreshold {
            current.suspended = true
            current.task.suspend()
        }
    }

    // StreamCacheUpstreamReceiver (delegate queue) -> hop to our queue. Ordering is preserved
    // because the delegate queue is serial and `queue` is serial.

    func upstreamTask(_ task: URLSessionTask, didReceiveResponse response: URLResponse) {
        queue.async { [weak self] in
            self?.handleUpstreamResponse(task: task, response: response)
        }
    }

    func upstreamTask(_ task: URLSessionTask, didReceiveData data: Data) {
        queue.async { [weak self] in
            self?.handleUpstreamData(task: task, data: data)
        }
    }

    func upstreamTask(_ task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async { [weak self] in
            self?.handleUpstreamCompletion(task: task, error: error)
        }
    }

    private func handleUpstreamResponse(task: URLSessionTask, response: URLResponse) {
        guard !closed, let current = fetch, current.task === task else { return }
        guard let http = response as? HTTPURLResponse else {
            cancelFetch()
            sendSimple(status: 502)
            return
        }
        switch current.kind {
        case .probe:
            handleProbeResponse(current, http)
        case .segment:
            handleSegmentResponse(current, http)
        case .passthrough:
            sendPassthroughHead(http)
        }
    }

    private func handleProbeResponse(_ current: Fetch, _ http: HTTPURLResponse) {
        let status = http.statusCode
        guard status == 200 || status == 206 else {
            print("[StreamCache] upstream answered HTTP \(status)")
            cancelFetch()
            sendSimple(status: (400...599).contains(status) ? status : 502)
            return
        }
        let contentType = http.value(forHTTPHeaderField: "Content-Type")
        if StreamCacheHTTP.isManifest(contentType: contentType) {
            current.kind = .passthrough
            servingMain = false
            sendPassthroughHead(http)
            return
        }
        var upstreamTotal: Int64 = -1
        var startMatches = false
        if status == 206, let contentRange = StreamCacheHTTP.parseContentRange(http.value(forHTTPHeaderField: "Content-Range")) {
            startMatches = contentRange.start == current.start
            upstreamTotal = contentRange.total ?? -1
        } else if status == 200 {
            // A 200 to `bytes=N-` with N > 0 means Range is not honoured: pass through.
            startMatches = current.start == 0
            upstreamTotal = current.start == 0 ? http.expectedContentLength : -1
        }
        let encoded = http.value(forHTTPHeaderField: "Content-Encoding").map { $0.lowercased() != "identity" } ?? false
        guard startMatches, upstreamTotal > 0, !encoded, let entryId = session?.entryId else {
            current.kind = .passthrough
            servingMain = false
            sendPassthroughHead(http)
            return
        }

        if let newGeneration = store.setTotal(id: entryId, totalBytes: upstreamTotal, mimeType: contentType) {
            generation = newGeneration
        } else {
            cacheWrites = false
        }
        totalBytes = upstreamTotal
        if let mime = StreamCacheStore.normalizedMime(contentType), mime != "application/octet-stream" {
            mimeType = mime
        }
        guard beginRangedResponse() else {
            cancelFetch()
            return
        }
        if position == current.start, store.cachedEnd(id: entryId, containing: position) == nil {
            // Reuse the probe response as the first missing segment.
            let nextCached = store.nextCachedStart(id: entryId, after: position) ?? totalBytes
            current.segmentEnd = min(endInclusive, nextCached - 1)
            current.kind = .segment
        } else {
            cancelFetch()
            pump()
        }
    }

    private func handleSegmentResponse(_ current: Fetch, _ http: HTTPURLResponse) {
        let status = http.statusCode
        var acceptable = false
        if status == 206, let contentRange = StreamCacheHTTP.parseContentRange(http.value(forHTTPHeaderField: "Content-Range")) {
            if let upstreamTotal = contentRange.total, upstreamTotal != totalBytes {
                print("[StreamCache] upstream size changed (\(totalBytes) -> \(upstreamTotal)); restarting entry")
                if let entryId = session?.entryId {
                    _ = store.setTotal(id: entryId, totalBytes: upstreamTotal, mimeType: nil)
                }
                close()
                return
            }
            acceptable = contentRange.start == current.start
        } else if status == 200 {
            acceptable = current.start == 0 && http.expectedContentLength == totalBytes
        }
        if !acceptable {
            print("[StreamCache] unexpected upstream response HTTP \(status) for segment at \(current.start)")
            close()
        }
    }

    private func handleUpstreamData(task: URLSessionTask, data: Data) {
        guard !closed, let current = fetch, current.task === task, !data.isEmpty else { return }
        switch current.kind {
        case .probe:
            // Response not processed yet (cannot happen: callbacks are ordered). Ignore.
            return
        case .passthrough:
            send(data)
            applyBackpressure(current)
        case .segment:
            let remaining = current.segmentEnd - position + 1
            guard remaining > 0 else { return }
            let chunk: Data = Int64(data.count) > remaining ? Data(data.prefix(Int(remaining))) : data
            if cacheWrites, let entryId = session?.entryId {
                store.write(id: entryId, generation: generation, offset: position, data: chunk)
            }
            send(chunk)
            position += Int64(chunk.count)
            if position > current.segmentEnd {
                // Reached the next cached span (or the requested end): continue from disk.
                cancelFetch()
                pump()
            } else {
                applyBackpressure(current)
            }
        }
    }

    private func handleUpstreamCompletion(task: URLSessionTask, error: Error?) {
        guard !closed, let current = fetch, current.task === task else { return }
        fetch = nil
        switch current.kind {
        case .probe:
            print("[StreamCache] upstream probe failed: \(error?.localizedDescription ?? "no response")")
            if headersSent {
                close()
            } else {
                sendSimple(status: 502)
            }
        case .segment:
            if position > current.segmentEnd {
                pump()
            } else if position > current.start {
                // Upstream ended early (server-capped range, idle timeout while paused by
                // backpressure, ...) but made progress: continue with a fresh request.
                if let error = error {
                    print("[StreamCache] upstream segment interrupted at \(position): \(error.localizedDescription); resuming")
                }
                pump()
            } else {
                if let error = error {
                    print("[StreamCache] upstream segment failed: \(error.localizedDescription)")
                }
                close()
            }
        case .passthrough:
            if !headersSent {
                sendSimple(status: 502)
            } else if error != nil {
                close()
            } else {
                finishResponse()
            }
        }
    }

    // MARK: Sending

    private func sendHead(status: Int, headers: [(String, String)]) {
        guard !headersSent, !closed else { return }
        headersSent = true
        var text = "HTTP/1.1 \(status) \(StreamCacheHTTP.reasonPhrase(status))\r\n"
        for (name, value) in headers {
            text += "\(name): \(value)\r\n"
        }
        text += "Connection: close\r\n\r\n"
        send(Data(text.utf8))
    }

    /// Body-less response (errors). Closes if a response was already started.
    private func sendSimple(status: Int) {
        if headersSent {
            close()
            return
        }
        sendHead(status: status, headers: [("Content-Length", "0")])
        finishResponse()
    }

    private func send(_ data: Data) {
        guard !closed, !data.isEmpty else { return }
        let count = data.count
        queuedBytes += count
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            self.queuedBytes -= count
            if error != nil {
                self.close()
                return
            }
            self.didDrain()
        })
    }

    private func didDrain() {
        guard !closed else { return }
        if let current = fetch, current.suspended, queuedBytes <= StreamCacheProxyConnection.resumeThreshold {
            current.suspended = false
            current.task.resume()
        }
        if finishing {
            if queuedBytes == 0 {
                finalizeConnection()
            }
            return
        }
        if servingMain, headersSent, fetch == nil {
            pump()
        }
    }

    /// Marks the response complete; the socket is closed once everything queued was sent.
    private func finishResponse() {
        guard !closed else { return }
        finishing = true
        if queuedBytes == 0 {
            finalizeConnection()
        }
    }

    private func finalizeConnection() {
        guard !closed, !finalized else { return }
        finalized = true
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.close()
        })
    }

    // MARK: Range helpers

    private static func parseRange(_ header: String?) -> RangeRequest {
        guard let header = header?.trimmingCharacters(in: .whitespaces).lowercased(), header.hasPrefix("bytes=") else {
            return .whole
        }
        let specs = header.dropFirst(6).split(separator: ",")
        guard let firstSpec = specs.first else { return .whole }
        let spec = firstSpec.trimmingCharacters(in: .whitespaces)
        if spec.hasPrefix("-") {
            if let count = Int64(spec.dropFirst()) {
                return .suffix(count)
            }
            return .whole
        }
        let parts = spec.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2,
              let start = Int64(parts[0].trimmingCharacters(in: .whitespaces)),
              start >= 0 else { return .whole }
        let endText = parts[1].trimmingCharacters(in: .whitespaces)
        if endText.isEmpty {
            return .from(start, nil)
        }
        guard let end = Int64(endText), end >= start else { return .whole }
        return .from(start, end)
    }

    /// Inclusive byte range to serve, or nil for 416.
    private static func resolve(_ request: RangeRequest, total: Int64) -> (start: Int64, end: Int64)? {
        guard total > 0 else { return nil }
        switch request {
        case .whole:
            return (0, total - 1)
        case .from(let start, let end):
            guard start < total else { return nil }
            return (start, min(end ?? (total - 1), total - 1))
        case .suffix(let count):
            guard count > 0 else { return nil }
            return (max(0, total - count), total - 1)
        }
    }
}
