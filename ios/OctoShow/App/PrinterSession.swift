import Foundation
import Network
import Observation
import os
import PartKit
import SwiftUI
import UIKit

struct FilesState {
    var loading = false
    var loaded = false
    var error: String?
    var root: [PrintFile] = [] {
        didSet { newestFirst = root.newestFirst }
    }
    /// Every printable file in the tree, most recent upload first.
    private(set) var newestFirst: [PrintFile] = []

    init(loading: Bool = false, loaded: Bool = false, error: String? = nil, root: [PrintFile] = []) {
        self.loading = loading
        self.loaded = loaded
        self.error = error
        // set here as well: didSet doesn't run during init
        self.root = root
        newestFirst = root.newestFirst
    }
}

/// Which way the phone is reaching OctoPrint just now.
enum Via: Equatable {
    case home
    case away
}

/// One OctoPrint, for as long as it stays set up: state in, commands out. Ported from the
/// Echo Show app's `PrinterViewModel`, without the power plug and screen-dimming parts.
/// Over the home address when that answers, otherwise over the away one if there is one.
@MainActor
@Observable
final class PrinterSession {
    let server: Server
    let prefs: Prefs

    private(set) var link: Link = .connecting
    private(set) var via: Via = .home
    private(set) var api: OctoApi?
    private(set) var printer: PrinterSnapshot?
    private(set) var layers = LayerInfo()
    private(set) var temps: [TempSample] = []
    private(set) var info = ServerInfo()
    private(set) var files = FilesState()
    /// Details of the selected job's file: its slicer preview and estimates.
    private(set) var jobFile: PrintFile?
    private(set) var outcome: Outcome?
    /// A question from the printer that is waiting for an answer.
    private(set) var prompt: Prompt?
    /// One line of feedback after an action fails; clears itself.
    private(set) var notice: String?
    /// Feed and flow overrides are not reported back by OctoPrint, so remember what we set.
    private(set) var feedRate = 100
    private(set) var flowRate = 100
    /// The Echo Show display, as it last reported through the OctoShow plugin: nil when OctoPrint
    /// hasn't the plugin, or no display has reported lately. Kept after OctoPrint goes quiet while
    /// the display was switching everything off, so the phone can say how that will have ended.
    private(set) var display: DisplayStatus?
    /// A file going onto the printer's SD card (the Card Transfer plugin). OctoPrint has let go of
    /// the printer meanwhile and calls it offline, so this is shown instead, and nothing may connect
    /// to the printer until the copy is done.
    private(set) var cardCopy: CardCopy?
    /// Where the nozzle really is, when the firmware says (REALPOS): the part drawing follows it.
    let nozzle = NozzleWatch()
    /// The parts this phone draws from G-code: the job's, as it is printed, and previews for
    /// files the slicer gave no picture. Kept on the phone; nothing is written to OctoPrint.
    let parts: PartLibrary
    /// What the drawing library's downloads need to know, from whichever thread they run on.
    private let shared = Shared()

    /// The file the job's part is drawn from: the job's own, except for one printed from the
    /// printer's SD card, which OctoPrint can't read: then OctoPrint's own copy, if it has one.
    var jobDrawing: PrintFile? {
        guard let job = jobFile else { return nil }
        if job.origin != sdCard { return job }
        return sameFile(as: JobFile(name: job.name, display: job.display, path: job.path, origin: job.origin), in: files.newestFirst)
    }

    private var loop: Task<Void, Never>?
    private var freshness: Task<Void, Never>?
    private var listening: Task<Void, Error>?
    private var filesTask: Task<Void, Never>?
    private var jobFileTask: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var infoTask: Task<Void, Never>?
    private var filesStale = true
    private var filesWanted = false
    private let paths = NWPathMonitor()
    private var lastPath: String?
    private let thumbCache = NSCache<NSString, UIImage>()

    init(server: Server, prefs: Prefs) {
        self.server = server
        self.prefs = prefs
        let shared = shared
        let dir = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        // G-code is fetched gently while a job runs, about a megabyte a second, so the Pi isn't
        // troubled; over the same pinned connection as everything else.
        parts = PartLibrary(directory: dir.appending(path: "Drawings", directoryHint: .isDirectory)) { file, to, _ in
            guard let octo = shared.api else { throw URLError(.notConnectedToInternet) }
            let r = octo.request(octo.downloadURL(origin: file.origin, path: file.path), timeout: 60)
            try await PartLibrary.download(r, session: octo.session, to: to, gentle: { shared.busy })
        }
    }

    /* ---------------- connection ---------------- */

    func start() {
        guard loop == nil else { return }
        loop = Task { await run() }
        // A display that stops reporting has gone (switched off, or lost its connection): stop
        // offering its power button. One that went quiet while switching off is kept, to say so.
        freshness = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                if let d = display, !d.fresh(), !(d.step?.underWay == true && link != .online) { display = nil }
            }
        }
        // Going from Wi‑Fi to mobile data, or back, changes which route works: start over.
        paths.pathUpdateHandler = { [weak self] path in
            let key = "\(path.status)-\(path.availableInterfaces.map(\.name).joined(separator: ","))"
            Task { @MainActor in
                guard let self else { return }
                if let last = self.lastPath, last != key { self.reconnectNow() }
                self.lastPath = key
            }
        }
        paths.start(queue: .main)
    }

    func stop() {
        loop?.cancel()
        freshness?.cancel()
        listening?.cancel()
        paths.cancel()
        filesTask?.cancel()
        infoTask?.cancel()
    }

    /// Drop the socket and try again straight away, over whichever route answers now.
    func reconnectNow() {
        listening?.cancel()
    }

    private func run() async {
        while !Task.isCancelled {
            let (route, where_) = await chooseRoute()
            via = where_
            // The same route keeps the same client, and with it the file list's ETag.
            if api == nil || api?.route != route { api = OctoApi(route: route, apiKey: server.apiKey) }
            let current = api!
            shared.api = current
            let live = OctoLive(api: current, carry: layers) { [weak self] update in
                await self?.apply(update, from: current)
            }
            let t = Task { try await live.listen() }
            listening = t
            do {
                try await t.value
            } catch {
                if Task.isCancelled { return }
                // Asked to start over (reconnectNow): go round at once, without saying it's down.
                if t.isCancelled { continue }
                // A dead key shows up as 403 on GETs but as 400 on the login POST, so ask plainly.
                if let e = error as? OctoHttpError, (400...403).contains(e.code), await current.keyAccepted() == false {
                    link = .unauthorized
                    try? await Task.sleep(for: .seconds(15))
                    continue
                }
                markDown(current)
            }
            try? await Task.sleep(for: .seconds(4))
        }
    }

    /// Home if it answers, else away if there is one and it answers, else home anyway.
    private func chooseRoute() async -> (Route, Via) {
        guard let away = server.away else { return (server.home, .home) }
        if await OctoApi(route: server.home, apiKey: server.apiKey).reachable(timeout: 2.5) { return (server.home, .home) }
        if await OctoApi(route: away, apiKey: server.apiKey).reachable(timeout: 6) { return (away, .away) }
        return (via == .away ? away : server.home, via)
    }

    private func markDown(_ octo: OctoApi) {
        link = octo.trust.changed ? .untrusted : .unreachable
    }

    private func apply(_ u: LiveUpdate, from octo: OctoApi) {
        guard octo === api else { return }
        switch u {
        case .online:
            if link != .online {
                link = .online
                becameOnline(octo)
            }
        case .printer(let p): onPrinter(p)
        case .layers(let l): layers = l
        case .temps(let t): temps = t
        case .prompt(let p): prompt = p
        case .event(let e): onEvent(e)
        case .nozzle(let lines): for line in lines { _ = nozzle.add(line: line) }
        case .display(let d): display = d
        case .cardCopy(let c): cardCopy = c
        case .cardCopyProgress(let name, let fraction):
            // a copy whose start was missed (the socket came up during it) is picked up here
            var known = cardCopy?.name == name ? cardCopy! : CardCopy(name: name, fraction: 0)
            known.fraction = min(max(fraction, 0), 1)
            cardCopy = known
        }
    }

    private func becameOnline(_ octo: OctoApi) {
        // a fetch that failed while the server was away deserves another go
        parts.forgetFailures()
        infoTask?.cancel()
        infoTask = Task {
            // a prompt raised while the phone was away is still waiting
            if let p = await octo.activePrompt() { prompt = p }
            // whether an Echo Show is listening, to offer its power button; it reports by itself after this
            if let answer = await octo.displayStatus() { display = answer } else { display = nil }
            // A Pi that has only just booted can be slow or half-ready. Without its settings
            // there is no camera, presets or lights, so keep asking.
            var wait: Duration = .seconds(2)
            while !Task.isCancelled, !(await loadServerInfo(octo)) {
                try? await Task.sleep(for: wait)
                wait = min(wait * 2, .seconds(30))
            }
        }
    }

    /* ---------------- state ---------------- */

    private func onPrinter(_ p: PrinterSnapshot) {
        let before = printer?.phase
        printer = p

        // A finished job stays on OctoPrint as "100 %, operational"; show it as done even if
        // the phone wasn't watching when it happened.
        if outcome == nil, p.phase == .ready, (p.completion ?? 0) >= 100, let f = p.file,
           UserDefaults.standard.string(forKey: "clearedJob") != jobId(p) {
            outcome = Outcome(kind: .done, fileName: f.display, seconds: p.printTime)
        }
        // A job running clears how the last one ended. Finishing and Cancelling don't: OctoPrint
        // calls a job done once its last line is in the printer and then waits in Finishing while
        // the printer works through what it holds, and it says a job was cancelled just before
        // it goes back to Operational. Both are the end of the job just told of.
        if outcome != nil, p.phase.busy, p.phase != .finishing, p.phase != .cancelling { outcome = nil }
        // It finished when the printer stopped, not when OctoPrint ran out of lines to send.
        if before == .finishing, p.phase == .ready, outcome?.kind == .done, outcome?.at != nil {
            outcome?.at = Date()
        }
        if p.phase == .ready, before != .ready { wantFiles() }
        shared.busy = p.phase.busy
        trackJobFile(p)
        // The running job's part is drawn whatever is on screen, so a phone taken out of a
        // pocket mid-print has it ready. Asked for often; the library asks OctoPrint rarely.
        if p.phase.busy, let job = jobFile, job.path == p.file?.path, job.origin == p.file?.origin {
            if job.origin == sdCard, !files.loaded, files.error == nil { wantFiles() }
            if let drawing = jobDrawing { parts.wantShape(PartFile(drawing), gentle: true) }
        }
    }

    /// One finished run of one file: a reprint takes a different time, so it counts as new.
    private func jobId(_ p: PrinterSnapshot) -> String { "\(p.file?.origin ?? ""):\(p.file?.path ?? ""):\(p.printTime ?? 0)" }

    /// Keep `jobFile` pointing at whatever is selected, asking OctoPrint for just that entry.
    private func trackJobFile(_ p: PrinterSnapshot) {
        guard let f = p.file else {
            jobFile = nil
            return
        }
        if jobFile?.path == f.path, jobFile?.origin == f.origin { return }
        if jobFileTask != nil { return }
        guard let octo = api else { return }
        jobFileTask = Task {
            let got = try? await octo.file(origin: f.origin, path: f.path)
            jobFile = got ?? PrintFile(name: f.name, display: f.display, path: f.path, origin: f.origin, isFolder: false,
                                       size: 0, date: 0, estimatedPrintTime: p.estimatedPrintTime, filamentCm3: nil,
                                       thumbnail: nil, lastPrintSuccess: nil)
            jobFileTask = nil
            // one from the SD card is drawn from OctoPrint's own copy, found in its file list
            if f.origin == sdCard, !files.loaded { wantFiles() }
        }
    }

    private func onEvent(_ e: LiveEvent) {
        switch e {
        case .printStarted:
            outcome = nil
            feedRate = 100
            flowRate = 100
            nozzle.reset()
        case .printEnded(let o):
            outcome = o
        case .filesChanged:
            filesStale = true
            // Building the listing costs the Pi several seconds, so never do it under a running
            // print unless someone is actually looking at the file list.
            if filesWanted || printer?.phase == .ready { refreshFiles() }
        case .lightChanged(let pin, let percent):
            info.lights = info.lights.map { var l = $0; if l.pin == pin { l.percent = percent }; return l }
        case .cardCopyFailed(let name, let error):
            let what = ["Copying \(prettyName(name)) to the SD card failed", error.trimmingCharacters(in: .whitespaces)]
                .filter { !$0.isEmpty }.joined(separator: ": ")
            say("\(what). It is still in OctoPrint.")
        case .heatersOff(let why):
            let reason = why.trimmingCharacters(in: .whitespaces)
            say("Heaters switched off: \(reason.isEmpty ? "nothing came to use them" : reason).")
        }
    }

    /* ---------------- server info ---------------- */

    /// False if the server couldn't be asked; the caller tries again.
    private func loadServerInfo(_ octo: OctoApi) async -> Bool {
        guard let version = try? await octo.version(), let settings = try? await octo.settings() else { return false }
        let lights = Self.readLights(settings)
        info = ServerInfo(
            name: settings.obj("appearance")?.str("name").flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? "",
            version: version,
            presets: Self.readPresets(settings),
            webcam: Self.readWebcam(settings),
            lights: lights
        )
        // their current brightness arrives over the socket in reply
        if !lights.isEmpty { try? await octo.announceLights() }
        if printer?.phase == .ready { wantFiles() }
        return true
    }

    private static func readPresets(_ settings: JSON) -> [HeatPreset] {
        (settings.obj("temperature")?.arr("profiles") ?? []).compactMap { any in
            guard let o = any as? JSON, let name = o.str("name") else { return nil }
            return HeatPreset(name: name, tool: o.int("extruder") ?? 0, bed: o.int("bed") ?? 0)
        }
    }

    /// Lights set up in the LightControls plugin. Its settings page stores some values as text.
    private static func readLights(_ settings: JSON) -> [Light] {
        (settings.obj("plugins")?.obj("LightControls")?.arr("light_controls") ?? []).compactMap { any in
            guard let o = any as? JSON, let pin = o.int("pin") else { return nil }
            let name = o.str("name").flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? "Light"
            return Light(name: name, pin: pin, dimmable: o.bool("ispwm") == true)
        }
    }

    private static func readWebcam(_ settings: JSON) -> Webcam? {
        guard let cam = settings.obj("webcam") else { return nil }
        if cam.bool("webcamEnabled") == false { return nil }
        let classic = settings.obj("plugins")?.obj("classicwebcam")
        guard let stream = [cam.str("streamUrl"), classic?.str("stream")].compactMap({ $0 }).first(where: { !$0.isEmpty }) else { return nil }
        // OctoPrint's own snapshot setting points at the Pi's loopback address, which is no use
        // from a phone, so derive the still from the stream address instead.
        var still: String?
        if stream.contains("action=stream") {
            still = stream.replacingOccurrences(of: "action=stream", with: "action=snapshot")
        } else if stream.hasSuffix("/stream") || stream.hasSuffix("/stream/") {
            var s = stream
            while s.hasSuffix("/") { s.removeLast() }
            still = String(s.dropLast("stream".count)) + "snapshot"
        }
        return Webcam(streamUrl: stream, snapshotUrl: still, flipH: cam.bool("flipH") ?? false,
                      flipV: cam.bool("flipV") ?? false, rotate90: cam.bool("rotate90") ?? false)
    }

    /* ---------------- files ---------------- */

    /// Call when something on screen needs the file list. Fetches only if it's missing or stale.
    func wantFiles() {
        if filesStale || (!files.loaded && !files.loading) { refreshFiles() }
    }

    /// The file list is on screen; keep it fresh even while printing.
    func watchFiles(_ watching: Bool) {
        filesWanted = watching
        if watching { wantFiles() }
    }

    func refreshFiles() {
        guard let octo = api, filesTask == nil else { return }
        filesTask = Task {
            defer { filesTask = nil }
            while !Task.isCancelled {
                filesStale = false
                files.loading = true
                files.error = nil
                do {
                    let root = try await octo.files()
                    files = FilesState(loading: false, loaded: true, error: nil, root: root)
                } catch {
                    filesStale = true
                    files.loading = false
                    files.error = forPeople(error)
                    return
                }
                // An upload is followed by its analysis, which changes the listing again.
                if !filesStale || !(filesWanted || printer?.phase == .ready) { return }
            }
        }
    }

    /// The slicer's own preview of a file, when the Slicer Thumbnails plugin pulled one out.
    func thumbnail(_ link: String) async -> UIImage? {
        if let hit = thumbCache.object(forKey: link as NSString) { return hit }
        guard let octo = api, let url = octo.resolve(link),
              let (data, r) = try? await octo.raw(octo.request(url, timeout: 20)), r.statusCode == 200,
              let image = await UIImage(data: data)?.byPreparingForDisplay()
        else { return nil }
        thumbCache.setObject(image, forKey: link as NSString)
        return image
    }

    /* ---------------- actions ---------------- */

    private func act(_ block: @escaping (OctoApi) async throws -> Void) {
        guard let octo = api else { return say("Not connected to OctoPrint.") }
        Task {
            do { try await block(octo) } catch { say(forPeople(error)) }
        }
    }

    func say(_ text: String) {
        notice = text
        noticeTask?.cancel()
        noticeTask = Task {
            try? await Task.sleep(for: .seconds(4.5))
            if !Task.isCancelled { notice = nil }
        }
    }

    func pause() { act { try await $0.pause() } }
    func resume() { act { try await $0.resume() } }
    func cancel() { act { try await $0.cancel() } }
    func print(_ file: PrintFile) { act { try await $0.print(file) } }

    /// Whether the printer's SD card has `file` (the Card Transfer plugin). A file on the card
    /// prints from it; one that isn't gets a choice before it starts. Nil when there is nothing to ask.
    func onCard(_ file: PrintFile) async -> Bool? {
        guard file.origin != sdCard, let octo = api else { return nil }
        return await octo.onCard(file)
    }

    func copyToCardAndPrint(_ file: PrintFile) {
        guard let octo = api else { return say("Not connected to OctoPrint.") }
        Task {
            do {
                try await octo.copyToCardAndPrint(file)
                say("Copying it to the printer’s SD card. The print starts once it’s there.")
            } catch {
                say(forPeople(error))
            }
        }
    }

    func printFromOctoPrint(_ file: PrintFile) { act { try await $0.printFromOctoPrint(file) } }

    /// The layer the nozzle is really on, brought up to date for something that shows it while
    /// the part drawing, which does this every frame, may be off screen.
    func followNozzle() {
        guard let file = jobDrawing ?? jobFile else { return }
        let layers = layers
        nozzle.follow(library: parts, file: PartFile(file), sent: layers.known ? layers.current : nil,
                      fromCard: jobFile?.origin == sdCard)
    }

    /// How high the nozzle is, for a running job: the height of the layer it is really on when that
    /// is followed. OctoPrint's own figure is that of the last move it sent: ahead of the nozzle, up
    /// in the air on every Z-hop, and of a file the printer reads from its SD card there is none.
    var nozzleHeight: Double? {
        if nozzle.layer != nil { return nozzle.height.map(Double.init) }
        if jobFile?.origin == sdCard { return nil }
        return printer?.currentZ
    }

    /// Put the finished job away so the status screen goes back to "Ready".
    func dismissOutcome() {
        if let p = printer { UserDefaults.standard.set(jobId(p), forKey: "clearedJob") }
        outcome = nil
    }

    func connectPrinter() {
        let inError = printer?.phase == .error
        act { octo in
            if inError { try? await octo.disconnectPrinter() }
            try await octo.connectPrinter()
        }
    }

    func setTool(_ c: Int) { act { try await $0.setToolTarget(min(max(c, 0), 300)) } }
    func setBed(_ c: Int) { act { try await $0.setBedTarget(min(max(c, 0), 110)) } }

    func preheat(_ p: HeatPreset) {
        let bed = printer?.hasBed != false
        act { octo in
            try await octo.setToolTarget(p.tool)
            if bed { try await octo.setBedTarget(p.bed) }
        }
    }

    func coolDown() {
        let bed = printer?.hasBed != false
        act { octo in
            try await octo.setToolTarget(0)
            if bed { try await octo.setBedTarget(0) }
        }
    }

    func jog(x: Double = 0, y: Double = 0, z: Double = 0) {
        let speed = prefs.jogSpeed
        act { try await $0.jog(x: x, y: y, z: z, speed: z != 0 ? speed.z : speed.xy) }
    }

    func home(_ axes: [String]) { act { try await $0.home(axes) } }
    func motorsOff() { act { try await $0.gcode(["M84"]) } }

    func extrude(_ mm: Int) {
        let speed = prefs.extrudeSpeed
        act { try await $0.extrude(mm, speed: speed) }
    }

    func setFan(_ percent: Int) { act { try await $0.gcode(["M106 S\(min(max(percent, 0), 100) * 255 / 100)"]) } }

    func changeFeedRate(_ percent: Int) {
        let v = min(max(percent, 50), 150)
        feedRate = v
        act { try await $0.setFeedRate(v) }
    }

    func changeFlowRate(_ percent: Int) {
        let v = min(max(percent, 75), 125)
        flowRate = v
        act { try await $0.setFlowRate(v) }
    }

    func answerPrompt(_ choice: Int) {
        prompt = nil
        act { try await $0.answerPrompt(choice) }
    }

    /// Whether to offer switching everything off: an Echo Show with a smart plug is listening, or
    /// is part-way through switching off.
    var canSwitchOff: Bool {
        guard let d = display else { return false }
        return d.step != nil || (d.plug && d.fresh() && link == .online)
    }

    /// Ask the Echo Show, through the OctoShow plugin, to switch everything off (`off`), to do so
    /// when the print is done (`offWhenDone`), or to leave everything on (`stay`). It decides; this
    /// only asks, and its answer arrives as its next report.
    func askDisplay(_ action: String) {
        guard let octo = api else { return say("Not connected to OctoPrint.") }
        Task {
            do {
                try await octo.askDisplay(action)
            } catch let e as OctoHttpError where e.code == 409 {
                display = nil
                say("The Echo Show isn't listening just now.")
            } catch {
                say(forPeople(error))
            }
        }
    }

    func setLight(_ light: Light, percent: Int) {
        info.lights = info.lights.map { var l = $0; if l.pin == light.pin { l.percent = percent }; return l }
        act { try await $0.setLight(pin: light.pin, percent: percent) }
    }
}

/// The client in use and whether a job is running, for the drawing library's downloads, which
/// run off the main thread.
private final class Shared: @unchecked Sendable {
    private let lock = NSLock()
    private var _api: OctoApi?
    private var _busy = false
    var api: OctoApi? {
        get { lock.withLock { _api } }
        set { lock.withLock { _api = newValue } }
    }
    var busy: Bool {
        get { lock.withLock { _busy } }
        set { lock.withLock { _busy = newValue } }
    }
}

extension PartFile {
    init(_ f: PrintFile) {
        self.init(origin: f.origin, path: f.path, display: f.display, date: f.date, size: f.size)
    }
}

extension PrinterSession {
    /// What to call the printer: the name set in OctoPrint's appearance settings, else the one
    /// it announced itself with, else just "OctoPrint" (an address makes a poor title).
    var displayName: String {
        if !info.name.isEmpty { return info.name }
        let n = server.name.trimmingCharacters(in: .whitespaces)
        return n.isEmpty || n == server.home.host || normalizeBase(n)?.host == server.home.host ? "OctoPrint" : n
    }

    /// Whether previews may be drawn now: the printer is free (a running job has the Pi to
    /// itself) and the phone is at home.
    var mayDrawPreviews: Bool { link == .online && via == .home && printer?.phase.busy == false }

    /// The picture this phone draws from a file's G-code, for files the slicer gave no picture.
    /// One not drawn yet is drawn now if the printer is free and the phone is at home: drawing
    /// means downloading the whole file, which a relay's data allowance won't thank you for.
    func drawnPreview(of file: PrintFile, tint: Color) async -> UIImage? {
        let pf = PartFile(file)
        let cg = UIColor(tint).cgColor
        if let picture = await parts.preview(pf, tint: cg) { return UIImage(cgImage: picture) }
        guard mayDrawPreviews, !parts.settled(pf) else { return nil }
        let shared = shared
        let outcome = await parts.want(pf, gentle: false, keepGoing: { !shared.busy })
        let picture = await parts.preview(pf, tint: cg)
        partsLog.debug("preview of \(file.path, privacy: .public): \(String(describing: outcome), privacy: .public), picture \(picture != nil)")
        return picture.map { UIImage(cgImage: $0) }
    }
}

private let partsLog = Logger(subsystem: "com.drazan.octoshow.ios", category: "parts")
