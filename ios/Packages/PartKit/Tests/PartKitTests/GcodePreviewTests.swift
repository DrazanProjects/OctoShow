import Foundation
import XCTest
@testable import PartKit

/// Ported from the Android app's GcodePreviewTest.kt.
final class GcodePreviewTests: XCTestCase {

    private func opener(_ gcode: String) -> GcodePreview.Opener { { InputStream(data: Data(gcode.utf8)) } }

    private func draw(_ gcode: String) -> GcodePreview.Picture? {
        try! GcodePreview.draw(opener(gcode), width: 96, height: 96)
    }

    /** A solid 20 mm square slab, 2 mm tall, the way a slicer writes one layer after another. */
    private func slab(_ label: String = ";TYPE:Outer wall") -> String {
        var out = ""
        var z: Float = 0.2
        while z <= 2.01 {
            out += String(format: "G1 Z%.2f F600\n", z)
            out += label + "\n"
            var y: Float = 100
            while y <= 120 {
                out += String(format: "G1 X100 Y%.2f F6000\n", y)
                out += String(format: "G1 X120 Y%.2f E0.6\n", y)
                y += 0.4
            }
            z += 0.2
        }
        return out
    }

    private let start = """
        ;TYPE:Custom
        G28
        G92 E0
        G1 X-2 Y20 Z0.28 F5000
        G1 X-2 Y200 Z0.28 F1500 E15 ; purge line down the edge of the bed
        G90
        M83

        """

    func testDrawsThePartAndFillsThePictureWithIt() {
        let picture = draw(start + slab())
        XCTAssertNotNil(picture)
        // If the purge line counted, the part would be a speck in a picture framed around the whole bed.
        XCTAssertGreaterThan(picture!.covered(), 0.25, "part should fill a good share of the picture")
    }

    func testLeavesOutWhatIsPrintedAroundThePart() {
        let alone = draw(start + slab())!.covered()
        let withSkirt = draw(
            start + ";TYPE:Skirt\nG1 Z0.2\nG1 X60 Y60 F6000\nG1 X160 Y60 E3\nG1 X160 Y160 E3\nG1 X60 Y160 E3\nG1 X60 Y60 E3\n" + slab()
        )!.covered()
        XCTAssertEqual(alone, withSkirt, accuracy: 0.02, "a skirt far from the part must not change the framing")
    }

    func testFallsBackToEverythingWhenNothingIsLabelled() {
        let unlabelled = start.replacingOccurrences(of: ";TYPE:Custom\n", with: "") + slab("; no labels in this file")
        XCTAssertNotNil(draw(unlabelled))
    }

    func testFollowsArcs() {
        // one full circle of radius 10, drawn as two half-circle arc moves
        let ring = "M83\nG90\nG1 Z0.2\n;TYPE:Outer wall\nG1 X110 Y100 F6000\nG2 X90 Y100 I-10 J0 E1\nG2 X110 Y100 I10 J0 E1\n"
        let picture = draw(ring)
        XCTAssertNotNil(picture)
        // a ring is mostly hole: some of the picture is covered, but nothing like a filled disc
        XCTAssert((0.02...0.35).contains(picture!.covered()), "covered \(picture!.covered())")
    }

    func testUnderstandsAbsoluteExtrusionAndIgnoresRetractions() {
        let absolute = "M82\nG90\nG92 E0\nG1 Z0.2\n;TYPE:Outer wall\nG1 X100 Y100 F6000\nG1 X120 Y100 E1\nG1 X120 Y120 E0.5\nG1 X100 Y120 E0.5\n"
        // the second and third moves pull filament back or hold it still: only the first lays any
        XCTAssertNotNil(draw(absolute))
        XCTAssertNil(draw("M83\nG1 Z0.2\nG1 X100 Y100\nG1 X120 Y120\nG1 X90 Y90 E-1\n"), "moves that lay no plastic are not a part")
    }

    func testStopsWhenTold() {
        XCTAssertNil(try GcodePreview.draw(opener(start + slab()), width: 96, height: 96, keepGoing: { false }))
    }

    func testAPartHalfPrintedIsShorterWithItsTopLayerMarked() throws {
        let shape = try XCTUnwrap(try GcodePreview.shape(opener(start + slab())))
        let whole = GcodePreview.render(shape, width: 96, height: 96)!
        let half = GcodePreview.render(shape, width: 96, height: 96, upTo: shape.top / 2)!

        XCTAssertLessThan(half.covered(), whole.covered(), "half a slab should cover less of the picture than a whole one")
        XCTAssert(half.fresh.contains { $0 != 0 }, "the layer being laid should be marked")
        XCTAssert(whole.fresh.allSatisfy { $0 == 0 }, "a finished part has no layer being laid")
    }

    func testEveryStageSitsInsideTheFinishedPicture() throws {
        // or the growing part would wander about inside its own ghost
        let shape = try XCTUnwrap(try GcodePreview.shape(opener(start + slab())))
        let whole = GcodePreview.render(shape, width: 96, height: 96)!
        let covered = whole.alpha.filter { $0 != 0 }.count
        for tenths in 1...9 {
            let stage = GcodePreview.render(shape, width: 96, height: 96, upTo: shape.top * Float(tenths) / 10)!
            let outside = stage.alpha.indices.filter { stage.alpha[$0] != 0 && whole.alpha[$0] == 0 }.count
            // a handful of pixels along an edge can fall either way; a part that had moved would be out by hundreds
            XCTAssertLessThanOrEqual(outside, covered / 100, "at \(tenths) tenths of the height, \(outside) pixels fell outside")
        }
    }

    func testDrawingTheShapeIsTheSameAsDrawingTheFile() throws {
        let gcode = start + slab()
        let direct = draw(gcode)!
        let viaShape = GcodePreview.render(try XCTUnwrap(try GcodePreview.shape(opener(gcode))), width: 96, height: 96)!
        XCTAssertEqual(direct.light, viaShape.light)
        XCTAssertEqual(direct.alpha, viaShape.alpha)
    }

    /* ---------------- the order a layer is laid in ---------------- */

    /**
     * The slab as OctoPrint stores it once the Dashboard plugin has marked its layers,
     * each one an outline and then lines filling it in from the front to the back.
     */
    private func markedSlab(scarf: Bool = false) -> String {
        var out = ""
        var layer = 0
        var z: Float = 0.2
        while z <= 2.01 {
            layer += 1
            out += ";LAYER_CHANGE\n"
            out += "M117 DASHBOARD_LAYER_INDICATOR \(layer)\n"
            out += String(format: "G1 Z%.2f F600\n", z)
            out += ";TYPE:Outer wall\n"
            out += "G1 X100 Y100 F6000\n"
            if scarf {
                // OrcaSlicer's scarf seam: the wall sets off at the height of the layer below
                // and climbs to its own over the first stretch
                out += String(format: "G1 X104 Y100 Z%.2f E0.1\n", z - 0.19)
                out += String(format: "G1 X108 Y100 Z%.2f E0.1\n", z)
                out += "G1 X120 Y100 E0.4\n"
            } else {
                out += "G1 X120 Y100 E0.6\n"
            }
            out += "G1 X120 Y120 E0.6\n"
            out += "G1 X100 Y120 E0.6\n"
            out += "G1 X100 Y100 E0.6\n"
            out += ";TYPE:Solid infill\n"
            var y: Float = 100.4
            while y < 119.8 {
                out += String(format: "G1 X100.4 Y%.2f F6000\n", y)
                out += String(format: "G1 X119.6 Y%.2f E0.6\n", y)
                y += 0.4
            }
            z += 0.2
        }
        return out
    }

    private func read(_ gcode: String) -> GcodePreview.Reading { try! GcodePreview.read(opener(gcode))! }

    func testAFileThePluginNeverMarkedHasNoRoute() {
        // without its marks there is no telling which layer its figures would be about
        XCTAssert(read(start + slab()).route.layers.isEmpty)
    }

    func testTheRouteIsTheOrderTheNozzleGoesOverTheLayer() {
        let route = read(start + markedSlab()).route
        XCTAssertEqual(route.layers.count, 10, "one for each layer the plugin marked")
        let layer = route.layers.first { $0.number == 4 }!
        XCTAssertEqual(layer.z, 0.8, accuracy: 0.001)

        let outline = route.moment(layer, 110, 100)
        let front = route.moment(layer, 110, 104)
        let middle = route.moment(layer, 110, 110)
        let back = route.moment(layer, 110, 116)
        XCTAssert(outline >= 1 && outline < front, "the outline goes down first: \(outline), then \(front)")
        XCTAssert(front < middle && middle < back, "and the filling follows from front to back: \(front), \(middle), \(back)")
        XCTAssert((108...148).contains(middle), "half way back is about half way through, not \(middle)")
        XCTAssertEqual(route.moment(layer, 97, 110), 0, "nothing is laid outside the part")
    }

    func testASeamThatClimbsIntoItsLayerDoesNotLowerTheLayer() throws {
        let reading = read(start + markedSlab(scarf: true))
        let layer = reading.route.layers.first { $0.number == 4 }!
        XCTAssertEqual(layer.z, 0.8, accuracy: 0.001, "the layer is where most of it is laid, not where its seam starts")
        let picture = try XCTUnwrap(try GcodePreview.render(reading.shape, route: reading.route, layer: layer, width: 96, height: 96))
        let laying = picture.fresh.filter { $0 != 0 }.count
        let timed = picture.fresh.indices.filter { picture.fresh[$0] != 0 && picture.reached![$0] != 0 }.count
        XCTAssert(laying > 200 && timed >= laying * 95 / 100, "the whole layer should show as the one being laid: \(timed) of \(laying)")
    }

    func testALayerIsMeasuredInMovesTheWayThePluginMeasuresIt() {
        // Eleven moves, of which the tenth lays the only plastic. The plugin would call the
        // layer 81% done as that line starts and 90% as it ends, though it is the whole part.
        var gcode = ""
        gcode += "M83\n"
        gcode += "G1 X5 Y5 F6000 ; before the first layer, so not counted in it\n"
        gcode += ";LAYER_CHANGE\n"
        gcode += "M117 DASHBOARD_LAYER_INDICATOR 1\n"
        gcode += "G1 Z0.2 F600\n"
        gcode += "G28 ; homing is not a move\n"
        gcode += "G92 E0\n"
        for it in 0..<8 { gcode += "G0 X\(50 + it) Y50 F6000\n" }
        gcode += ";TYPE:Outer wall\n"
        gcode += "G1 X120 Y50 E2\n"
        gcode += "G1 X10 Y10 F6000\n"
        let route = read(gcode).route
        XCTAssertEqual(route.layers.count, 1)
        let layer = route.layers[0]
        let start = route.moment(layer, 58, 50)
        let end = route.moment(layer, 119, 50)
        XCTAssert((205...213).contains(start), "the line starts 9 moves of 11 in, at about 209 of 255, not \(start)")
        XCTAssert((228...236).contains(end), "and ends 10 of 11 in, at about 232, not \(end)")
    }

    func testAPictureOfALayerSaysWhenEachBitOfItIsLaid() throws {
        let reading = read(start + markedSlab())
        let layer = reading.route.layers.first { $0.number == 5 }!
        let picture = try XCTUnwrap(try GcodePreview.render(reading.shape, route: reading.route, layer: layer, width: 96, height: 96))
        let reached = picture.reached!
        let trail = picture.trail!

        let laying = picture.fresh.indices.filter { picture.fresh[$0] != 0 }
        XCTAssertGreaterThan(laying.count, 200, "the layer should show")
        let timed = laying.filter { reached[$0] != 0 }.count
        XCTAssertGreaterThanOrEqual(timed, laying.count * 95 / 100, "only \(timed) of \(laying.count) pixels of the layer have a time")
        // the filling runs from the front of the part to the back, which is up the picture
        func average(_ xs: [Int]) -> Double { Double(xs.reduce(0, +)) / Double(xs.count) }
        let early = average(laying.filter { (40...90).contains(Int(reached[$0])) }.map { $0 / 96 })
        let late = average(laying.filter { (170...220).contains(Int(reached[$0])) }.map { $0 / 96 })
        XCTAssertLessThan(late, early - 4, "what is laid later should be further up the picture: \(early) then \(late)")

        // And the nozzle is where the picture says plastic is going down at that moment. Right
        // at the end of a line the spot under it belongs to the outline, laid long before, so
        // this looks a few pixels around.
        for m in [60, 127, 200] {
            let x = Int(trail[2 * m] * 96)
            let y = Int(trail[2 * m + 1] * 96)
            var near = [Int]()
            for dy in -3...3 { for dx in -3...3 { near.append((y + dy) * 96 + x + dx) } }
            let times = near.filter { $0 >= 0 && $0 < reached.count }.map { Int(reached[$0]) }
            XCTAssert(times.contains { $0 != 0 && abs($0 - (m + 1)) <= 8 },
                      "at moment \(m + 1) the nozzle is at \(x), \(y), where what is laid is laid at \(Set(times.filter { $0 != 0 }).sorted())")
        }
    }

    func testTheLastLayerIsShownBeingLaidToo() throws {
        let reading = read(start + markedSlab())
        let picture = try XCTUnwrap(try GcodePreview.render(reading.shape, route: reading.route, layer: reading.route.layers.last!, width: 96, height: 96))
        XCTAssert(picture.fresh.contains { $0 != 0 } && picture.reached!.contains { $0 != 0 })
    }

    /* ---------------- what the part really is ---------------- */

    /** Layers of straight lines side by side, [wide] mm across and running between two points, from [from] to [to] mm up. */
    private func block(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ wide: Float, _ from: Float, _ to: Float,
                       label: String = ";TYPE:Outer wall") -> String {
        let length = khypot(x1 - x0, y1 - y0)
        let acrossX = (y0 - y1) / length
        let acrossY = (x1 - x0) / length
        var out = ""
        var z = from
        while z <= to + 0.01 {
            out += String(format: "G1 Z%.2f F600\n", z)
            out += label + "\n"
            var off = -wide / 2
            while off <= wide / 2 {
                out += String(format: "G1 X%.3f Y%.3f F6000\n", x0 + acrossX * off, y0 + acrossY * off)
                out += String(format: "G1 X%.3f Y%.3f E0.6\n", x1 + acrossX * off, y1 + acrossY * off)
                off += 0.4
            }
            z += 0.2
        }
        return out
    }

    func testALongPartLaidCornerToCornerStillFillsThePicture() throws {
        // a bar 280 mm long and 30 wide, the way a slicer fits it onto a 220 mm bed
        let picture = try XCTUnwrap(try GcodePreview.draw(opener("M83\n" + block(10, 10, 208, 208, 30, 0.2, 4))))
        XCTAssertGreaterThan(picture.width, picture.height, "it should be the picture's shape, not a square")
        let (wide, tall) = picture.spans()
        XCTAssertGreaterThan(wide, 0.9, "it should reach right across the picture, not \(wide * 100)%")
        XCTAssertLessThan(tall, 0.6, "and be turned to lie across it, not stand \(tall * 100)% of the way up")
        // which is all a bar that shape can cover of a picture this shape
        XCTAssertGreaterThan(picture.covered(), 0.08, "so there is a good deal of it, not \(picture.covered() * 100)%")
    }

    func testAPartIsMeasuredAlongItsOwnLength() throws {
        let shape = try XCTUnwrap(try GcodePreview.shape(opener("M83\n" + block(10, 10, 208, 208, 30, 0.2, 4))))
        let view = shape.view()
        XCTAssertEqual(view.long, 280, accuracy: 3)
        XCTAssertEqual(view.broad, 30, accuracy: 3)
        XCTAssertEqual(view.high, 4, accuracy: 0.3)
    }

    /** A roof 30 mm square, 8 to 10 mm up, held by a pillar in the middle of it. */
    private var mushroom: String { "M83\n" + block(100, 110, 104, 110, 4, 0.2, 10) + block(87, 110, 117, 110, 30, 8.2, 10) }

    func testTheAirUnderAnOverhangIsAir() throws {
        let shape = try XCTUnwrap(try GcodePreview.shape(opener(mushroom)))
        let whole = GcodePreview.render(shape, width: 96, height: 96)!
        let low = GcodePreview.render(shape, width: 96, height: 96, upTo: 5)!
        // with only one height for each spot of bed, the roof would have come up with the pillar
        XCTAssertLessThan(low.covered(), whole.covered() / 5, "at 5 mm only the pillar stands: \(low.covered()) of \(whole.covered())")
        let stack = shape.stack
        let s = stack.of(4)
        let beside = Int((90 - shape.originX) / shape.cell)
        let row = Int((110 - shape.originY) / shape.cell)
        XCTAssertFalse(stack.solid(beside, row, s), "nothing is laid under the roof")
        XCTAssert(stack.solid(beside, row, stack.of(9)), "the roof itself is there")
    }

    func testWhatOverhangsAWallFromFarAboveLeavesItTheSameAllAlong() throws {
        // a block, a shaft on it, and on that a roof reaching past the block's sides high above them,
        // like a gear on a block: the wall under the roof used to go dark in a band from top to bottom
        let gcode = "M83\n" + block(100, 120, 140, 120, 30, 0.2, 8) +
            block(115, 120, 125, 120, 10, 8.2, 16) + block(110, 120, 130, 120, 40, 16.2, 20)
        let p = try XCTUnwrap(try GcodePreview.draw(opener(gcode)))
        // along the foot of the wall on the right of the picture, which stands square to the block
        let feet: [Int?] = (0..<p.width).map { x in
            (0..<p.height).reversed().first { y in p.alpha[y * p.width + x] != 0 }
        }
        let covered = feet.indices.filter { feet[$0] != nil }
        let corner = covered.max { feet[$0]! < feet[$1]! }!
        let along = (corner + 6...covered.max()! - 6).map { x in Int(p.light[(feet[x]! - 4) * p.width + x]) }
        XCTAssertLessThanOrEqual(along.max()! - along.min()!, 20, "the wall should be the same all along, not \(along.min()!) to \(along.max()!)")
    }

    func testNoLayerIsLeftEmpty() throws {
        let shape = try XCTUnwrap(try GcodePreview.shape(opener(start + markedSlab(scarf: true))))
        XCTAssertEqual(shape.stack.slice, 0.2, accuracy: 0.001, "the climbing seams don't make the layers look thinner")
        XCTAssert(shape.stack.part.allSatisfy { $0 != nil })
    }

    func testSupportsAreDrawnDimmerThanThePart() {
        let part = block(100, 110, 120, 110, 20, 0.2, 4)
        let support = block(130, 110, 150, 110, 20, 0.2, 4, label: ";TYPE:Support")
        let helped = draw("M83\n\(part)\(support)")!
        let shown = draw("M83\n\(part)" + support.replacingOccurrences(of: ";TYPE:Support", with: ";TYPE:Outer wall"))!
        XCTAssertEqual(shown.covered(), helped.covered(), accuracy: 0.02, "they should show as much as the same thing in the part would")
        XCTAssertLessThan(helped.meanLight(), shown.meanLight() - 15, "and be dimmer than the part: \(helped.meanLight()), \(shown.meanLight())")
    }

    func testABrimIsDrawnAndASkirtIsNot() throws {
        let part = block(100, 110, 120, 110, 20, 0.2, 4)
        let around = "G1 X95 Y95 Z0.2 F6000\nG1 X125 Y95 E3\nG1 X125 Y125 E3\nG1 X95 Y125 E3\nG1 X95 Y95 E3\n"
        func shape(_ label: String) throws -> GcodePreview.Shape { try XCTUnwrap(try GcodePreview.shape(opener("M83\n\(label)\n\(around)\(part)"))) }
        let brim = try shape(";TYPE:Brim")
        XCTAssertNotNil(brim.stack.extra[0], "a brim is printed with the part")
        for skirt in [";TYPE:Skirt", ";TYPE:Skirt/Brim"] { XCTAssert(try shape(skirt).stack.extra.allSatisfy { $0 == nil }) }
    }

    func testAPartCanBeLookedAtFromEverySide() throws {
        let lopsided = mushroom + block(120, 110, 140, 110, 4, 0.2, 3)
        let shape = try XCTUnwrap(try GcodePreview.shape(opener(lopsided)))
        let front = GcodePreview.render(shape, width: 96, height: 96)!
        let back = GcodePreview.render(shape, width: 96, height: 96, turn: 2)!
        XCTAssertNotEqual(front.light, back.light, "it should be seen differently")
        for turn in 1..<GcodePreview.TURNS {
            let seen = GcodePreview.render(shape, width: 96, height: 96, turn: turn)!
            XCTAssertGreaterThan(seen.covered(), front.covered() / 2, "from side \(turn) there should be as much to see")
        }
    }

    func testTheShadowIsNotCountedAsThePart() {
        let picture = draw(start + slab())!
        XCTAssert(picture.shadow.contains { $0 != 0 }, "the part stands in a shadow")
        let outside = picture.shadow.indices.first { picture.shadow[$0] != 0 && picture.alpha[$0] == 0 }!
        let shown = picture.shown(outside)
        XCTAssert(shown >> 24 != 0 && shown & 0xFFFFFF == 0, "which shows as a bit of black")
    }
}

extension GcodePreview.Picture {
    /** Share of the picture's pixels that show any of the part. */
    func covered() -> Float { Float(alpha.filter { $0 != 0 }.count) / Float(alpha.count) }

    /** How much of its width and height a picture's part reaches across. */
    func spans() -> (Float, Float) {
        let shown = alpha.indices.filter { alpha[$0] != 0 }
        let xs = shown.map { $0 % width }
        let ys = shown.map { $0 / width }
        return (Float(xs.max()! - xs.min()! + 1) / Float(width), Float(ys.max()! - ys.min()! + 1) / Float(height))
    }

    func meanLight() -> Float {
        let shown = alpha.indices.filter { alpha[$0] == 255 }
        return Float(shown.map { Int(light[$0]) }.reduce(0, +)) / Float(shown.count)
    }
}

extension GcodePreview.Route {
    /** When in its layer the spot of bed at ([x], [y]) is laid: 1..255, or 0 for never. */
    func moment(_ layer: Layer, _ x: Float, _ y: Float) -> Int {
        let col = Int((x - originX) / cell) - layer.left
        let row = Int((y - originY) / cell) - layer.top
        if col < 0 || col >= layer.width || row < 0 || row >= layer.height { return 0 }
        return Int(try! layer.unpacked()[row * layer.width + col])
    }
}
