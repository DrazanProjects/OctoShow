import Foundation

/// Where we stand with the OctoPrint server itself (not the printer behind it).
enum Link: Equatable {
    case connecting
    case online
    case unreachable
    /// The access key was revoked or never valid; setup has to run again.
    case unauthorized
    /// An https server presented a different certificate from the one seen at setup.
    case untrusted
}

enum LiveEvent {
    case filesChanged
    case printEnded(Outcome)
    case printStarted
    /// A light was switched somewhere else.
    case lightChanged(pin: Int, percent: Int)
}

/// What the push socket has to say, handed to the session on the main actor.
enum LiveUpdate {
    case online
    case printer(PrinterSnapshot)
    case layers(LayerInfo)
    case temps([TempSample])
    case prompt(Prompt?)
    case event(LiveEvent)
    /// Lines of the terminal log carrying the nozzle's real position (REALPOS).
    case nozzle([String])
    /// What the Echo Show display says about itself, through the OctoShow plugin.
    case display(DisplayStatus)
}

/// What the firmware starts each reading of the nozzle's real position with.
let nozzleMark = "REALPOS "

/// OctoPrint's push socket: one connection that streams printer state about once a second.
/// `listen` holds one socket open until it closes or fails; the session decides when and
/// over which route to try again. Ported from the Echo Show app's `OctoLive`.
final class OctoLive: @unchecked Sendable {
    private let api: OctoApi
    private let send: @Sendable (LiveUpdate) async -> Void

    // Only touched from inside `listen`, which runs one message at a time.
    private var printer: PrinterSnapshot?
    private var layers = LayerInfo()
    private var temps: [TempSample] = []
    private var reporting = false

    init(api: OctoApi, carry layers: LayerInfo = LayerInfo(), send: @escaping @Sendable (LiveUpdate) async -> Void) {
        self.api = api
        self.layers = layers
        self.send = send
    }

    func listen() async throws {
        var session = try await api.login()
        var r = api.request(socketURL())
        r.timeoutInterval = 30
        let socket = api.session.webSocketTask(with: r)
        socket.maximumMessageSize = 16 * 1024 * 1024
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil) }

        // If OctoPrint accepts the socket but goes quiet, start over. It sends state every
        // second or so, so a long silence is a dead connection the phone hasn't noticed.
        let lastHeard = Clock()
        let watchdog = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                if lastHeard.since > (lastHeard.gotData ? 20 : 12) {
                    socket.cancel(with: .abnormalClosure, reason: nil)
                    return
                }
            }
        }
        defer { watchdog.cancel() }

        var quick = false
        reporting = false
        while !Task.isCancelled {
            let message = try await socket.receive()
            lastHeard.touch()
            let text: String
            switch message {
            case .string(let s): text = s
            case .data(let d): text = String(decoding: d, as: UTF8.self)
            @unknown default: continue
            }
            guard let msg = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? JSON else { continue }
            if msg["connected"] != nil {
                // Next to nothing of the terminal log, and only the plugins we read: keeps updates small.
                try await socket.send(.string(Self.subscribe))
                // 500 ms base interval x2: one update a second is plenty for a phone.
                try await socket.send(.string(Self.throttle(2)))
                try await socket.send(.string(Self.auth(session)))
            } else if msg["reauthRequired"] != nil {
                session = try await api.login()
                try await socket.send(.string(Self.auth(session)))
            } else if let history = msg.obj("history") {
                lastHeard.gotData = true
                await applyState(history, replaceTemps: true)
                await send(.online)
            } else if let current = msg.obj("current") {
                lastHeard.gotData = true
                await applyState(current, replaceTemps: false)
                await applyLog(current)
                await send(.online)
                // ...unless the nozzle can be followed, which wants every reading promptly
                if reporting && !quick {
                    quick = true
                    try await socket.send(.string(Self.throttle(1)))
                }
            } else if let event = msg.obj("event") {
                await applyEvent(event)
            } else if let plugin = msg.obj("plugin") {
                await applyPlugin(plugin)
            }
        }
        throw CancellationError()
    }

    private func socketURL() -> URL {
        var c = URLComponents(url: api.url("sockjs/websocket"), resolvingAgainstBaseURL: false)!
        c.scheme = c.scheme == "https" ? "wss" : "ws"
        return c.url!
    }

    /* ---------------- state ---------------- */

    private func applyState(_ d: JSON, replaceTemps: Bool) async {
        let prev = printer
        let state = d.obj("state")
        let flags = state?.obj("flags")
        let text = state?.str("text") ?? ""
        func flag(_ name: String) -> Bool { flags?.bool(name) == true }

        let phase: Phase
        if flag("error") { phase = .error }
        else if flag("cancelling") { phase = .cancelling }
        else if flag("finishing") { phase = .finishing }
        else if flag("pausing") { phase = .pausing }
        else if flag("resuming") { phase = .resuming }
        else if flag("paused") { phase = .paused }
        else if flag("printing") { phase = text.hasPrefix("Starting") ? .starting : .printing }
        else if flag("operational") { phase = .ready }
        else if text.hasPrefix("Offline") || text.isEmpty { phase = .offline }
        else if flag("closedOrError") { phase = .error }
        else { phase = .connecting }

        let job = d.obj("job")
        var file: JobFile?
        if let f = job?.obj("file"), let name = f.str("name") {
            file = JobFile(name: name, display: f.str("display") ?? name, path: f.str("path") ?? name, origin: f.str("origin") ?? "local")
        }
        let progress = d.obj("progress")

        var tool = prev?.tool
        var bed = prev?.bed
        if let arr = d.arr("temps"), !arr.isEmpty {
            var fresh: [TempSample] = []
            for case let e as JSON in arr {
                let t = e.obj("tool0").map { Temp(actual: $0.dbl("actual"), target: $0.dbl("target") ?? 0) }
                let b = e.obj("bed").map { Temp(actual: $0.dbl("actual"), target: $0.dbl("target") ?? 0) }
                if let t { tool = t }
                if let b { bed = b }
                fresh.append(TempSample(tool: t?.actual, bed: b?.actual))
            }
            let merged = replaceTemps ? fresh : temps + fresh
            temps = Array(merged.suffix(Self.tempKeep))
            await send(.temps(temps))
        }
        if phase == .offline {
            tool = nil
            bed = nil
        }

        let snapshot = PrinterSnapshot(
            phase: phase,
            stateText: text,
            errorText: state?.str("error").flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 },
            file: file,
            estimatedPrintTime: job?.dbl("estimatedPrintTime"),
            completion: progress?.dbl("completion"),
            printTime: progress?.int("printTime"),
            printTimeLeft: progress?.int("printTimeLeft"),
            currentZ: d.dbl("currentZ"),
            tool: tool,
            bed: bed
        )
        printer = snapshot
        await send(.printer(snapshot))
    }

    /// The lines of the terminal log asked for in `subscribe`, in the order they came.
    private func applyLog(_ d: JSON) async {
        let lines = (d.arr("logs") ?? []).compactMap { $0 as? String }.filter { $0.contains(nozzleMark) }
        guard !lines.isEmpty else { return }
        reporting = true
        await send(.nozzle(lines))
    }

    private func applyEvent(_ e: JSON) async {
        let p = e.obj("payload")
        func ended(_ kind: Outcome.Kind) -> LiveEvent {
            .printEnded(Outcome(kind: kind, fileName: p?.str("name") ?? "", seconds: p?.int("time")))
        }
        switch e.str("type") {
        case "PrintStarted":
            // Forget where the last job had got to, but keep the layer count: the Dashboard
            // plugin sends the new job's count at this same moment, sometimes just before.
            layers.current = nil
            layers.layerProgress = nil
            await send(.layers(layers))
            await send(.event(.printStarted))
        case "PrintDone": await send(.event(ended(.done)))
        case "PrintCancelled": await send(.event(ended(.cancelled)))
        // a cancel also raises PrintFailed with reason "cancelled"; only real failures count here
        case "PrintFailed": if p?.str("reason") != "cancelled" { await send(.event(ended(.failed))) }
        case "UpdatedFiles", "FileAdded", "FileRemoved", "FolderAdded", "FolderRemoved", "MetadataAnalysisFinished":
            await send(.event(.filesChanged))
        default: break
        }
    }

    private func applyPlugin(_ m: JSON) async {
        guard let data = m.obj("data") else { return }
        switch m.str("plugin") {
        case "dashboard": await applyDashboard(data)
        case "LightControls":
            if let pin = data.int("pin") { await send(.event(.lightChanged(pin: pin, percent: data.int("value") ?? 0))) }
        case "action_command_prompt":
            if data.str("action") == "show" {
                await send(.prompt(Prompt(text: data.str("text") ?? "", choices: (data.arr("choices") ?? []).map { "\($0)" })))
            } else {
                await send(.prompt(nil))
            }
        case "octoshow":
            // the phone's own requests come back this way too, and are the display's business
            if data.str("type") == "display", let d = DisplayStatus(data.obj("display")) { await send(.display(d)) }
        default: break
        }
    }

    /// Layer progress from the Dashboard plugin. It sends nearly everything as text, and its
    /// totalLayers counts one more than there are (the moves before the first layer get a slot).
    private func applyDashboard(_ d: JSON) async {
        let cur = layers
        let current = d.int("currentLayer") ?? cur.current
        // A layer starts from nothing, but the plugin doesn't say so until ten moves later.
        let carried = (current != cur.current && cur.layerProgress != nil) ? 0 : cur.layerProgress
        layers = LayerInfo(
            current: current,
            total: d.int("totalLayers").map { max($0 - 1, 0) } ?? cur.total,
            layerProgress: d.int("layerProgress").map { min(max($0, 0), 100) } ?? carried,
            fanPercent: d.int("fanSpeed").map { min(max($0, 0), 100) } ?? cur.fanPercent
        )
        await send(.layers(layers))
    }

    private static let subscribe: String = {
        let o: JSON = [
            "subscribe": [
                // OctoPrint takes text here as a pattern, and sends only the log lines it fits
                "state": ["logs": nozzleMark, "messages": false],
                "events": true,
                "plugins": ["dashboard", "LightControls", "action_command_prompt", "octoshow"],
            ] as JSON,
        ]
        return String(decoding: try! JSONSerialization.data(withJSONObject: o), as: UTF8.self)
    }()

    private static func throttle(_ factor: Int) -> String { "{\"throttle\":\(factor)}" }

    private static func auth(_ s: LoginSession) -> String {
        let o: JSON = ["auth": "\(s.name):\(s.session)"]
        return String(decoding: try! JSONSerialization.data(withJSONObject: o), as: UTF8.self)
    }

    /// About ten minutes of history for the little heat traces.
    private static let tempKeep = 300
}

/// When the socket was last heard from.
private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var last = Date()
    private var _gotData = false
    func touch() { lock.withLock { last = Date() } }
    var since: TimeInterval { lock.withLock { Date().timeIntervalSince(last) } }
    var gotData: Bool {
        get { lock.withLock { _gotData } }
        set { lock.withLock { _gotData = newValue } }
    }
}
