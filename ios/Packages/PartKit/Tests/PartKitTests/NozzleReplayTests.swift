import Foundation
import XCTest
@testable import PartKit

/**
 * A real file printed on paper: where the nozzle is at each moment by a plain timing model of
 * the printer, the REALPOS lines the firmware would send for it, and what the follower makes of
 * them, set against where the nozzle really was. Also what the figures OctoPrint and the
 * Dashboard plugin count from lines sent would have said at the same moments. Ported from
 * NozzleReplayTest.kt, printing the same report, so the two can be set side by side.
 *
 *   SKETCH=/path/to/file.gcode swift test -c release -Xswiftc -enable-testing --filter NozzleReplayTests
 */
final class NozzleReplayTests: XCTestCase {

    /** One movement command, or a wait during which the printer's queue is empty. */
    private struct Move {
        let t0: Double, t1: Double
        let x0: Float, y0: Float, z0: Float, e0: Float
        let x1: Float, y1: Float, z1: Float, e1: Float
        let layer: Int, inLayer: Int, moving: Bool, zSent: Float
    }

    // the firmware's limits, from Configuration.h of the build
    private var maxXY = 600.0
    private var maxZ = 12.0
    private var maxE = 30.0

    private func plan(_ file: URL) throws -> ([Move], [Int: Int]) {
        var moves = [Move]()
        var perLayer = [Int: Int]()
        var t = 0.0
        var x: Float = 0, y: Float = 0, z: Float = 0, e: Float = 0
        var f = 3000.0
        var absXYZ = true
        var absE = true
        var layer = 0
        func wait(_ seconds: Double) {
            moves.append(Move(t0: t, t1: t + seconds, x0: x, y0: y, z0: z, e0: e, x1: x, y1: y, z1: z, e1: e,
                              layer: layer, inLayer: -1, moving: false, zSent: z))
            t += seconds
        }
        let text = try String(contentsOf: file, encoding: .utf8)
        text.enumerateLines { raw, _ in
            let line = String(raw.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { return }
            let words = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            func v(_ letter: Character) -> Double? {
                words.dropFirst().first { $0.first?.uppercased() == String(letter) }.flatMap { Double($0.dropFirst()) }
            }
            switch words[0].uppercased() {
            case "G0", "G1", "G2", "G3":
                if let it = v("F") { f = it }
                let nx = v("X").map { absXYZ ? Float($0) : x + Float($0) } ?? x
                let ny = v("Y").map { absXYZ ? Float($0) : y + Float($0) } ?? y
                let nz = v("Z").map { absXYZ ? Float($0) : z + Float($0) } ?? z
                let ne = v("E").map { absE ? Float($0) : e + Float($0) } ?? e
                let dx = Double(nx - x), dy = Double(ny - y), dz = Double(nz - z), de = Double(ne - e)
                var path = (dx * dx + dy * dy + dz * dz).squareRoot()
                let arc = words[0].uppercased() != "G1" && words[0].uppercased() != "G0"
                if arc {
                    let r = hypot(v("I") ?? 0, v("J") ?? 0)
                    // Orca's spiral lift is a whole turn back to where it began
                    if dx == 0 && dy == 0 { path = hypot(2 * Double.pi * r, dz) } else { path = max(path, hypot(dx, dy)) }
                }
                var dur = 0.0
                if path > 1e-6 {
                    var speed = f / 60
                    if dx != 0 || dy != 0 { speed = min(speed, self.maxXY * path / max(abs(dx), abs(dy))) }
                    if dz != 0 { speed = min(speed, self.maxZ * path / abs(dz)) }
                    if de != 0 { speed = min(speed, self.maxE * path / abs(de)) }
                    dur = path / speed
                    if dx == 0 && dy == 0 { dur = max(dur, 2 * (abs(dz) / 200).squareRoot()) }
                } else if de != 0 {
                    dur = max(abs(de) / min(f / 60, self.maxE), 2 * (abs(de) / 500).squareRoot())
                }
                let n = perLayer[layer] ?? 0
                perLayer[layer] = n + 1
                if dur > 0 {
                    moves.append(Move(t0: t, t1: t + dur, x0: x, y0: y, z0: z, e0: e, x1: nx, y1: ny, z1: nz, e1: ne,
                                      layer: layer, inLayer: n, moving: true, zSent: v("Z").map(Float.init) ?? moves.last?.zSent ?? z))
                }
                t += dur
                x = nx; y = ny; z = nz; e = ne
            case "G92":
                if let it = v("E") { e = Float(it) }
                if let it = v("Z") { z = Float(it) }
            case "G28": wait(20.0); x = 0; y = 0; z = 5
            case "G4": wait((v("P") ?? 0) / 1000 + (v("S") ?? 0))
            case "G90": absXYZ = true
            case "G91": absXYZ = false
            case "M82": absE = true
            case "M83": absE = false
            // the slicer's own limits replace the firmware's for the print
            case "M203":
                if let it = v("X") { self.maxXY = it }
                if let it = v("Z") { self.maxZ = it }
                if let it = v("E") { self.maxE = it }
            case "M190": wait(90.0)
            case "M109": wait(45.0)
            case "M117":
                if words.count > 1 && words[1] == "DASHBOARD_LAYER_INDICATOR", words.count > 2, let n = Int(words[2]) { layer = n }
            default: break
            }
        }
        return (moves, perLayer)
    }

    /// Java's "%.Nf": the shortest decimal for the number, rounded half up.
    private func fixed(_ v: Double, _ places: Int, sign: Bool = false) -> String {
        // Java keeps the sign of a negative number that rounds to nothing: -0.04 is "-0.0"
        let negative = v.sign == .minus
        var exact = Decimal(string: "\(abs(v))", locale: Locale(identifier: "en_US_POSIX")) ?? Decimal(abs(v))
        var rounded = Decimal()
        NSDecimalRound(&rounded, &exact, places, .plain)
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.minimumFractionDigits = places
        formatter.maximumFractionDigits = places
        formatter.minimumIntegerDigits = 1
        formatter.usesGroupingSeparator = false
        let s = formatter.string(from: rounded as NSDecimalNumber) ?? "\(abs(v))"
        return (negative ? "-" : sign ? "+" : "") + s
    }

    private func pad(_ s: String, _ width: Int) -> String { String(repeating: " ", count: max(0, width - s.count)) + s }

    private func signed(_ n: Int) -> String { n >= 0 ? "+\(n)" : "\(n)" }

    func testFollowingARealFile() throws {
        guard let path = ProcessInfo.processInfo.environment["SKETCH"] else { throw XCTSkip("set SKETCH to a .gcode file from OctoPrint") }
        let file = URL(fileURLWithPath: path)
        let route = try XCTUnwrap(try GcodePreview.read({ InputStream(url: file)! })).route
        try XCTSkipIf(route.layers.isEmpty, "the file has none of the Dashboard plugin's layer marks")
        let (moves, perLayer) = try plan(file)
        print("\(moves.count) moves, \(route.layers.count) layers, \(fixed(moves.last!.t1 / 60, 0)) min")

        // what the firmware sends: every 250 ms while moves are queued, quiet while waiting
        var fixes = [NozzleFix]()
        var next = 0.0
        for m in moves {
            if !m.moving { next = max(next, m.t1); continue }
            while next < m.t1 {
                if next >= m.t0 {
                    let k = Float((next - m.t0) / (m.t1 - m.t0))
                    fixes.append(NozzleFix(
                        x: m.x0 + (m.x1 - m.x0) * k, y: m.y0 + (m.y1 - m.y0) * k, z: m.z0 + (m.z1 - m.z0) * k,
                        e: m.e0 + (m.e1 - m.e0) * k, millis: Int64(next * 1000)
                    ))
                    next += 0.25
                } else {
                    next = m.t0
                }
            }
        }
        // OctoPrint hands the lines over twice a second
        let arrival = fixes.map { Int64((Double($0.millis + 10) / 500.0).rounded(.up) * 500) + 30 }

        let numbers = route.layers.map(\.number), heights = route.layers.map(\.z)
        let nozzle = NozzleFollower()
        nozzle.useLayers(numbers, heights)
        // the same file printed from the SD card, where nothing says which layers have been sent
        let card = NozzleFollower()
        card.useLayers(numbers, heights)
        var cardMapOf = -1
        var cardBehind = [Int: Double]()
        var cardFillErr = 0.0
        var cardFillSamples = 0
        var shownLive = [Int: Double]()
        var shownBehind = [Int: Double]()
        var shownSent = [Int: Double]()
        var heightOff = [String: Double]()
        var liveTime = 0.0
        var doneErr = 0.0
        var doneSamples = 0
        var doneBehindErr = 0.0
        var doneBehindSigned = 0.0
        var fillOff = [Int: Double]()
        var doneBehindSamples = 0
        var leadSum = 0.0
        var leadMax = 0.0
        var leadSamples = 0
        var mapOf = -1
        var fi = 0
        var k = 0
        var kBehind = 0
        var reported = 0
        let frame: Int64 = 16
        var now: Int64 = 0
        let end = Int64(moves.last!.t1 * 1000)
        func along(_ m: Move, _ at: Double) -> Double { m.t1 > m.t0 ? clamp((at - m.t0) / (m.t1 - m.t0), 0, 1) : 1 }
        while now < end {
            let s = Double(now) / 1000.0
            while k < moves.count - 1 && moves[k].t1 <= s { k += 1 }
            // the display plays the readings back this far behind, so this is where it means to show the nozzle
            let behind = s - 0.7
            while kBehind < moves.count - 1 && moves[kBehind].t1 <= behind { kBehind += 1 }
            let m = moves[k]
            let truth = m.layer
            // lines go out one at a time as the 32-move queue makes room: what has been sent is that far on
            let sent = moves[min(k + 33, moves.count - 1)]
            let sentLayer = sent.layer

            while fi < fixes.count && arrival[fi] <= now { nozzle.add(fixes[fi], arrivedAt: arrival[fi]); card.add(fixes[fi], arrivedAt: arrival[fi]); fi += 1 }
            let live = nozzle.look(now, sent: sentLayer >= 1 ? sentLayer : nil)
            let shown = nozzle.layer
            if let shown, shown != mapOf {
                if let l = route.layers.first(where: { $0.number == shown }) { nozzle.useMap(try LayerMap(route, l)) }
                mapOf = shown
            }
            card.look(now, sent: NozzleFollower.ANY_LAYER)
            let cardShown = card.layer
            if let cardShown, cardShown != cardMapOf {
                if let l = route.layers.first(where: { $0.number == cardShown }) { card.useMap(try LayerMap(route, l)) }
                cardMapOf = cardShown
            }
            if truth >= 1 && m.moving {
                shownSent[sentLayer - truth, default: 0] += 1
                let lead = max(0.0, sent.t0 - s)
                leadSum += lead; leadMax = max(leadMax, lead); leadSamples += 1
                let layerZ = route.layers.first { $0.number == truth }?.z ?? m.z1
                let dz = sent.zSent - layerZ
                let where_: String = abs(dz) < 0.01 ? "this layer" : dz > 0.3 ? "a hop or more above" : dz > 0 ? "the next layer" : "below"
                heightOff[where_, default: 0] += 1
                let mb = moves[kBehind]
                if live, let cardShown {
                    cardBehind[cardShown - mb.layer, default: 0] += 1
                    if cardShown == mb.layer && mb.inLayer >= 0, let total = perLayer[mb.layer] {
                        cardFillErr += abs(Double(card.done) - (Double(mb.inLayer) + along(mb, behind)) / Double(total))
                        cardFillSamples += 1
                    }
                }
                if live, let shown {
                    liveTime += 1
                    shownLive[shown - truth, default: 0] += 1
                    shownBehind[shown - mb.layer, default: 0] += 1
                    if shown == truth, let total = perLayer[truth] {
                        doneErr += abs(Double(nozzle.done) - (Double(m.inLayer) + along(m, s)) / Double(total))
                        doneSamples += 1
                    }
                    if shown == mb.layer && mb.inLayer >= 0, let total = perLayer[mb.layer] {
                        let off = Double(nozzle.done) - (Double(mb.inLayer) + along(mb, behind)) / Double(total)
                        doneBehindErr += abs(off)
                        doneBehindSigned += off
                        fillOff[kRound(off * 20), default: 0] += 1
                        doneBehindSamples += 1
                    }
                    if shown - truth >= 2 && reported < 4 {
                        reported += 1
                        let around = fixes.filter { $0.millis >= now - 2200 && $0.millis <= now - 300 }
                        print("at \(fixed(s, 0)) s the nozzle is on layer \(truth) (z \(fixed(Double(m.z1), 2))) and the display says \(shown). Readings before:")
                        for r in around { print("   \(fixed(Double(r.x), 2)) \(fixed(Double(r.y), 2)) z \(fixed(Double(r.z), 2)) e \(fixed(Double(r.e), 2)) at \(r.millis)") }
                    }
                }
            }
            now += frame
        }
        func table(_ name: String, _ h: [Int: Double]) {
            let all = h.values.reduce(0, +)
            print(name)
            for d in h.keys.sorted() { print("   \(signed(d)) layers: \(pad(fixed(100 * h[d]! / all, 1), 5))%") }
        }
        print("readings coming in for \(fixed(100 * liveTime / Double(leadSamples), 1))% of the printing time")
        table("layer shown from REALPOS against the real one", shownLive)
        table("layer shown from REALPOS against the one 0.7 s before, which it means to show", shownBehind)
        table("layer sent (Dashboard) against the real one", shownSent)
        print("fill through the layer from REALPOS, when on the right layer: off by \(fixed(100 * doneErr / Double(max(1, doneSamples)), 1))% on average")
        print("the same against 0.7 s before: off by \(fixed(100 * doneBehindErr / Double(max(1, doneBehindSamples)), 1))% on average, "
              + "\(fixed(100 * doneBehindSigned / Double(max(1, doneBehindSamples)), 1, sign: true))% counting ahead as plus and behind as minus")
        let fills = fillOff.values.reduce(0, +)
        for d in fillOff.keys.sorted() { print("   \(pad(signed(d * 5), 4))%: \(pad(fixed(100 * fillOff[d]! / fills, 1), 5))%") }
        table("printed from the SD card: layer shown against the one 0.7 s before", cardBehind)
        print("printed from the SD card: fill off by \(fixed(100 * cardFillErr / Double(max(1, cardFillSamples)), 1))% on average")
        print("what has been sent is \(fixed(leadSum / Double(leadSamples), 1)) s ahead of the nozzle on average, \(fixed(leadMax, 1)) s at most")
        let hs = heightOff.values.reduce(0, +)
        print("Height (OctoPrint's last Z sent) shows: " + heightOff.keys.sorted().map { "\($0) \(fixed(100 * heightOff[$0]! / hs, 1))%" }.joined(separator: ", "))
    }
}
