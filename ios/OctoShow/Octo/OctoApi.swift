import Foundation

struct OctoHttpError: Error {
    let code: Int
    let detail: String
}

struct LoginSession {
    let name: String
    let session: String
}

enum KeyPoll {
    case pending
    case granted(String)
    case denied
}

/// The slice of OctoPrint's REST API the app uses, over one route. Every call is async and
/// throws `URLError` / `OctoHttpError`. Ported from the Echo Show app's `OctoApi`.
final class OctoApi: @unchecked Sendable {
    static let keyHeader = "X-Api-Key"

    let route: Route
    let trust: ServerTrust
    let session: URLSession
    private let apiKey: String?
    var base: URL { route.base }

    init(route: Route, apiKey: String?, learning: Bool = false) {
        self.route = route
        self.apiKey = apiKey
        trust = ServerTrust(base: route.base, pinned: route.cert, learning: learning, user: route.user, password: route.password)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpMaximumConnectionsPerHost = 4
        session = URLSession(configuration: config, delegate: trust, delegateQueue: nil)
    }

    deinit { session.invalidateAndCancel() }

    func url(_ path: String, query: [URLQueryItem]? = nil) -> URL {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "?#;"))
        let encoded = path.split(separator: "/", omittingEmptySubsequences: false)
            .map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? String($0) }
            .joined(separator: "/")
        c.percentEncodedPath += encoded
        c.queryItems = query
        return c.url!
    }

    /// Resolve something OctoPrint hands back (a thumbnail, the webcam stream) against the base.
    func resolve(_ link: String) -> URL? { URL(string: link, relativeTo: base)?.absoluteURL }

    /// A request carrying the key and the route's headers, but only to OctoPrint's own address:
    /// not to another port on the same machine, where a camera often lives.
    func request(_ url: URL, method: String = "GET", body: JSON? = nil, timeout: TimeInterval? = nil) -> URLRequest {
        var r = URLRequest(url: url)
        r.httpMethod = method
        if let timeout { r.timeoutInterval = timeout }
        if ServerTrust.sameServer(url, base) || url.host == base.host {
            for (k, v) in route.headers { r.setValue(v, forHTTPHeaderField: k) }
        }
        if let apiKey, ServerTrust.sameServer(url, base) { r.setValue(apiKey, forHTTPHeaderField: Self.keyHeader) }
        if let body {
            r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    /* ---------------- access ---------------- */

    /// Whether this server hands out app keys on request, and where it really lives: a Pi that
    /// redirects http to https answers from a different base than the one asked. Nil when
    /// something answered but it wasn't OctoPrint offering keys.
    func keyOffice(timeout: TimeInterval = 6) async throws -> URL? {
        let (_, r) = try await raw(request(url("plugin/appkeys/probe"), timeout: timeout))
        guard r.statusCode == 204 else { return nil }
        return landedBase(r, asked: "plugin/appkeys/probe")
    }

    /// Ask OctoPrint for an access key; a signed-in user then approves it in the web page.
    func requestKey(app: String) async throws -> String {
        let o = try await json(request(url("plugin/appkeys/request"), method: "POST", body: ["app": app]))
        guard let token = o.str("app_token") else { throw OctoHttpError(code: 0, detail: "No token") }
        return token
    }

    func pollKey(_ token: String) async throws -> KeyPoll {
        let (data, r) = try await raw(request(url("plugin/appkeys/request/\(token)")))
        switch r.statusCode {
        case 200:
            let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON
            return o?.str("api_key").map(KeyPoll.granted) ?? .denied
        case 202: return .pending
        default: return .denied
        }
    }

    /// Where this key is good: the base that answered a keyed request.
    /// Throws 403 when the server is there but refuses the key.
    func keyHome() async throws -> URL {
        let (_, r) = try await checked(request(url("api/version"), timeout: 8))
        return landedBase(r, asked: "api/version")
    }

    /// Passive login: swaps the access key for the session the push socket wants.
    func login() async throws -> LoginSession {
        let o = try await json(request(url("api/login"), method: "POST", body: ["passive": true]))
        guard let name = o.str("name"), !name.isEmpty, let s = o.str("session"), !s.isEmpty else {
            throw OctoHttpError(code: 403, detail: "Not signed in")
        }
        return LoginSession(name: name, session: s)
    }

    func version() async throws -> String { try await json(request(url("api/version"))).str("server") ?? "" }

    /// Answers at all, quickly: used to choose between the home and away routes.
    func reachable(timeout: TimeInterval = 3) async -> Bool {
        guard let (_, r) = try? await raw(request(url("api/version"), timeout: timeout)) else { return false }
        return r.statusCode < 500
    }

    /// Whether OctoPrint still honours our key. A revoked key makes POSTs fail with a
    /// misleading 400 (CSRF), so this asks with a GET, which answers 403. Nil when unreachable.
    func keyAccepted() async -> Bool? {
        guard let (_, r) = try? await raw(request(url("api/version"))) else { return nil }
        if (200..<300).contains(r.statusCode) { return true }
        return r.statusCode == 403 ? false : nil
    }

    func settings() async throws -> JSON { try await json(request(url("api/settings"))) }

    /* ---------------- files ---------------- */

    private let listingLock = NSLock()
    private var listing: (tag: String, files: [PrintFile])?

    /// The file list is slow for a Pi to build and can be megabytes long, which matters over
    /// a relay with a data cap. It is asked for with the ETag of the last one, and when
    /// nothing has changed the Pi answers 304 and the last one is used again.
    func files() async throws -> [PrintFile] {
        var r = request(url("api/files/local", query: [URLQueryItem(name: "recursive", value: "true")]), timeout: 90)
        let last = listingLock.withLock { listing }
        if let last { r.setValue(last.tag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await raw(r)
        if response.statusCode == 304, let last { return last.files }
        guard (200..<300).contains(response.statusCode) else { throw httpError(response, data) }
        let parsed = FileList.parse(data)
        let tag = response.value(forHTTPHeaderField: "ETag")
        listingLock.withLock { listing = tag.map { ($0, parsed) } }
        return parsed
    }

    func file(origin: String, path: String) async throws -> PrintFile? {
        let (data, _) = try await checked(request(url("api/files/\(origin)/\(path)")))
        return FileList.parseOne(data)
    }

    func print(_ file: PrintFile) async throws {
        try await send("api/files/\(file.origin)/\(file.path)", ["command": "select", "print": true])
    }

    /// Where the file's G-code can be downloaded from.
    func downloadURL(origin: String, path: String) -> URL { url("downloads/files/\(origin)/\(path)") }

    /* ---------------- job ---------------- */

    func cancel() async throws { try await send("api/job", ["command": "cancel"]) }
    func pause() async throws { try await send("api/job", ["command": "pause", "action": "pause"]) }
    func resume() async throws { try await send("api/job", ["command": "pause", "action": "resume"]) }

    /* ---------------- printer ---------------- */

    func connectPrinter() async throws { try await send("api/connection", ["command": "connect"]) }
    /// After an error OctoPrint ignores "connect" until the dead connection is dropped.
    func disconnectPrinter() async throws { try await send("api/connection", ["command": "disconnect"]) }

    func setToolTarget(_ celsius: Int) async throws {
        try await send("api/printer/tool", ["command": "target", "targets": ["tool0": celsius]])
    }

    func setBedTarget(_ celsius: Int) async throws {
        try await send("api/printer/bed", ["command": "target", "target": celsius])
    }

    /// Millimetres, at `speed` mm/min. Left to itself OctoPrint takes the slowest of the axes it
    /// is given, and with all three named that would have X and Y crawl at Z's pace.
    func jog(x: Double = 0, y: Double = 0, z: Double = 0, speed: Int) async throws {
        try await send("api/printer/printhead", ["command": "jog", "x": x, "y": y, "z": z, "speed": speed])
    }

    func home(_ axes: [String]) async throws {
        try await send("api/printer/printhead", ["command": "home", "axes": axes])
    }

    /// Positive pushes filament out, negative pulls it back. Millimetres, at `speed` mm/min.
    func extrude(_ mm: Int, speed: Int) async throws {
        try await send("api/printer/tool", ["command": "extrude", "amount": mm, "speed": speed])
    }

    func setFeedRate(_ percent: Int) async throws {
        try await send("api/printer/printhead", ["command": "feedrate", "factor": percent])
    }

    func setFlowRate(_ percent: Int) async throws {
        try await send("api/printer/tool", ["command": "flowrate", "factor": percent])
    }

    func gcode(_ commands: [String]) async throws {
        try await send("api/printer/command", ["commands": commands])
    }

    /* ---------------- plugins ---------------- */

    /// LightControls: brightness 0...100 for the light on a GPIO pin. Needs the key of an admin.
    func setLight(pin: Int, percent: Int) async throws {
        try await send("api/plugin/LightControls", ["command": "setLightValue", "pin": pin, "percentage": min(max(percent, 0), 100)])
    }

    /// LightControls has no way to read values back; this makes it announce them on the socket.
    func announceLights() async throws {
        try await send("api/plugin/LightControls", ["command": "getLightValues"])
    }

    /// The question the printer is currently asking, if any.
    func activePrompt() async -> Prompt? {
        guard let (data, r) = try? await raw(request(url("api/plugin/action_command_prompt"))), r.statusCode == 200,
              let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON, let text = o.str("text")
        else { return nil }
        return Prompt(text: text, choices: (o.arr("choices") ?? []).map { "\($0)" })
    }

    func answerPrompt(_ choice: Int) async throws {
        try await send("api/plugin/action_command_prompt", ["command": "select", "choice": choice])
    }

    /* ---------------- the Echo Show, through the OctoShow plugin ---------------- */

    /// The display listening through the OctoShow plugin: `.some(nil)` when the plugin is there
    /// and no display has reported lately, `nil` when OctoPrint hasn't the plugin at all.
    func displayStatus() async -> DisplayStatus?? {
        guard let (data, r) = try? await raw(request(url("api/plugin/octoshow"))), r.statusCode == 200,
              let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON
        else { return nil }
        return .some(DisplayStatus(o.obj("display")))
    }

    /// Ask the display to switch everything off (`off`), to do so once the print is done
    /// (`offWhenDone`), or to leave everything on (`stay`). Throws 409 when no display is listening.
    func askDisplay(_ action: String) async throws {
        try await send("api/plugin/octoshow", ["command": "power", "action": action])
    }

    /* ---------------- plumbing ---------------- */

    func send(_ path: String, _ body: JSON) async throws {
        _ = try await checked(request(url(path), method: "POST", body: body))
    }

    func raw(_ r: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: r)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    private func checked(_ r: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, http) = try await raw(r)
        guard (200..<300).contains(http.statusCode) else { throw httpError(http, data) }
        return (data, http)
    }

    private func json(_ r: URLRequest) async throws -> JSON {
        let (data, _) = try await checked(r)
        return (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? [:]
    }

    /// OctoPrint answers errors with {"error": "..."}; fall back to the status code.
    private func httpError(_ r: HTTPURLResponse, _ data: Data) -> OctoHttpError {
        let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON
        return OctoHttpError(code: r.statusCode, detail: o?.str("error") ?? HTTPURLResponse.localizedString(forStatusCode: r.statusCode))
    }

    private func landedBase(_ r: HTTPURLResponse, asked: String) -> URL {
        guard let landed = r.url else { return base }
        if ServerTrust.sameServer(landed, base) { return base }
        let s = landed.absoluteString
        if let at = s.range(of: asked) { return normalizeBase(String(s[..<at.lowerBound])) ?? base }
        return base
    }
}

/// A sentence for the person at the printer, not a stack trace.
func forPeople(_ error: Error) -> String {
    if let e = error as? OctoHttpError {
        switch e.code {
        case 401, 403: return "OctoPrint turned down this phone's access key."
        case 404: return "OctoPrint couldn't find that."
        case 409: return "The printer can't do that right now."
        default: return "OctoPrint answered with an error (\(e.code))."
        }
    }
    return "Couldn't reach OctoPrint."
}
