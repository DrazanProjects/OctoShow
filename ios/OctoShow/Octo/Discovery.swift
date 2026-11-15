import Foundation
import Network

/// An OctoPrint instance announced on the local network.
struct FoundServer: Identifiable, Hashable {
    var name: String
    /// "host:port", as announced; the scheme is worked out when connecting.
    var address: String
    var id: String { address }
}

/// Lists the OctoPrints announcing themselves over Bonjour (`_octoprint._tcp`), as the
/// Echo Show does with its own mDNS. iOS resolves `.local` names itself, so a found
/// server's address can be its host name and keeps working if the router moves the Pi.
@MainActor
final class Discovery {
    private var browser: NWBrowser?
    private var resolving: [NWBrowser.Result: NWConnection] = [:]
    var onFound: ((FoundServer) -> Void)?

    func start() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjour(type: "_octoprint._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in
                for r in results { self?.resolve(r) }
            }
        }
        b.start(queue: .main)
        browser = b
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolving.values.forEach { $0.cancel() }
        resolving.removeAll()
    }

    /// A service name isn't an address: connect to it briefly to learn the host and port.
    /// IPv4, so the address is one a person can read and type.
    private func resolve(_ result: NWBrowser.Result) {
        guard resolving[result] == nil, case let .service(name, _, _, _) = result.endpoint else { return }
        let params = NWParameters.tcp
        (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let c = NWConnection(to: result.endpoint, using: params)
        resolving[result] = c
        c.stateUpdateHandler = { [weak self] state in
            guard case .ready = state else { return }
            let remote = c.currentPath?.remoteEndpoint
            c.cancel()
            guard case let .hostPort(host, port) = remote else { return }
            var h: String
            switch host {
            case .name(let n, _): h = n
            case .ipv4(let a): h = "\(a)"
            case .ipv6(let a): h = "[\("\(a)".split(separator: "%").first ?? "")]"
            @unknown default: return
            }
            if let pct = h.firstIndex(of: "%"), !h.hasPrefix("[") { h = String(h[..<pct]) }
            let address = port.rawValue == 80 ? h : "\(h):\(port.rawValue)"
            Task { @MainActor in self?.onFound?(FoundServer(name: name, address: address)) }
        }
        c.start(queue: .main)
    }
}
