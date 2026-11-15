import CoreGraphics
import Foundation
import XCTest
@testable import PartKit

/// Keeping drawings, and drawing the part as it stands from what was kept.
final class PartLibraryTests: XCTestCase {

    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("PartKitTests-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    /** Layers of a 20 mm square, each an outline and lines filling it from the front to the back, marked by the plugin. */
    static let marked: String = {
        var out = ";TYPE:Custom\nG28\nG92 E0\nG1 X-2 Y20 Z0.28 F5000\nG1 X-2 Y200 Z0.28 F1500 E15\nG90\nM83\n"
        var layer = 0
        var z: Float = 0.2
        while z <= 2.01 {
            layer += 1
            out += ";LAYER_CHANGE\nM117 DASHBOARD_LAYER_INDICATOR \(layer)\n"
            out += String(format: "G1 Z%.2f F600\n", z)
            out += ";TYPE:Outer wall\nG1 X100 Y100 F6000\nG1 X120 Y100 E0.6\nG1 X120 Y120 E0.6\nG1 X100 Y120 E0.6\nG1 X100 Y100 E0.6\n"
            out += ";TYPE:Solid infill\n"
            var y: Float = 100.4
            while y < 119.8 {
                out += String(format: "G1 X100.4 Y%.2f F6000\nG1 X119.6 Y%.2f E0.6\n", y, y)
                y += 0.4
            }
            z += 0.2
        }
        return out
    }()

    private let file = PartFile(origin: "local", path: "slab.gcode", display: "slab.gcode", date: 1_700_000_000, size: 40_000)
    private let tint = CGColor(srgbRed: 0xE9 / 255.0, green: 0xE4 / 255.0, blue: 0xD8 / 255.0, alpha: 1)
    private let hot = CGColor(srgbRed: 1, green: 0x6B / 255.0, blue: 0x2C / 255.0, alpha: 1)

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func bump() { lock.withLock { n += 1 } }
        var count: Int { lock.withLock { n } }
    }

    private func library(_ gcode: String = PartLibraryTests.marked, fetches: Counter? = nil, fail: Bool = false) -> PartLibrary {
        PartLibrary(directory: dir) { _, to, _ in
            fetches?.bump()
            if fail { throw URLError(.cannotConnectToHost) }
            try Data(gcode.utf8).write(to: to)
        }
    }

    func testAFileIsDrawnOnceAndKept() async throws {
        let fetches = Counter()
        let lib = library(fetches: fetches)
        XCTAssertFalse(lib.settled(file))
        let outcome = await lib.want(file, gentle: true)
        XCTAssertEqual(outcome, .drawn)
        XCTAssert(lib.settled(file))
        XCTAssert(lib.hasShape(file))
        let again = await lib.want(file, gentle: true)
        XCTAssertEqual(again, .drawn)
        XCTAssertEqual(fetches.count, 1, "a drawn file isn't fetched again")
        let picture = await lib.preview(file, tint: tint)
        XCTAssertEqual(picture?.width, GcodePreview.WIDTH)
        XCTAssertEqual(picture?.height, GcodePreview.HEIGHT)
    }

    func testTheStoredPictureIsTheDrawingExactly() async throws {
        let lib = library()
        _ = await lib.want(file, gentle: false)
        let drawn = try XCTUnwrap(try GcodePreview.draw({ InputStream(data: Data(PartLibraryTests.marked.utf8)) }))
        let stored = try XCTUnwrap(Images.loadPNG(dir.appendingPathComponent(lib.key(file) + ".png")))
        XCTAssertEqual(stored.shown, (0..<(drawn.width * drawn.height)).map { drawn.shown($0) })
    }

    func testTheKeptShapeAndRouteReadBack() async throws {
        let lib = library()
        _ = await lib.want(file, gentle: false)
        let reading = try XCTUnwrap(try GcodePreview.read({ InputStream(data: Data(PartLibraryTests.marked.utf8)) }))
        let shape = try XCTUnwrap(lib.shapeOf(file))
        XCTAssertEqual(shape.cols, reading.shape.cols)
        XCTAssertEqual(shape.rows, reading.shape.rows)
        XCTAssertEqual(shape.top, reading.shape.top)
        for s in 0..<shape.stack.slices {
            XCTAssertEqual(shape.stack.part[s]?.words, reading.shape.stack.part[s]?.words)
            XCTAssertEqual(shape.stack.extra[s]?.words, reading.shape.stack.extra[s]?.words)
        }
        for i in shape.height.indices { XCTAssertEqual(shape.height[i], reading.shape.height[i], accuracy: 0.006) }

        let layers = try XCTUnwrap(lib.layersOf(file))
        XCTAssertEqual(layers.numbers, reading.route.layers.map(\.number))
        XCTAssertEqual(layers.heights, reading.route.layers.map(\.z))
        let map = try XCTUnwrap(lib.mapOf(file, number: 4))
        let fresh = try LayerMap(reading.route, reading.route.layers.first { $0.number == 4 }!)
        for (x, y) in [(110, 100), (110, 104), (110, 110), (110, 116), (97, 110)] as [(Float, Float)] {
            XCTAssertEqual(map.momentAt(x, y), fresh.momentAt(x, y))
        }
        XCTAssertNil(lib.mapOf(file, number: 99))
    }

    func testThePartGrowsLayerByLayer() async throws {
        let lib = library()
        _ = await lib.want(file, gentle: true)
        let grown = await lib.grown(file, fraction: 0.4, layer: 4, tint: tint, hot: hot)
        let growth = try XCTUnwrap(grown)
        XCTAssertEqual(growth.layer, 4)
        XCTAssertNotNil(growth.order, "with the order known, the layer fills in along it")
        XCTAssertEqual(growth.trail?.count, 2 * GcodePreview.MOMENTS)
        XCTAssertNotNil(growth.box)
        XCTAssertFalse(growth.dated)
        XCTAssertEqual(growth.view?.high ?? 0, 2, accuracy: 0.3)
        XCTAssert(lib.lastGrown(file, tint: tint, hot: hot) === growth)

        // without a layer, by how much of its height is done
        let plainGrown = await lib.grown(file, fraction: 0.5, layer: nil, tint: tint, hot: hot, turn: 2)
        let plain = try XCTUnwrap(plainGrown)
        XCTAssertNil(plain.layer)
        XCTAssertNil(plain.order)
        XCTAssertNotNil(plain.box)
    }

    func testNothingToDrawIsRememberedAndNotFetchedAgain() async {
        let fetches = Counter()
        let lib = library("M83\nG1 Z0.2\nG1 X100 Y100\n", fetches: fetches)
        let first = await lib.want(file, gentle: false)
        XCTAssertEqual(first, .nothing)
        XCTAssert(lib.settled(file))
        let second = await lib.want(file, gentle: false)
        XCTAssertEqual(second, .nothing)
        XCTAssertEqual(fetches.count, 1)
        XCTAssertEqual(lib.note(file), "There is nothing in this file to draw")
        let grown = await lib.grown(file, fraction: 0.5, layer: nil, tint: tint, hot: hot)
        XCTAssertNil(grown)
    }

    func testAFileTooBigIsNeverFetched() async {
        let fetches = Counter()
        let lib = library(fetches: fetches)
        let huge = PartFile(origin: "local", path: "huge.gcode", display: "huge", date: 1, size: 400 * 1024 * 1024)
        let outcome = await lib.want(huge, gentle: true)
        XCTAssertEqual(outcome, .nothing)
        XCTAssertEqual(fetches.count, 0)
    }

    func testAFailedFetchIsNotTriedAgainUntilForgotten() async {
        let fetches = Counter()
        let lib = library(fetches: fetches, fail: true)
        let first = await lib.want(file, gentle: false)
        XCTAssertEqual(first, .failed)
        let second = await lib.want(file, gentle: false)
        XCTAssertEqual(second, .failed)
        XCTAssertEqual(fetches.count, 1)
        XCTAssertEqual(lib.note(file), "This file couldn't be fetched to draw a preview")
        lib.forgetFailures()
        _ = await lib.want(file, gentle: false)
        XCTAssertEqual(fetches.count, 2)
    }

    func testAskingAgainWhileItIsBeingDrawnDrawsItOnce() async {
        let fetches = Counter()
        let lib = library(fetches: fetches)
        async let a = lib.want(file, gentle: true)
        async let b = lib.want(file, gentle: true)
        async let c = lib.want(file, gentle: false)
        let outcomes = await [a, b, c]
        XCTAssertEqual(outcomes, [.drawn, .drawn, .drawn])
        XCTAssertEqual(fetches.count, 1)
    }

    func testAShapeIsWantedAtMostOnceAMinute() async throws {
        let fetches = Counter()
        let lib = library(fetches: fetches, fail: true)
        XCTAssert(lib.wantShape(file, gentle: true))
        XCTAssert(lib.wantShape(file, gentle: true))
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(fetches.count, 1)
    }

    func testTintedPicturesAreTheGreysInTheColor() throws {
        let p = GcodePreview.Picture(width: 2, height: 1, light: [255, 128], alpha: [255, 255], fresh: [0, 0], shadow: [0, 0])
        let image = try XCTUnwrap(p.tinted(CGColor(srgbRed: 1, green: 0.5, blue: 0, alpha: 1)))
        let data = try XCTUnwrap(image.dataProvider?.data) as Data
        XCTAssertEqual([UInt8](data.prefix(8)), [255, 128, 0, 255, 128, 64, 0, 255])
    }

    func testThePartsSizeReads() {
        // halves round up, as the Android app's do
        XCTAssertEqual(partSize(120.4, 35, 8.25), "120 × 35 × 8.3 mm")
        XCTAssertEqual(partSize(9.96, 0.15, 10.5), "10.0 × 0.2 × 11 mm")
    }
}
