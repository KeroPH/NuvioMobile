import Foundation

/// "Finish download": fetches the missing spans of an entry sequentially in the background
/// (foreground URLSession; stops when the app is suspended and fails/retries on resume).
final class StreamCacheFiller {
    static let shared = StreamCacheFiller()

    private let lock = NSLock()
    private var jobs: [String: StreamCacheFillJob] = [:]

    private init() {}

    func startFill(id: String) {
        lock.lock()
        if jobs[id] != nil {
            lock.unlock()
            return
        }
        guard StreamCacheStore.shared.beginFill(id: id) else {
            lock.unlock()
            return
        }
        let job = StreamCacheFillJob(id: id)
        jobs[id] = job
        lock.unlock()
        print("[StreamCache] fill started for \(id)")
        job.start()
    }

    func cancelFill(id: String) {
        lock.lock()
        let job = jobs.removeValue(forKey: id)
        lock.unlock()
        guard let cancelled = job else { return }
        cancelled.cancel()
        StreamCacheStore.shared.endFill(id: id, error: nil)
    }

    func cancelAll() {
        lock.lock()
        let all = jobs
        jobs.removeAll()
        lock.unlock()
        for (id, job) in all {
            job.cancel()
            StreamCacheStore.shared.endFill(id: id, error: nil)
        }
    }

    fileprivate func jobFinished(_ job: StreamCacheFillJob, error: String?) {
        lock.lock()
        let isCurrent = jobs[job.id] === job
        if isCurrent {
            jobs.removeValue(forKey: job.id)
        }
        lock.unlock()
        guard isCurrent else { return }
        if let error = error {
            print("[StreamCache] fill for \(job.id) stopped: \(error)")
        } else {
            print("[StreamCache] fill for \(job.id) complete")
        }
        StreamCacheStore.shared.endFill(id: job.id, error: error)
    }
}

/// One fill operation. Requests ≤ 8 MiB spans one after another and streams them to disk.
/// All state is confined to `queue`.
final class StreamCacheFillJob: StreamCacheUpstreamReceiver {
    private static let chunkSize: Int64 = 8 * 1024 * 1024
    private static let maxRetries = 3
    static let expiredMessage = "Link expired — play it again to refresh the link"

    let id: String
    private let queue: DispatchQueue
    private let store = StreamCacheStore.shared

    private var cancelled = false
    private var finished = false
    private var task: URLSessionDataTask?
    private var requestStart: Int64 = 0
    private var requestEnd: Int64 = 0
    private var position: Int64 = 0
    private var generation = 0
    private var knownTotal: Int64 = -1
    private var responseAccepted = false
    private var failure: String?
    private var restartNeeded = false
    private var retries = 0

    init(id: String) {
        self.id = id
        self.queue = DispatchQueue(label: "com.nuvio.streamcache.fill", qos: .utility)
    }

    func start() {
        queue.async { [weak self] in
            self?.requestNext()
        }
    }

    func cancel() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.cancelled = true
            self.task?.cancel()
            self.task = nil
        }
    }

    private func finish(_ error: String?) {
        guard !finished else { return }
        finished = true
        task = nil
        StreamCacheFiller.shared.jobFinished(self, error: error)
    }

    private func requestNext() {
        guard !cancelled, !finished else { return }
        guard store.hasEnoughFreeSpace() else {
            finish("Not enough free storage space")
            return
        }
        guard let source = store.fillSource(id: id) else {
            finish("Cache entry no longer exists")
            return
        }
        knownTotal = source.totalBytes
        generation = source.generation
        let start: Int64
        let end: Int64
        if source.totalBytes > 0 {
            guard let gap = source.gap else {
                finish(nil)
                return
            }
            start = gap.start
            end = min(gap.end, gap.start + StreamCacheFillJob.chunkSize) - 1
        } else {
            start = 0
            end = StreamCacheFillJob.chunkSize - 1
        }
        requestStart = start
        requestEnd = end
        position = start
        responseAccepted = false
        failure = nil
        restartNeeded = false
        let newTask = StreamCacheUpstream.shared.makeTask(
            url: source.url,
            headers: source.headers,
            range: "bytes=\(start)-\(end)",
            method: "GET",
            receiver: self
        )
        task = newTask
        newTask.resume()
    }

    // MARK: StreamCacheUpstreamReceiver

    func upstreamTask(_ task: URLSessionTask, didReceiveResponse response: URLResponse) {
        queue.async { [weak self] in
            self?.handleResponse(task: task, response: response)
        }
    }

    func upstreamTask(_ task: URLSessionTask, didReceiveData data: Data) {
        queue.async { [weak self] in
            self?.handleData(task: task, data: data)
        }
    }

    func upstreamTask(_ task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async { [weak self] in
            self?.handleCompletion(task: task, error: error)
        }
    }

    private func handleResponse(task: URLSessionTask, response: URLResponse) {
        guard !cancelled, let current = self.task, current === task else { return }
        guard let http = response as? HTTPURLResponse else {
            failure = "Unexpected server response"
            current.cancel()
            return
        }
        let status = http.statusCode
        if status != 206 {
            switch status {
            case 401, 403, 410:
                failure = StreamCacheFillJob.expiredMessage
            case 404:
                failure = "Link no longer available (HTTP 404) — play it again to refresh the link"
            case 200:
                failure = "The server does not support resuming downloads"
            default:
                failure = "Server error (HTTP \(status))"
            }
            current.cancel()
            return
        }
        guard let contentRange = StreamCacheHTTP.parseContentRange(http.value(forHTTPHeaderField: "Content-Range")),
              contentRange.start == requestStart else {
            failure = "Unexpected server response"
            current.cancel()
            return
        }
        if let total = contentRange.total, total != knownTotal {
            guard let newGeneration = store.setTotal(
                id: id,
                totalBytes: total,
                mimeType: http.value(forHTTPHeaderField: "Content-Type")
            ) else {
                failure = "Cache entry no longer exists"
                current.cancel()
                return
            }
            let sizeChanged = knownTotal > 0
            knownTotal = total
            generation = newGeneration
            if sizeChanged {
                // Entry was reset; start over from the first gap.
                restartNeeded = true
                current.cancel()
                return
            }
        }
        guard knownTotal > 0 else {
            // Without a total size the gaps can't be computed (would refetch [0, 8 MiB) forever).
            failure = "The server did not report the file size"
            current.cancel()
            return
        }
        responseAccepted = true
    }

    private func handleData(task: URLSessionTask, data: Data) {
        guard !cancelled, responseAccepted, let current = self.task, current === task, !data.isEmpty else { return }
        let remaining = requestEnd - position + 1
        guard remaining > 0 else { return }
        let chunk: Data = Int64(data.count) > remaining ? Data(data.prefix(Int(remaining))) : data
        if !store.write(id: id, generation: generation, offset: position, data: chunk) {
            if !store.entryExists(id: id) {
                failure = "Cache entry no longer exists"
            } else if !store.hasEnoughFreeSpace() {
                failure = "Not enough free storage space"
            } else if let info = store.serveInfo(id: id), info.generation != generation {
                restartNeeded = true
            } else {
                failure = "Could not write to storage"
            }
            current.cancel()
            return
        }
        position += Int64(chunk.count)
    }

    private func handleCompletion(task: URLSessionTask, error: Error?) {
        guard let current = self.task, current === task else { return }
        self.task = nil
        guard !cancelled, !finished else { return }
        if restartNeeded {
            restartNeeded = false
            requestNext()
            return
        }
        if let failure = failure {
            finish(failure)
            return
        }
        let madeProgress = position > requestStart
        if error != nil || !responseAccepted || !madeProgress {
            if retries < StreamCacheFillJob.maxRetries {
                retries += 1
                let delay = Double(retries) * 2.0
                queue.asyncAfter(deadline: .now() + delay) { [weak self] in
                    self?.requestNext()
                }
                return
            }
            finish(error?.localizedDescription ?? "Download failed")
            return
        }
        retries = 0
        requestNext()
    }
}
