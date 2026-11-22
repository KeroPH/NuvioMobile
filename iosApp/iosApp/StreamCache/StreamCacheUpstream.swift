import Foundation

/// Receives streaming callbacks for one upstream task. Callbacks arrive on the shared
/// (serial) URLSession delegate queue; receivers hop to their own queue.
protocol StreamCacheUpstreamReceiver: AnyObject {
    func upstreamTask(_ task: URLSessionTask, didReceiveResponse response: URLResponse)
    func upstreamTask(_ task: URLSessionTask, didReceiveData data: Data)
    func upstreamTask(_ task: URLSessionTask, didCompleteWithError error: Error?)
}

/// Shared streaming HTTP client for the proxy and the filler: one URLSession (default config,
/// no URL cache), delegate-based so bodies are streamed, never buffered whole.
final class StreamCacheUpstream: NSObject, URLSessionDataDelegate {
    static let shared = StreamCacheUpstream()

    /// Headers never forwarded from the player's header map (we set our own / they are hop-by-hop).
    private static let excludedHeaders: Set<String> = [
        "range", "host", "content-length", "connection", "accept-encoding",
        "transfer-encoding", "if-range", "keep-alive", "upgrade",
    ]

    private let lock = NSLock()
    private var receivers: [Int: StreamCacheUpstreamReceiver] = [:]

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60 * 60 * 24
        configuration.httpMaximumConnectionsPerHost = 16
        let queue = OperationQueue()
        queue.name = "com.nuvio.streamcache.upstream"
        queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }()

    private override init() {
        super.init()
    }

    /// Creates a suspended data task; the caller stores it and then calls `resume()`.
    /// The receiver is retained until the task completes.
    func makeTask(url: URL, headers: [String: String], range: String?, method: String,
                  receiver: StreamCacheUpstreamReceiver) -> URLSessionDataTask {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = method
        for (name, value) in headers where !StreamCacheUpstream.excludedHeaders.contains(name.lowercased()) {
            request.setValue(value, forHTTPHeaderField: name)
        }
        // Byte offsets must match the stored file: never let the transport decode content.
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if let range = range {
            request.setValue(range, forHTTPHeaderField: "Range")
        }
        lock.lock()
        let task = session.dataTask(with: request)
        receivers[task.taskIdentifier] = receiver
        lock.unlock()
        return task
    }

    private func receiver(for task: URLSessionTask) -> StreamCacheUpstreamReceiver? {
        lock.lock()
        defer { lock.unlock() }
        return receivers[task.taskIdentifier]
    }

    // MARK: URLSessionDataDelegate

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Keep Range and the player's headers on the redirected request (CDN redirects).
        var redirected = request
        if let original = task.originalRequest?.allHTTPHeaderFields {
            for (name, value) in original where redirected.value(forHTTPHeaderField: name) == nil {
                redirected.setValue(value, forHTTPHeaderField: name)
            }
        }
        completionHandler(redirected)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        receiver(for: dataTask)?.upstreamTask(dataTask, didReceiveResponse: response)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        receiver(for: dataTask)?.upstreamTask(dataTask, didReceiveData: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let receiver = receivers.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        receiver?.upstreamTask(task, didCompleteWithError: error)
    }
}

/// Small HTTP helpers shared by the proxy and the filler.
enum StreamCacheHTTP {
    /// Parses `Content-Range: bytes s-e/total` (total nil when `*`).
    static func parseContentRange(_ value: String?) -> (start: Int64, end: Int64, total: Int64?)? {
        guard let raw = value?.trimmingCharacters(in: .whitespaces).lowercased(), raw.hasPrefix("bytes") else {
            return nil
        }
        let text = String(raw.dropFirst(5)).trimmingCharacters(in: CharacterSet(charactersIn: " ="))
        let halves = text.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard halves.count == 2 else { return nil }
        let bounds = halves[0].split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard bounds.count == 2,
              let start = Int64(bounds[0].trimmingCharacters(in: .whitespaces)),
              let end = Int64(bounds[1].trimmingCharacters(in: .whitespaces)) else { return nil }
        let total = Int64(halves[1].trimmingCharacters(in: .whitespaces))
        return (start, end, total)
    }

    /// HLS / DASH manifests are never cached.
    static func isManifest(contentType: String?) -> Bool {
        guard let type = contentType?.lowercased() else { return false }
        return type.contains("mpegurl") || type.contains("dash+xml")
    }

    static func reasonPhrase(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 206: return "Partial Content"
        case 301: return "Moved Permanently"
        case 302: return "Found"
        case 304: return "Not Modified"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 410: return "Gone"
        case 416: return "Range Not Satisfiable"
        case 429: return "Too Many Requests"
        case 500: return "Internal Server Error"
        case 502: return "Bad Gateway"
        case 503: return "Service Unavailable"
        default: return "Status"
        }
    }

    static func mimeType(forFileName fileName: String) -> String {
        switch (fileName as NSString).pathExtension.lowercased() {
        case "mkv": return "video/x-matroska"
        case "mp4", "m4v": return "video/mp4"
        case "webm": return "video/webm"
        case "mov": return "video/quicktime"
        case "ts", "m2ts", "mts": return "video/mp2t"
        case "avi": return "video/x-msvideo"
        default: return "application/octet-stream"
        }
    }
}
