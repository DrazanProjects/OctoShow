import Foundation
import UIKit

/// Reads a webcam's MJPEG stream (multipart/x-mixed-replace) as pictures, at most `maxFps`
/// a second. The stream ends with an error when the camera stalls or drops; the caller
/// decides when to try again. https cameras behind the Pi's own certificate are checked
/// the same way OctoPrint is.
final class MjpegStream: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let trust: ServerTrust
    private let minGap: TimeInterval
    private var buffer = Data()
    private var last = Date.distantPast
    private var continuation: AsyncThrowingStream<UIImage, Error>.Continuation?
    private var session: URLSession?

    private init(trust: ServerTrust, maxFps: Int) {
        self.trust = trust
        minGap = 1 / Double(max(maxFps, 1))
    }

    static func frames(api: OctoApi, url: URL, maxFps: Int = 12) -> AsyncThrowingStream<UIImage, Error> {
        let reader = MjpegStream(trust: api.trust, maxFps: maxFps)
        return AsyncThrowingStream { continuation in
            reader.continuation = continuation
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 12
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            let session = URLSession(configuration: config, delegate: reader, delegateQueue: nil)
            reader.session = session
            session.dataTask(with: api.request(url)).resume()
            continuation.onTermination = { _ in session.invalidateAndCancel() }
        }
    }

    /// A still every `every` seconds: what to show over a slow or metered connection, where a
    /// camera pushing megabytes a second has no business.
    static func stills(api: OctoApi, url: URL, every: Duration = .seconds(2)) -> AsyncThrowingStream<UIImage, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    while !Task.isCancelled {
                        let (data, r) = try await api.raw(api.request(url, timeout: 10))
                        guard r.statusCode == 200 else { throw URLError(.badServerResponse) }
                        if let image = UIImage(data: data) { continuation.yield(image) }
                        try await Task.sleep(for: every)
                    }
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            continuation?.finish(throwing: URLError(.badServerResponse))
            completionHandler(.cancel)
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        buffer.append(data)
        // Pull every whole JPEG out of what has come; keep the newest, skip the rest.
        var newest: Data?
        while let start = buffer.firstRange(of: Self.soi),
              let end = buffer[start.upperBound...].firstRange(of: Self.eoi) {
            newest = buffer[start.lowerBound..<end.upperBound]
            buffer.removeSubrange(..<end.upperBound)
        }
        if buffer.count > Self.maxFrame { buffer.removeAll() }
        guard let jpeg = newest, Date().timeIntervalSince(last) >= minGap,
              let image = UIImage(data: jpeg)?.preparingForDisplay() else { return }
        last = Date()
        continuation?.yield(image)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        continuation?.finish(throwing: error ?? URLError(.networkConnectionLost))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        trust.urlSession(session, task: task, didReceive: challenge, completionHandler: completionHandler)
    }

    private static let soi = Data([0xFF, 0xD8])
    private static let eoi = Data([0xFF, 0xD9])
    private static let maxFrame = 8 * 1024 * 1024
}
