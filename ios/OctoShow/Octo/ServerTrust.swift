import CryptoKit
import Foundation
import Security

/// How the app decides an https OctoPrint is the right one, and how it signs in to
/// whatever stands in front of it.
///
/// A Pi on the home network serves a certificate it signed itself, which no certificate
/// authority vouches for. So trust works the way an SSH client's does, as on the Echo Show:
/// during setup (`learning`) the certificate on offer is accepted and its fingerprint noted;
/// from then on only that exact certificate (`pinned`) is accepted, and a different one is
/// refused rather than quietly trusted. Certificates that chain to a public authority, as
/// Cloudflare's and Tailscale's do, are accepted as usual.
///
/// It is also the session delegate, so it keeps the access key from following a redirect
/// to another server, and answers a relay's password challenge with the route's own.
final class ServerTrust: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let pinned: String?
    private let learning: Bool
    private let base: URL
    private let user: String?
    private let password: String?
    private let lock = NSLock()
    private var _learned: String?
    private var _changed = false

    init(base: URL, pinned: String?, learning: Bool, user: String? = nil, password: String? = nil) {
        self.base = base
        self.pinned = pinned
        self.learning = learning
        self.user = user
        self.password = password
    }

    /// Fingerprint of a self-signed certificate accepted on first use, to be saved with the server.
    var learned: String? { lock.withLock { _learned } }
    /// Set when the server showed a certificate other than the one pinned at setup.
    var changed: Bool { lock.withLock { _changed } }

    func urlSession(
        _ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let space = challenge.protectionSpace
        switch space.authenticationMethod {
        case NSURLAuthenticationMethodServerTrust:
            guard let trust = space.serverTrust else { return completionHandler(.cancelAuthenticationChallenge, nil) }
            if decide(trust) {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        case NSURLAuthenticationMethodHTTPBasic, NSURLAuthenticationMethodDefault:
            // A relay (OctoEverywhere, Obico, a reverse proxy) asking for its password.
            if let user, let password, challenge.previousFailureCount == 0, space.host == base.host {
                completionHandler(.useCredential, URLCredential(user: user, password: password, persistence: .forSession))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        default:
            completionHandler(.performDefaultHandling, nil)
        }
    }

    /// The access key and a route's extra headers go to OctoPrint's own address and nowhere
    /// else: not to a server a redirect points at.
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let url = request.url, !Self.sameServer(url, base) else { return completionHandler(request) }
        var stripped = request
        stripped.setValue(nil, forHTTPHeaderField: OctoApi.keyHeader)
        completionHandler(stripped)
    }

    private func decide(_ trust: SecTrust) -> Bool {
        guard let leaf = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first else { return false }
        let print = Self.fingerprint(leaf)
        if let pinned, pinned == print {
            lock.withLock { _changed = false }
            return true
        }
        if SecTrustEvaluateWithError(trust, nil) { return true }
        if pinned != nil {
            lock.withLock { _changed = true }
            return false
        }
        guard learning else { return false }
        // First meeting with a self-signed server. Whichever certificate is met first is the one
        // kept: a different one turning up before setup is over is someone else answering.
        return lock.withLock {
            if let first = _learned, first != print { return false }
            _learned = print
            return true
        }
    }

    static func fingerprint(_ cert: SecCertificate) -> String {
        let der = SecCertificateCopyData(cert) as Data
        return SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    static func sameServer(_ a: URL, _ b: URL) -> Bool {
        a.scheme?.lowercased() == b.scheme?.lowercased() && a.host?.lowercased() == b.host?.lowercased()
            && effectivePort(a) == effectivePort(b)
    }

    static func effectivePort(_ u: URL) -> Int {
        if let p = u.port { return p }
        switch u.scheme?.lowercased() {
        case "https", "wss": return 443
        default: return 80
        }
    }
}
