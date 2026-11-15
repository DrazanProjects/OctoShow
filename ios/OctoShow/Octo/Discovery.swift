import dnssd
import Foundation
import Network

/// An OctoPrint instance announced on the local network.
struct FoundServer: Identifiable, Hashable {
    enum State: Hashable {
        /// Knocking on the announced port, and on OctoPrint's usual ones.
        case checking
        /// Something answered as OctoPrint at `address`.
        case ready
        /// Nothing answered as OctoPrint anywhere it was looked for.
        case silent
    }

    /// The name it announced, tidied: "OctoPrint instance on octopi" becomes "octopi".
    var name: String
    /// The host name it announced, such as "octopi.local". By name rather than address, so it
    /// keeps working if the router gives the Pi a new address.
    var host: String
    /// The port it announced. Often not the one that answers: OctoPrint announces 80 behind
    /// OctoPi's proxy even when only https answers, and a Discovery plugin set up for one port
    /// goes on announcing it after OctoPrint has moved to another.
    var announcedPort: Int
    var state: State = .checking
    /// Where it answered, as a base URL such as "http://octopi.local:5001/".
    var address: String?

    var id: String { "\(name)@\(host)" }
}

/// The ports to look for OctoPrint on, given the one it announced: that one first, then
/// OctoPrint's own default (5000) and the one after it, which a second printer on the same Pi
/// usually gets.
func discoveryPorts(announced: Int) -> [Int] {
    var ports: [Int] = []
    for p in [announced, 5000, 5001] where p > 0 && !ports.contains(p) { ports.append(p) }
    return ports
}

/// "OctoPrint instance on octopi" is OctoPrint's default announcement; the Pi's name is the
/// part worth showing.
func tidyServiceName(_ name: String) -> String {
    let prefix = "OctoPrint instance on "
    guard name.hasPrefix(prefix) else { return name }
    let rest = name.dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)
    return rest.isEmpty ? name : rest
}

/// Lists the OctoPrints announcing themselves over Bonjour (`_octoprint._tcp`), as the Echo Show
/// does with its own mDNS. Each one's host name and port are read from the announcement itself
/// (DNS-SD's resolve), without connecting, so one whose announced port doesn't answer is still
/// listed; whether and where it answers is worked out afterwards (see `AppModel`).
@MainActor
final class Discovery {
    private var browser: NWBrowser?
    private var resolving: Set<String> = []
    private var resolvers: [ServiceResolver] = []
    /// A service's name, host name and announced port, once resolved.
    var onFound: ((_ name: String, _ host: String, _ port: Int) -> Void)?

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
        resolvers.forEach { $0.cancel() }
        resolvers.removeAll()
        resolving.removeAll()
    }

    private func resolve(_ result: NWBrowser.Result) {
        guard case let .service(name, type, domain, interface) = result.endpoint else { return }
        let key = "\(name).\(type).\(domain)"
        guard !resolving.contains(key) else { return }
        resolving.insert(key)
        let resolver = ServiceResolver(name: name, type: type, domain: domain, interface: UInt32(interface?.index ?? 0)) { [weak self] host, port in
            self?.onFound?(tidyServiceName(name), host, port)
        }
        resolvers.append(resolver)
        resolver.start()
    }
}

/// One DNS-SD resolve: a service's host name and port, read from its SRV record. Answers on
/// the main queue, once, and gives up quietly after a few seconds.
@MainActor
final class ServiceResolver {
    private let name: String
    private let type: String
    private let domain: String
    private let interface: UInt32
    private let done: (String, Int) -> Void
    private var ref: DNSServiceRef?
    private var answered = false

    init(name: String, type: String, domain: String, interface: UInt32, done: @escaping (String, Int) -> Void) {
        self.name = name
        self.type = type
        self.domain = domain.isEmpty ? "local." : domain
        self.interface = interface
        self.done = done
    }

    func start() {
        let context = Unmanaged.passUnretained(self).toOpaque()
        let err = DNSServiceResolve(&ref, 0, interface, name, type, domain, { _, _, _, error, _, hostTarget, port, _, _, context in
            guard error == kDNSServiceErr_NoError, let hostTarget, let context else { return }
            let resolver = Unmanaged<ServiceResolver>.fromOpaque(context).takeUnretainedValue()
            var host = String(cString: hostTarget)
            if host.hasSuffix(".") { host.removeLast() }
            // the port comes in network byte order
            let p = Int(UInt16(bigEndian: port))
            MainActor.assumeIsolated { resolver.answer(host, p) }
        }, context)
        guard err == kDNSServiceErr_NoError, let ref else { return }
        DNSServiceSetDispatchQueue(ref, .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in self?.cancel() }
    }

    private func answer(_ host: String, _ port: Int) {
        guard !answered else { return }
        answered = true
        done(host, port)
        // not from inside DNS-SD's own callback
        DispatchQueue.main.async { [weak self] in self?.cancel() }
    }

    func cancel() {
        if let ref { DNSServiceRefDeallocate(ref) }
        ref = nil
    }
}
