import Foundation

/**
 * One reading of where the nozzle really is, taken from the printer's motors, in
 * millimetres. [millis] is the printer's own clock when it was read.
 *
 * What OctoPrint and its plugins report is what has been sent to the printer, and the
 * printer holds a queue of moves: that can be most of a layer ahead of the nozzle. The
 * only way to know where the nozzle is, is for the firmware to say. Stock firmware doesn't
 * (`M114` and `M154` report the end of the queue), so this needs one built to.
 */
public struct NozzleFix: Hashable, Sendable {
    public let x: Float
    public let y: Float
    public let z: Float
    public let e: Float
    public let millis: Int64

    public init(x: Float, y: Float, z: Float, e: Float, millis: Int64) {
        self.x = x; self.y = y; self.z = z; self.e = e; self.millis = millis
    }
}

/** What the firmware starts each reading with. OctoPrint's terminal log can be filtered on it. */
public let NOZZLE_MARK = "REALPOS "

/**
 * Reads "REALPOS <x> <y> <z> <e> <millis>", which may have "Recv: " and the like before it.
 * The numbers are bare on purpose: OctoPrint takes a line with a "T:" in it for temperatures,
 * and one with "X:", "Y:" and "Z:" for a position of its own.
 */
public func parseNozzleFix(_ line: String) -> NozzleFix? {
    guard let at = line.range(of: NOZZLE_MARK) else { return nil }
    let rest = line[at.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
    // split on single spaces, keeping empty pieces, as Kotlin's split(' ') does
    let parts = rest.split(separator: " ", omittingEmptySubsequences: false)
    if parts.count != 5 { return nil }
    guard let x = kotlinFloat(parts[0]), let y = kotlinFloat(parts[1]), let z = kotlinFloat(parts[2]),
          let e = kotlinFloat(parts[3]), let millis = Int64(parts[4]) else { return nil }
    if !x.isFinite || !y.isFinite || !z.isFinite || !e.isFinite || millis < 0 { return nil }
    return NozzleFix(x: x, y: y, z: z, e: e, millis: millis)
}

/// Kotlin's String.toFloatOrNull for the plain decimals firmware prints (and its "NaN", "Infinity").
private func kotlinFloat(_ s: Substring) -> Float? {
    if s.isEmpty { return nil }
    switch s {
    case "NaN", "+NaN", "-NaN": return .nan
    case "Infinity", "+Infinity": return .infinity
    case "-Infinity": return -.infinity
    default: break
    }
    // Swift's own parser also takes "nan", "inf" and hex, which Java's does not
    for c in s.unicodeScalars where !("0"..."9").contains(c) && c != "." && c != "-" && c != "+" && c != "e" && c != "E" {
        return nil
    }
    return Float(s)
}

/**
 * Plays readings back in step with when they were taken. They reach the display in
 * batches, about twice a second, so what shows is where the nozzle was a moment ago, drawn
 * only from real readings: nothing here guesses where it has got to since.
 */
public final class NozzleTrack {
    private var fixes = [NozzleFix]()
    /** The display's clock less the printer's, for a reading that got here as fast as any has. */
    private var offset = Int64.max
    private var lastArrival: Int64 = -1

    public private(set) var x: Float = 0
    public private(set) var y: Float = 0
    /** While [laying], the lower of the two readings' heights: see [sample]. */
    public private(set) var z: Float = 0
    /** Plastic is going down: the extruder moved on between the two readings around this moment. */
    public private(set) var laying = false
    /**
     * The readings either side of this moment, and how far it is from the first to the second,
     * 0..1. The nozzle was at both, but it needn't have gone straight from one to the other.
     */
    public private(set) var before: NozzleFix?
    public private(set) var after: NozzleFix?
    public private(set) var along: Float = 0

    public init() {}

    public func add(_ fix: NozzleFix, arrivedAt: Int64) {
        // a printer that has been restarted has started its clock again
        if let last = fixes.last, fix.millis < last.millis { clear() }
        fixes.append(fix)
        if fixes.count > NozzleTrack.KEEP_FIXES { fixes.removeFirst() }
        offset = min(offset, arrivedAt - fix.millis)
        lastArrival = arrivedAt
    }

    public func clear() {
        fixes.removeAll()
        offset = Int64.max
        lastArrival = -1
        laying = false
        before = nil
        after = nil
    }

    /** Whether readings are coming in at all. */
    public func isLive(_ now: Int64) -> Bool { lastArrival >= 0 && now - lastArrival <= NozzleTrack.STALE_MS }

    /** Works out where the nozzle was for what is on screen at [now]. False if there is no telling. */
    @discardableResult
    public func sample(_ now: Int64) -> Bool {
        if !isLive(now) { return false }
        let wanted = now &- offset &- NozzleTrack.REPLAY_DELAY_MS
        while fixes.count > 2 && fixes[1].millis <= wanted { fixes.removeFirst() }
        let a = fixes[0]
        if fixes.count < 2 || wanted <= a.millis {
            moveTo(a, a, 0, a.z, false)
            return true
        }
        let b = fixes[1]
        let t: Float = b.millis > a.millis ? clamp(Float(wanted - a.millis) / Float(b.millis - a.millis), 0, 1) : 1
        // Plastic went down between the two readings, and it went down at the lower one's
        // height. When they differ the nozzle hopped clear of the layer between them, and the
        // extruder pushing back what it drew in before the hop doesn't lay anything up there.
        let extruding = b.e > a.e
        moveTo(a, b, t, extruding ? min(a.z, b.z) : a.z + (b.z - a.z) * t, extruding)
        return true
    }

    private func moveTo(_ from: NozzleFix, _ to: NozzleFix, _ t: Float, _ atZ: Float, _ extruding: Bool) {
        before = from
        after = to
        along = t
        x = from.x + (to.x - from.x) * t
        y = from.y + (to.y - from.y) * t
        z = atZ
        laying = extruding
    }

    /** Long enough that the batch holding the reading for this moment has always arrived. */
    static let REPLAY_DELAY_MS: Int64 = 700
    static let KEEP_FIXES = 32
    /** With nothing for this long the firmware has stopped saying, and other figures take over. */
    static let STALE_MS: Int64 = 3_000
}

/** One layer's map from a spot on the bed to when in the layer it is laid. See [GcodePreview.Route]. */
public final class LayerMap: @unchecked Sendable {
    public let number: Int
    private let originX: Float
    private let originY: Float
    private let cell: Float
    private let left: Int
    private let top: Int
    private let width: Int
    private let height: Int
    private let squares: [UInt8]

    public init(_ route: GcodePreview.Route, _ layer: GcodePreview.Route.Layer) throws {
        number = layer.number
        originX = route.originX
        originY = route.originY
        cell = route.cell
        left = layer.left
        top = layer.top
        width = layer.width
        height = layer.height
        squares = try layer.unpacked()
    }

    /** When the spot at ([x], [y]) is laid: 1..255, or 0 for never. */
    public func momentAt(_ x: Float, _ y: Float) -> Int {
        let col = kInt((x - originX) / cell) - left
        let row = kInt((y - originY) / cell) - top
        if col < 0 || row < 0 || col >= width || row >= height { return 0 }
        return Int(squares[row * width + col])
    }
}

/**
 * Turns the nozzle's real position into the layer it is on and how far through it has got.
 * Only plastic going down counts: on a travel the nozzle crosses spots that are laid much
 * later, and hops up past layers it hasn't reached.
 */
public final class NozzleFollower {
    private let track = NozzleTrack()
    private var numbers = [Int]()
    private var heights = [Float]()
    private var map: LayerMap?
    private var moment = 0
    /** How long the nozzle has been laying somewhere further on than it should have got to, in looks. */
    private var beyond = 0

    /** The layer the nozzle is on, as the Dashboard plugin numbers them; nil until that is known. */
    public private(set) var layer: Int?
    /** How high that layer is, in millimetres. */
    public private(set) var height: Float = 0
    /** How far through that layer the nozzle has got, 0..1. */
    public private(set) var done: Float = 0

    public init() {}

    /** The file's layers: their numbers, and how high each is, from the bottom up. */
    public func useLayers(_ layerNumbers: [Int], _ layerHeights: [Float]) {
        numbers = layerNumbers
        heights = layerHeights
    }

    public var knowsLayers: Bool { !numbers.isEmpty }

    /** The map of the layer asked for by [layer] has been read. */
    public func useMap(_ layerMap: LayerMap) {
        if layerMap.number == layer { map = layerMap }
    }

    public func add(_ fix: NozzleFix, arrivedAt: Int64) { track.add(fix, arrivedAt: arrivedAt) }

    public func reset() {
        track.clear()
        numbers = []
        heights = []
        map = nil
        layer = nil
        moment = 0
        beyond = 0
        done = 0
    }

    /**
     * Catches up with [now]. [sent] is the last layer sent to the printer, as the Dashboard
     * plugin reports it, or nil before the first, or [ANY_LAYER] when nothing says, as when
     * the printer reads the file from its own SD card. False when there are no readings to go on.
     */
    @discardableResult
    public func look(_ now: Int64, sent: Int?) -> Bool {
        if !track.sample(now) { return false }
        if track.laying && !numbers.isEmpty {
            // With nothing to say which layers have been sent, only two readings at one height
            // say which layer it is. Two at different heights straddle a hop: within a quarter
            // of a second the nozzle can come down, print a little and go back up, and both
            // readings be in the air. Otherwise what has been sent rules that out, and waiting
            // for a pair at one height would only make each new layer show later.
            let level: Bool
            if let from = track.before, let to = track.after { level = abs(from.z - to.z) <= NozzleFollower.SAME_LAYER_MM } else { level = false }
            if level || sent != NozzleFollower.ANY_LAYER { findLayer(track.z, sent) }
            findMoment()
        }
        return true
    }

    /**
     * The layer at height [z], and it only ever goes up: the first as high as the nozzle is.
     * Never one the printer hasn't been sent yet, though: the purge line before the first
     * layer is laid a little higher than it.
     */
    private func findLayer(_ z: Float, _ sent: Int?) {
        guard let sent = sent else { return }
        var i = 0
        while i < heights.count - 1 && heights[i] < z - NozzleFollower.SAME_LAYER_MM { i += 1 }
        while i > 0 && numbers[i] > sent { i -= 1 }
        let found = numbers[i]
        if found > sent { return }
        if sent == NozzleFollower.ANY_LAYER {
            // With nothing to say what has been sent, only plastic at a layer's own height says
            // which layer it is. A hop up or down passes through heights in between, and the
            // extruder can still come out a shade ahead across it.
            if z < heights[i] - NozzleFollower.SAME_LAYER_MM { return }
            // Just above the first layer, before any layer is known, it is as likely the purge
            // line: wait for a clearer answer.
            if layer == nil && i == 1 { return }
        }
        if layer == nil || found > layer! {
            layer = found
            height = heights[i]
            map = nil
            moment = 0
            beyond = 0
            done = 0
        }
    }

    /**
     * How far through the layer the nozzle has got: when the map has it at the readings either
     * side of this moment, and in between those by the time. Not the map at a point on the
     * straight line between them, which on a turn round an outline lies inside it, where the
     * infill goes later.
     */
    private func findMoment() {
        guard let map = map, let from = track.before, let to = track.after else { return }
        // a reading above the layer is in the air, hopped up for a travel to or from there
        let first = from.z > height + NozzleFollower.SAME_LAYER_MM ? 0 : map.momentAt(from.x, from.y)
        let second = to.z > height + NozzleFollower.SAME_LAYER_MM ? 0 : map.momentAt(to.x, to.y)
        let at: Int
        if first == 0 {
            at = second
        } else if second <= first || second - first > NozzleFollower.MOST_AT_ONCE {
            // Off the part by then, or over plastic laid earlier, or further on than a quarter
            // of a second's printing gets: a travel straight after printing ends somewhere laid
            // later, and going a little way towards that every frame would never look like a jump.
            at = first
        } else {
            at = first + kRound(Float(second - first) * track.along)
        }
        if at <= moment { return }
        // Near enough to where it was, it is simply carrying on, and the first plastic seen on a
        // layer says where it is, however far through this display came in. Much further on than
        // that, it is laying over something already there, unless it stays that way: then this
        // display has lost track.
        if moment > 0 && at - moment > NozzleFollower.MOST_AT_ONCE {
            beyond += 1
            if beyond < NozzleFollower.LOST_AFTER_LOOKS { return }
        }
        beyond = 0
        moment = at
        done = Float(max(0, moment - 1)) / Float(GcodePreview.MOMENTS - 1)
    }

    /** For [look]: any layer may have been sent, as nothing reports which. */
    public static let ANY_LAYER = Int(Int32.max)
    /** Heights are stored to a hundredth of a millimetre, and a seam can start a shade low. */
    static let SAME_LAYER_MM: Float = 0.03
    /** About an eighth of a layer. */
    static let MOST_AT_ONCE = 32
    /** Half a second or so at the rate the screen looks. */
    static let LOST_AFTER_LOOKS = 30
}
