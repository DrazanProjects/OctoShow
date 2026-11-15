import Foundation
import Observation

enum SetupState: Equatable {
    case idle
    case checking(String)
    /// Asked OctoPrint for a key; someone has to say yes in its web page.
    case waiting(String)
    case failed(String)
}

/// The app as a whole: which OctoPrint it is set up with, the session talking to it, and
/// first-run setup. Ported from the setup half of the Echo Show app's `PrinterViewModel`.
@MainActor
@Observable
final class AppModel {
    let prefs = Prefs()
    private(set) var server: Server?
    private(set) var session: PrinterSession?

    var setup: SetupState = .idle
    private(set) var found: [FoundServer] = []
    private var discovery: Discovery?
    private var setupTask: Task<Void, Never>?

    /// The tab on show.
    var tab = AppTab.status

    /// Shown in OctoPrint's approval prompt and its list of application keys.
    static let appName = "OctoShow iPhone"

    init() {
        if let s = ServerStore.load() { use(s, save: false) }
    }

    private func use(_ s: Server?, save: Bool = true) {
        session?.stop()
        server = s
        if save {
            if let s { ServerStore.save(s) } else { ServerStore.forget() }
        }
        session = s.map { PrinterSession(server: $0, prefs: prefs) }
        session?.start()
    }

    func forgetServer() { use(nil) }

    /// Change the route used away from home; nil removes it.
    func setAway(_ away: Route?) {
        guard var s = server else { return }
        s.away = away
        use(s)
    }

    func handle(url: URL) {
        // An alert's link: show how the print stands, freshly.
        guard url.scheme == "octoshow" else { return }
        tab = .status
        session?.reconnectNow()
    }

    /* ---------------- discovery ---------------- */

    func startDiscovery() {
        if discovery == nil {
            let d = Discovery()
            d.onFound = { [weak self] f in
                guard let self, !self.found.contains(where: { $0.address == f.address }) else { return }
                self.found.append(f)
            }
            discovery = d
        }
        discovery?.start()
    }

    func stopDiscovery() {
        discovery?.stop()
    }

    /* ---------------- first-run setup ---------------- */

    private enum Located {
        case found(OctoApi)
        case notOctoPrint
        case silent
    }

    /// The first candidate that answers as OctoPrint, with the client set up to talk to it.
    private func locate(_ address: String, announced: Bool) async -> Located {
        var answered = false
        for base in candidates(for: address, announced: announced) {
            let octo = OctoApi(route: Route(base: base), apiKey: nil, learning: true)
            do {
                guard let home = try await octo.keyOffice() else {
                    answered = true
                    continue
                }
                if home == base { return .found(octo) }
                // keep the certificate met so far, so a switch of address can't swap servers
                return .found(OctoApi(route: Route(base: home, cert: octo.trust.learned), apiKey: nil, learning: true))
            } catch {
                continue
            }
        }
        return answered ? .notOctoPrint : .silent
    }

    private func hostOf(_ address: String) -> String { normalizeBase(address)?.host ?? address }

    /// Ask the server at `address` for an access key and wait for someone to approve it.
    /// `announced` is for an address that came from the server's own announcement.
    func requestAccess(_ address: String, name: String = "", announced: Bool = false) {
        setupTask?.cancel()
        guard normalizeBase(address) != nil else {
            setup = .failed("That doesn't look like an address. Try something like 192.168.1.50.")
            return
        }
        let host = hostOf(address)
        setupTask = Task {
            setup = .checking(host)
            let octo: OctoApi
            switch await locate(address, announced: announced) {
            case .found(let api): octo = api
            case .notOctoPrint:
                setup = .failed("Something answered at \(host), but it isn't handing out access keys. Paste a key from OctoPrint's settings instead.")
                return
            case .silent:
                setup = .failed("Nothing answered at \(host). Check the address, that the Pi is on, and that this phone is on the same Wi‑Fi.")
                return
            }
            do {
                let token = try await octo.requestKey(app: Self.appName)
                setup = .waiting(octo.base.absoluteString)
                while !Task.isCancelled {
                    try await Task.sleep(for: .milliseconds(1200))
                    switch try await octo.pollKey(token) {
                    case .pending: continue
                    case .granted(let key):
                        let route = Route(base: octo.base, cert: octo.trust.learned ?? octo.route.cert)
                        use(Server(name: name.isEmpty ? host : name, apiKey: key, home: route, away: nil))
                        setup = .idle
                        stopDiscovery()
                        return
                    case .denied:
                        setup = .failed("The request was declined or ran out of time. Ask again when OctoPrint is open in a browser.")
                        return
                    }
                }
            } catch is CancellationError {
            } catch let e as OctoHttpError {
                setup = .failed(e.code == 429
                    ? "OctoPrint is limiting how often keys can be requested. Wait a minute, then ask again."
                    : "OctoPrint at \(host) answered with an error (\(e.code)).")
            } catch {
                setup = .failed("The connection to \(host) dropped. Try again.")
            }
        }
    }

    /// Skip the approval step with a key copied out of OctoPrint's settings.
    func useKey(_ address: String, key: String, name: String = "") {
        setupTask?.cancel()
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalizeBase(address) != nil, !trimmed.isEmpty else {
            setup = .failed("Enter both the address and the key.")
            return
        }
        let host = hostOf(address)
        setupTask = Task {
            setup = .checking(host)
            var refused = false
            for base in candidates(for: address) {
                let octo = OctoApi(route: Route(base: base), apiKey: trimmed, learning: true)
                do {
                    let home = try await octo.keyHome()
                    use(Server(name: name.isEmpty ? host : name, apiKey: trimmed, home: Route(base: home, cert: octo.trust.learned), away: nil))
                    setup = .idle
                    stopDiscovery()
                    return
                } catch let e as OctoHttpError {
                    if e.code == 403 { refused = true }
                } catch {
                    if Task.isCancelled { return }
                }
            }
            setup = .failed(refused ? "OctoPrint didn't accept that key." : "Nothing answered at \(host). Check the address and that the Pi is on.")
        }
    }

    func cancelSetup() {
        setupTask?.cancel()
        setup = .idle
    }

    /// Check an away route before keeping it: it has to reach this same OctoPrint with this key.
    /// Gives the route to keep (with the certificate met there, if it is self-signed) or a reason.
    func checkAway(address: String, headers: [String: String], user: String?, password: String?) async -> Result<Route, AwayProblem> {
        guard let server else { return .failure(.message("Set up OctoPrint at home first.")) }
        let tries = candidates(for: address)
        guard !tries.isEmpty else { return .failure(.message("That doesn't look like an address.")) }
        var refused = false
        for base in tries {
            // The same Pi reached over a VPN shows the certificate pinned at home, so offer that first.
            for pin in [server.home.cert, nil] {
                let route = Route(base: base, cert: pin, headers: headers, user: user, password: password)
                let octo = OctoApi(route: route, apiKey: server.apiKey, learning: pin == nil)
                do {
                    let home = try await octo.keyHome()
                    var kept = route
                    kept.base = home
                    if pin == nil { kept.cert = octo.trust.learned }
                    return .success(kept)
                } catch let e as OctoHttpError {
                    if e.code == 401 || e.code == 403 { refused = true }
                } catch {}
            }
        }
        return .failure(.message(refused
            ? "Something answered, but it turned the key down. A Cloudflare Access token or relay password may be missing."
            : "Nothing answered there. From home, a VPN address only works with the VPN switched on."))
    }

    enum AwayProblem: Error { case message(String) }
}
