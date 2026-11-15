import Foundation

/// What the printer is doing, collapsed from OctoPrint's state flags and state text.
/// Ported from the Echo Show app's `Phase`; the order matters, as everything from
/// `starting` on means a job owns the printer.
enum Phase: Int, Comparable {
    case offline, connecting, error, ready
    case starting, printing, pausing, paused, resuming, cancelling, finishing

    static func < (a: Phase, b: Phase) -> Bool { a.rawValue < b.rawValue }

    /// A job owns the printer: moving the head by hand is off limits.
    var busy: Bool { self >= .starting }
    var canPause: Bool { self == .printing || self == .starting }
    var canCancel: Bool { [.printing, .starting, .paused, .pausing, .resuming].contains(self) }
}

/// How fast the head moves by hand, in mm/min. Z has figures of its own, being by far
/// the slower axis.
enum JogSpeed: String, CaseIterable, Identifiable {
    case slow, normal, fast
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var xy: Int { switch self { case .slow: 600; case .normal: 3000; case .fast: 9000 } }
    var z: Int { switch self { case .slow: 60; case .normal: 180; case .fast: 300 } }
}

struct Temp: Equatable {
    var actual: Double?
    var target: Double
    var heating: Bool { target > 0 && (actual ?? 0) < target - 3 }
    var on: Bool { target > 0 }
}

struct JobFile: Equatable {
    var name: String
    var display: String
    var path: String
    var origin: String
}

struct PrinterSnapshot: Equatable {
    var phase: Phase
    var stateText: String
    var errorText: String?
    var file: JobFile?
    /// Slicer / analysis estimate for the whole job, seconds.
    var estimatedPrintTime: Double?
    /// 0...100, by position in the file.
    var completion: Double?
    var printTime: Int?
    var printTimeLeft: Int?
    var currentZ: Double?
    var tool: Temp?
    var bed: Temp?

    /// A printer that reports a nozzle and no bed has none. One that reports nothing at all,
    /// because it isn't connected, is taken to have one, so the screen doesn't rearrange itself.
    var hasBed: Bool { bed != nil || tool == nil }

    /// How far along, 0...1. By time when OctoPrint has a remaining-time estimate, because a
    /// file position says little about a print whose first layers are the slow ones.
    var fraction: Double {
        if let spent = printTime, let left = printTimeLeft, spent + left > 0 {
            return min(max(Double(spent) / Double(spent + left), 0), 1)
        }
        return min(max((completion ?? 0) / 100, 0), 1)
    }
}

/// Layer progress as reported by the Dashboard plugin, when it is installed.
struct LayerInfo: Equatable {
    /// 1-based number of the layer being printed.
    var current: Int?
    var total: Int?
    /// 0...100 through the current layer.
    var layerProgress: Int?
    var fanPercent: Int?
    var known: Bool { (current ?? 0) > 0 && (total ?? 0) > 0 }
}

struct TempSample: Equatable {
    var tool: Double?
    var bed: Double?
}

/// How the last job ended, kept until the next one starts.
struct Outcome: Equatable {
    enum Kind { case done, failed, cancelled }
    var kind: Kind
    var fileName: String
    var seconds: Int?
}

struct PrintFile: Identifiable, Hashable {
    var name: String
    var display: String
    var path: String
    var origin: String
    var isFolder: Bool
    var size: Int64
    /// Upload time, epoch seconds.
    var date: Int64
    var estimatedPrintTime: Double?
    var filamentCm3: Double?
    /// Relative URL of the slicer preview, when the Slicer Thumbnails plugin extracted one.
    var thumbnail: String?
    var lastPrintSuccess: Bool?
    var children: [PrintFile] = []

    var id: String { "\(origin):\(path)" }
}

struct HeatPreset: Hashable {
    var name: String
    var tool: Int
    var bed: Int
}

struct Webcam: Equatable {
    var streamUrl: String
    /// Where a single still can be fetched, when that can be worked out from the stream address.
    var snapshotUrl: String?
    var flipH = false
    var flipV = false
    var rotate90 = false
}

struct Light: Identifiable, Equatable {
    var name: String
    var pin: Int
    var dimmable: Bool
    var percent: Int = 0
    var id: Int { pin }
}

/// A question the printer is asking through OctoPrint, e.g. after a filament runout.
struct Prompt: Equatable {
    var text: String
    var choices: [String]
}

/// What we learn about the server once, after connecting.
struct ServerInfo: Equatable {
    var name = ""
    var version = ""
    var presets: [HeatPreset] = []
    var webcam: Webcam?
    var lights: [Light] = []
}

/// OctoPrint's name for the printer's own SD card, as a file's origin.
let sdCard = "sdcard"

/* ---- loose JSON reading: OctoPrint and its plugins are relaxed about types ---- */

typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func str(_ key: String) -> String? {
        switch self[key] {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }
    func dbl(_ key: String) -> Double? {
        switch self[key] {
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            let d = n.doubleValue
            return d.isFinite ? d : nil
        case let s as String:
            let t = s.trimmingCharacters(in: .whitespaces)
            return Double(t.hasSuffix("%") ? String(t.dropLast()) : t).flatMap { $0.isFinite ? $0 : nil }
        default: return nil
        }
    }
    func int(_ key: String) -> Int? { dbl(key).map { Int($0) } }
    func bool(_ key: String) -> Bool? {
        switch self[key] {
        case let b as Bool: return b
        case let s as String: return s.lowercased() == "true" ? true : (s.lowercased() == "false" ? false : nil)
        default: return nil
        }
    }
    func obj(_ key: String) -> JSON? { self[key] as? JSON }
    func arr(_ key: String) -> [Any]? { self[key] as? [Any] }
}

/* ---------------- the Echo Show, through the OctoShow plugin ---------------- */

/// How far a switch-off run by the Echo Show display has got. See octoprint-plugin/README.md.
struct PowerStepInfo: Equatable {
    enum Kind: String {
        case confirm, afterPrint, cooling, shuttingDown, settling, off, failed
    }
    var kind: Kind
    var seconds: Int?
    var nozzle: Int?
    var message: String?

    init(kind: Kind, seconds: Int? = nil, nozzle: Int? = nil, message: String? = nil) {
        self.kind = kind
        self.seconds = seconds
        self.nozzle = nozzle
        self.message = message
    }

    init?(_ o: JSON?) {
        guard let o, let kind = o.str("kind").flatMap(Kind.init(rawValue:)) else { return nil }
        self.init(kind: kind, seconds: o.int("seconds"), nozzle: o.int("nozzle"), message: o.str("message"))
    }

    /// Past the point of no return: OctoPrint has been told to shut the Pi down.
    var committed: Bool { [.shuttingDown, .settling, .off].contains(kind) }
    /// Still early enough for "leave everything on" to stop it.
    var canBackOut: Bool { [.confirm, .afterPrint, .cooling, .failed].contains(kind) }
}

/// What the Echo Show display last said about itself through the OctoShow plugin.
struct DisplayStatus: Equatable {
    var name: String
    /// A smart plug is set up on the display: without one it has nothing to switch off with.
    var plug: Bool
    var plugOn: Bool?
    /// The display switches itself off too at the end.
    var canGoOff: Bool
    var offWhenDone: Bool
    var step: PowerStepInfo?
    /// When it said so.
    var heard: Date

    init?(_ o: JSON?, now: Date = Date()) {
        guard let o else { return nil }
        name = o.str("name").flatMap { $0.isEmpty ? nil : $0 } ?? "Echo Show"
        plug = o.bool("plug") ?? false
        plugOn = o.bool("plugOn")
        canGoOff = o.bool("canGoOff") ?? false
        offWhenDone = o.bool("offWhenDone") ?? false
        step = PowerStepInfo(o.obj("step"))
        heard = now.addingTimeInterval(-(o.dbl("age") ?? 0))
    }

    /// A display that hasn't reported for this long may have gone; the plugin reckons the same.
    static let freshFor: TimeInterval = 90
    func fresh(at now: Date = Date()) -> Bool { now.timeIntervalSince(heard) <= Self.freshFor }
}
