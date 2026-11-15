import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import PartKit

/**
 * Real (or realistic) G-code, drawn, for looking at and for checking against the Android app.
 * Skipped unless asked for:
 *
 *   GCODE_SAMPLES=/folder/of/gcode [PARTKIT_OUT=/folder] swift test -c release --filter SamplesTests
 *
 * draws every file as the Android app's PreviewSamplesTest does, to PARTKIT_OUT (or a folder
 * in the temporary directory) as <name>.png: filament color times brightness, over the panel.
 *
 * With KOTLIN_DUMPS=/folder as well, holding what the Android app's own GcodePreview.kt made of
 * the same files (raw: see the dumper this was checked with), every picture is compared to it
 * pixel by pixel, and the shape and the route number by number.
 */
final class SamplesTests: XCTestCase {

    private var samples: [URL] {
        guard let folder = ProcessInfo.processInfo.environment["GCODE_SAMPLES"] else { return [] }
        let files = (try? FileManager.default.contentsOfDirectory(at: URL(fileURLWithPath: folder), includingPropertiesForKeys: nil)) ?? []
        return files.filter { ["gcode", "gco", "g"].contains($0.pathExtension.lowercased()) }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private var out: URL {
        let dir = ProcessInfo.processInfo.environment["PARTKIT_OUT"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("partkit-previews")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func testDrawEverySample() throws {
        let files = samples
        try XCTSkipIf(files.isEmpty, "set GCODE_SAMPLES to a folder of G-code")
        for file in files {
            let began = Date()
            let picture = try GcodePreview.draw({ InputStream(url: file)! })
            let took = Date().timeIntervalSince(began)
            print(String(format: "%-50@ %6.1f MB  %6.0f ms", file.deletingPathExtension().lastPathComponent as NSString,
                         Double((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) / 1e6, took * 1000))
            let p = try XCTUnwrap(picture, "nothing drawn for \(file.lastPathComponent)")
            try writePNG(onPanel(p), to: out.appendingPathComponent(file.deletingPathExtension().lastPathComponent + ".png"))
        }
    }

    /** The picture as the display shows it: filament color times brightness, over the panel color. */
    private func onPanel(_ p: GcodePreview.Picture) -> CGImage {
        let filament: [Float] = [0xE9, 0xE4, 0xD8]
        let panel: [Float] = [0x12, 0x14, 0x16]
        var bytes = [UInt8](repeating: 255, count: p.width * p.height * 4)
        for i in 0..<(p.width * p.height) {
            let shown = p.shown(i)
            let light = Float(shown & 0xFF) / 255
            let cover = Float(shown >> 24) / 255
            for c in 0..<3 {
                bytes[4 * i + c] = UInt8(clamp(kInt(filament[c] * light * cover + panel[c] * (1 - cover)), 0, 255))
            }
        }
        return Images.rgba(bytes, width: p.width, height: p.height)!
    }

    private func writePNG(_ image: CGImage, to url: URL) throws {
        let dest = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(dest, image, nil)
        XCTAssert(CGImageDestinationFinalize(dest))
    }

    /* ---------------- against the Android app ---------------- */

    private struct Dumped {
        let width: Int, height: Int
        let light: [UInt8], alpha: [UInt8], fresh: [UInt8], shadow: [UInt8]
        let reached: [UInt8]?
        let trail: [Float]?
    }

    private final class Big {
        let bytes: [UInt8]
        var at = 0
        init(_ url: URL) throws { bytes = [UInt8](try Data(contentsOf: url)) }
        func int() -> Int {
            let u = UInt32(bytes[at]) << 24 | UInt32(bytes[at + 1]) << 16 | UInt32(bytes[at + 2]) << 8 | UInt32(bytes[at + 3])
            at += 4
            return Int(Int32(bitPattern: u))
        }
        func float() -> Float { Float(bitPattern: UInt32(bitPattern: Int32(truncatingIfNeeded: int()))) }
        func double() -> Double {
            let hi = UInt64(UInt32(bitPattern: Int32(truncatingIfNeeded: int())))
            let lo = UInt64(UInt32(bitPattern: Int32(truncatingIfNeeded: int())))
            return Double(bitPattern: hi << 32 | lo)
        }
        func bool() -> Bool { defer { at += 1 }; return bytes[at] != 0 }
        func take(_ n: Int) -> [UInt8] { defer { at += n }; return Array(bytes[at..<(at + n)]) }
    }

    private func dumped(_ url: URL) throws -> Dumped {
        let src = try Big(url)
        let w = src.int(), h = src.int()
        let light = src.take(w * h), alpha = src.take(w * h), fresh = src.take(w * h), shadow = src.take(w * h)
        let reached = src.bool() ? src.take(w * h) : nil
        let trail: [Float]? = src.bool() ? (0..<(2 * GcodePreview.MOMENTS)).map { _ in src.float() } : nil
        return Dumped(width: w, height: h, light: light, alpha: alpha, fresh: fresh, shadow: shadow, reached: reached, trail: trail)
    }

    /// How far apart two channels are: the largest difference, the mean, and how many pixels differ at all.
    private func apart(_ a: [UInt8], _ b: [UInt8]) -> (max: Int, mean: Double, differ: Int) {
        var most = 0, sum = 0, differ = 0
        for i in a.indices {
            let d = abs(Int(a[i]) - Int(b[i]))
            if d > most { most = d }
            sum += d
            if d != 0 { differ += 1 }
        }
        return (most, Double(sum) / Double(max(a.count, 1)), differ)
    }

    private var report = [String]()

    private func compare(_ name: String, _ swift: GcodePreview.Picture, _ kotlin: Dumped) -> Bool {
        XCTAssertEqual(swift.width, kotlin.width, name)
        XCTAssertEqual(swift.height, kotlin.height, name)
        var line = String(format: "%-44@", name as NSString)
        var same = true
        for (channel, a, b) in [("light", swift.light, kotlin.light), ("alpha", swift.alpha, kotlin.alpha),
                                ("fresh", swift.fresh, kotlin.fresh), ("shadow", swift.shadow, kotlin.shadow)] {
            let d = apart(a, b)
            line += String(format: "  %@ max %3d mean %.4f (%d px)", channel as NSString, d.max, d.mean, d.differ)
            if d.max != 0 { same = false }
        }
        XCTAssertEqual(swift.reached == nil, kotlin.reached == nil, name)
        if let a = swift.reached, let b = kotlin.reached {
            let d = apart(a, b)
            line += String(format: "  reached max %3d mean %.4f (%d px)", d.max, d.mean, d.differ)
            if d.max != 0 { same = false }
        }
        XCTAssertEqual(swift.trail == nil, kotlin.trail == nil, name)
        if let a = swift.trail, let b = kotlin.trail {
            let d = zip(a, b).map { abs($0 - $1) }.max() ?? 0
            line += String(format: "  trail max %.2g", d)
            if d != 0 { same = false }
        }
        report.append(line)
        return same
    }

    func testTheSameAsTheAndroidApp() throws {
        let files = samples
        guard let dumps = ProcessInfo.processInfo.environment["KOTLIN_DUMPS"].map({ URL(fileURLWithPath: $0) }) else {
            throw XCTSkip("set GCODE_SAMPLES and KOTLIN_DUMPS")
        }
        try XCTSkipIf(files.isEmpty, "set GCODE_SAMPLES to a folder of G-code")
        var pictures = 0, identical = 0
        for file in files {
            let base = file.deletingPathExtension().lastPathComponent
            let began = Date()
            guard let reading = try GcodePreview.read({ InputStream(url: file)! }) else {
                XCTAssertFalse(FileManager.default.fileExists(atPath: dumps.appendingPathComponent("\(base).shape").path), "\(base): nothing read here")
                report.append("\(base): nothing to draw, here as there")
                continue
            }
            let read = Date().timeIntervalSince(began)
            let shape = reading.shape
            let route = reading.route

            // the shape, number for number
            let s = try Big(dumps.appendingPathComponent("\(base).shape"))
            XCTAssertEqual(s.float(), shape.originX, base); XCTAssertEqual(s.float(), shape.originY, base); XCTAssertEqual(s.float(), shape.cell, base)
            XCTAssertEqual(s.int(), shape.cols, base); XCTAssertEqual(s.int(), shape.rows, base); XCTAssertEqual(s.float(), shape.top, base)
            XCTAssertEqual(s.int(), shape.stack.slices, base); XCTAssertEqual(s.float(), shape.stack.slice, base)
            var heightsOff = 0
            for h in shape.height where s.float() != h { heightsOff += 1 }
            XCTAssertEqual(heightsOff, 0, "\(base): heights that differ")
            var planesOff = 0
            for slice in 0..<shape.stack.slices {
                for plane in [shape.stack.part[slice], shape.stack.extra[slice]] {
                    let there = s.bool()
                    if there != (plane != nil) { planesOff += 1; if there { _ = s.take(4 * shape.stack.words * shape.rows) }; continue }
                    if let plane {
                        for i in 0..<plane.count where UInt32(bitPattern: Int32(truncatingIfNeeded: s.int())) != plane[i] { planesOff += 1 }
                    }
                }
            }
            XCTAssertEqual(planesOff, 0, "\(base): stack words that differ")
            var viewsOff = 0
            for turn in 0..<GcodePreview.TURNS {
                let v = shape.view(turn)
                let kotlin = [s.double()]
                let floats = (0..<7).map { _ in s.float() }
                let mine = [v.right, v.up, v.wide, v.tall, v.long, v.broad, v.high]
                // The azimuth comes out of atan2, sin and cos, and Darwin's libm can be a unit in the
                // last place of a double away from the JVM's. That is far below anything a float of the
                // view or a pixel can show, which are held to being identical; it is reported, not failed.
                if kotlin[0] != v.azimuth {
                    report.append("\(base) view \(turn): azimuth \(kotlin[0]) there, \(v.azimuth) here, \((kotlin[0] - v.azimuth).magnitude / v.azimuth.ulp) ulp")
                }
                if abs(kotlin[0] - v.azimuth) > 1e-12 || floats != mine {
                    viewsOff += 1
                    report.append("\(base) view \(turn): \(floats) there, \(mine) here")
                }
            }
            XCTAssertEqual(viewsOff, 0, "\(base): views that differ")

            // the route, square for square
            let r = try Big(dumps.appendingPathComponent("\(base).route"))
            XCTAssertEqual(r.float(), route.originX, base); XCTAssertEqual(r.float(), route.originY, base); XCTAssertEqual(r.float(), route.cell, base)
            XCTAssertEqual(r.int(), route.cols, base); XCTAssertEqual(r.int(), route.rows, base)
            let count = r.int()
            XCTAssertEqual(count, route.layers.count, "\(base): layers in the route")
            var layersOff = 0
            var packedHere = 0
            for k in 0..<count {
                let number = r.int(), z = r.float(), left = r.int(), top = r.int(), width = r.int(), height = r.int()
                let unpacked = r.take(r.int())
                guard k < route.layers.count else { layersOff += 1; continue }
                let l = route.layers[k]
                packedHere += l.packed.count
                let mine = try l.unpacked()
                if number != l.number || z != l.z || left != l.left || top != l.top || width != l.width || height != l.height
                    || mine != unpacked { layersOff += 1 }
            }
            let packedThere = r.int()
            XCTAssertEqual(layersOff, 0, "\(base): route layers that differ")

            // and every picture
            var drawn: [(String, GcodePreview.Picture)] = []
            drawn.append(("finished", GcodePreview.render(shape)!))
            for tenths in [3, 6] { drawn.append(("upto\(tenths)", GcodePreview.render(shape, upTo: shape.top * Float(tenths) / 10)!)) }
            for turn in 1..<GcodePreview.TURNS { drawn.append(("turn\(turn)", GcodePreview.render(shape, turn: turn)!)) }
            for number in [route.layers.count / 8, route.layers.count / 2] {
                guard let layer = route.layers.first(where: { $0.number == number }) else { continue }
                drawn.append(("layer\(number)", try GcodePreview.render(shape, route: route, layer: layer)!))
                drawn.append(("layer\(number).turn2", try GcodePreview.render(shape, route: route, layer: layer, turn: 2)!))
            }
            for (what, picture) in drawn {
                pictures += 1
                if compare("\(base).\(what)", picture, try dumped(dumps.appendingPathComponent("\(base).\(what).pic"))) { identical += 1 }
            }
            report.append(String(format: "%@: read in %.0f ms here; shape %d x %d x %d, route %d layers, packed %d bytes here and %d there",
                                 base, read * 1000, shape.cols, shape.rows, shape.stack.slices, route.layers.count, packedHere, packedThere))
        }
        print("\n" + report.joined(separator: "\n"))
        print("\(identical) of \(pictures) pictures identical to the Android app's")
    }
}
