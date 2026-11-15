import Foundation
import XCTest
@testable import PartKit

/// Following where the nozzle really is, from what the firmware says. Ported from NozzleTest.kt.
final class NozzleTests: XCTestCase {

    private func fix(_ x: Float, _ millis: Int64, e: Float? = nil, y: Float = 110, z: Float = 0.8) -> NozzleFix {
        NozzleFix(x: x, y: y, z: z, e: e ?? Float(millis) / 100, millis: millis)
    }

    func testReadsTheFirmwaresLine() throws {
        let fix = try XCTUnwrap(parseNozzleFix("Recv: REALPOS 123.45 67.89 1.20 1234.56 987654"))
        XCTAssertEqual(fix.x, 123.45, accuracy: 0.001)
        XCTAssertEqual(fix.y, 67.89, accuracy: 0.001)
        XCTAssertEqual(fix.z, 1.2, accuracy: 0.001)
        XCTAssertEqual(fix.e, 1234.56, accuracy: 0.01)
        XCTAssertEqual(fix.millis, 987654)
    }

    func testLeavesEverythingElseAlone() {
        for line in [
            "Recv: ok", "Send: N123 G1 X10 Y10*45", "Recv: X:10.00 Y:20.00 Z:0.20 E:0.00 Count X:800 Y:1600 Z:80",
            "Recv: REALPOS 1 2 3", "Recv: REALPOS a b c d e", "Recv: REALPOS NaN 2 3 4 5",
        ] { XCTAssertNil(parseNozzleFix(line), line) }
    }

    func testABatchIsPlayedBackInStepWithWhenItWasTaken() {
        let track = NozzleTrack()
        // four readings a quarter of a second apart, all arriving at once, as OctoPrint sends them
        for i in 0...3 { track.add(fix(100 + Float(i) * 10, 10_000 + Int64(i) * 250), arrivedAt: 50_750) }
        var here = [Float](repeating: 0, count: 5)
        for i in 0...4 {
            XCTAssert(track.sample(50_750 + 200 + Int64(i) * 125))
            here[i] = track.x
        }
        for i in 1...4 { XCTAssertGreaterThanOrEqual(here[i], here[i - 1], "it should run on smoothly between readings: \(here)") }
        XCTAssert(here[2] > 100 && here[2] < 130, "and be somewhere between them, not at the end at once: \(here)")
    }

    func testItNeverGoesPastTheLastReading() {
        let track = NozzleTrack()
        track.add(fix(100, 1_000), arrivedAt: 5_000)
        track.add(fix(110, 1_250), arrivedAt: 5_250)
        XCTAssert(track.sample(7_000))
        XCTAssertEqual(track.x, 110, accuracy: 0.01, "nothing is guessed beyond what was measured")
    }

    func testSilenceMeansNotKnowing() {
        let track = NozzleTrack()
        track.add(fix(100, 1_000), arrivedAt: 5_000)
        XCTAssert(track.sample(6_000))
        XCTAssertFalse(track.sample(8_100), "after three seconds the firmware has stopped saying")
    }

    func testATravelIsNotLaying() {
        let track = NozzleTrack()
        track.add(fix(100, 1_000, e: 5), arrivedAt: 1_000)
        track.add(fix(120, 1_250, e: 5), arrivedAt: 1_250)
        track.add(fix(130, 1_500, e: 6), arrivedAt: 1_500)
        track.sample(1_825)
        XCTAssertFalse(track.laying)
        track.sample(2_075)
        XCTAssert(track.laying)
    }

    func testARestartedPrinterIsNotReadAsTimeGoingBackwards() {
        let track = NozzleTrack()
        track.add(fix(100, 900_000), arrivedAt: 5_000)
        track.add(fix(50, 1_000), arrivedAt: 6_000)
        XCTAssert(track.sample(6_100))
        XCTAssertEqual(track.x, 50, accuracy: 0.01)
    }

    /* ---------------- along the toolpath ---------------- */

    /** Layers of a 20 mm square, each an outline and lines filling it from the front to the back. */
    private static let square: String = {
        var out = "M83\n"
        var layer = 0
        var z: Float = 0.2
        while z <= 1.21 {
            layer += 1
            out += "M117 DASHBOARD_LAYER_INDICATOR \(layer)\n"
            out += String(format: "G1 Z%.2f F600\n", z)
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
    }()

    private static let route: GcodePreview.Route = try! GcodePreview.read({ InputStream(data: Data(square.utf8)) })!.route
    private var route: GcodePreview.Route { NozzleTests.route }

    private func follower() -> NozzleFollower {
        let f = NozzleFollower()
        f.useLayers(route.layers.map(\.number), route.layers.map(\.z))
        return f
    }

    private func map(_ number: Int) -> LayerMap { try! LayerMap(route, route.layers.first { $0.number == number }!) }

    /** Every layer has been sent to the printer. */
    private let allSent = Int(Int32.max)

    /**
     * Feeds the nozzle laying plastic at one spot and looks, given the map of the layer it is on
     * and [sent] as the last layer sent to the printer.
     */
    private func at(_ nozzle: NozzleFollower, _ x: Float, _ y: Float, _ z: Float, _ clock: inout Int64, sent: Int?? = .none) {
        let sent: Int? = sent ?? allSent
        let t = clock
        nozzle.add(NozzleFix(x: x, y: y, z: z, e: Float(t) / 10, millis: t), arrivedAt: t + 200)
        nozzle.add(NozzleFix(x: x, y: y, z: z, e: Float(t + 250) / 10, millis: t + 250), arrivedAt: t + 450)
        nozzle.look(t + 1_100, sent: sent)
        if let n = nozzle.layer { nozzle.useMap(map(n)) }
        nozzle.look(t + 1_101, sent: sent)
        clock = t + 500
    }

    func testTheLayerComesFromTheRealHeightNotFromWhatWasSent() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 101, 0.8, &clock)
        XCTAssertEqual(nozzle.layer, 4)
        XCTAssertEqual(nozzle.height, 0.8, accuracy: 0.001)
    }

    func testTheFillGoesWhereTheNozzleIs() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 102, 0.8, &clock)
        let early = nozzle.done
        for it in 0..<12 { at(nozzle, 110, 102 + Float(it) * 1.5, 0.8, &clock) }
        XCTAssertGreaterThan(nozzle.done, early + 0.5, "further back in the part is further through the layer: \(early) then \(nozzle.done)")
    }

    func testATravelOverPlasticStillToComeDoesNotMoveIt() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 0.8, &clock)
        let before = nozzle.done
        // across to the far side of the part with the extruder still, as a travel goes
        let t = clock
        nozzle.add(NozzleFix(x: 110, y: 118, z: 0.8, e: 1, millis: t), arrivedAt: t)
        nozzle.add(NozzleFix(x: 110, y: 118, z: 0.8, e: 1, millis: t + 250), arrivedAt: t + 250)
        nozzle.look(t + 1_100, sent: allSent)
        XCTAssertEqual(nozzle.done, before, accuracy: 0.0001)
    }

    func testAHopUpOnATravelIsNotANewLayer() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 0.8, &clock)
        let t = clock
        nozzle.add(NozzleFix(x: 110, y: 104, z: 1.2, e: 1, millis: t), arrivedAt: t)
        nozzle.add(NozzleFix(x: 110, y: 104, z: 1.2, e: 1, millis: t + 250), arrivedAt: t + 250)
        nozzle.look(t + 1_100, sent: allSent)
        XCTAssertEqual(nozzle.layer, 4)
    }

    func testTheExtruderPushingBackAfterAHopIsNotANewLayer() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 0.8, &clock)
        // up 0.4 for a travel with the filament drawn in, then back down and pushed out again
        let t = clock
        nozzle.add(NozzleFix(x: 112, y: 106, z: 1.2, e: 1, millis: t), arrivedAt: t)
        nozzle.add(NozzleFix(x: 114, y: 108, z: 0.8, e: 1.8, millis: t + 250), arrivedAt: t + 250)
        nozzle.look(t + 1_100, sent: allSent)
        XCTAssertEqual(nozzle.layer, 4, "the plastic went down at the lower reading's height")
    }

    func testItIsNeverOnALayerNotSentYet() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 1.0, &clock, sent: 4)
        XCTAssertEqual(nozzle.layer, 4)
        at(nozzle, 110, 104, 1.0, &clock, sent: 5)
        XCTAssertEqual(nozzle.layer, 5)
    }

    func testThePurgeBeforeTheFirstLayerIsNoLayer() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        // a purge line a shade higher than the first layer, before the first layer's mark goes out
        at(nozzle, 110, 104, 0.28, &clock, sent: .some(nil))
        XCTAssertNil(nozzle.layer)
        at(nozzle, 110, 101, 0.2, &clock, sent: 1)
        XCTAssertEqual(nozzle.layer, 1)
    }

    func testANewLayerStartsFromNothing() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 116, 0.8, &clock)
        XCTAssertGreaterThan(nozzle.done, 0.5)
        at(nozzle, 110, 101, 1.0, &clock)
        XCTAssertEqual(nozzle.layer, 5)
        XCTAssertLessThan(nozzle.done, 0.2, "near the start of layer 5, not \(nozzle.done)")
    }

    func testAHopIntoANewLayerOverWhatItLaysLastStartsItFromWhereItLands() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 116, 0.8, &clock)
        // up and over the back of the part, where layer 5 finishes, then down at its front, pushed
        // out and printing on
        let t = clock
        nozzle.add(NozzleFix(x: 110, y: 118, z: 1.4, e: 1, millis: t), arrivedAt: t)
        nozzle.add(NozzleFix(x: 110.5, y: 101, z: 1.0, e: 1.8, millis: t + 250), arrivedAt: t + 250)
        nozzle.add(NozzleFix(x: 111, y: 101.2, z: 1.0, e: 2.4, millis: t + 500), arrivedAt: t + 500)
        nozzle.look(t + 900, sent: allSent)
        XCTAssertEqual(nozzle.layer, 4, "one reading in the air and one on the layer don't say which layer")
        nozzle.look(t + 1_100, sent: allSent)
        XCTAssertEqual(nozzle.layer, 5)
        nozzle.useMap(map(5))
        nozzle.look(t + 1_101, sent: allSent)
        XCTAssertLessThan(nozzle.done, 0.2, "near the start of layer 5, not \(nozzle.done)")
    }

    func testATravelStraightAfterPrintingDoesNotCreepAhead() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 0.8, &clock)
        let before = nozzle.done
        // printed a little, then across to the back of the part before the next reading
        let t = clock
        nozzle.add(NozzleFix(x: 110, y: 104.4, z: 0.8, e: 1, millis: t), arrivedAt: t)
        nozzle.add(NozzleFix(x: 110, y: 118, z: 0.8, e: 1.2, millis: t + 250), arrivedAt: t + 250)
        // frame by frame through the quarter second between the two, where each step on is small
        for frame in 0...16 { nozzle.look(t + 700 + Int64(frame) * 16, sent: allSent) }
        XCTAssertLessThan(nozzle.done, before + 0.05, "still about where it printed: \(before) then \(nozzle.done)")
    }

    func testPrintedFromTheCardThePurgeIsStillNoLayer() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        // nothing says what has been sent: the purge line, between the first layer and the second
        at(nozzle, 110, 104, 0.28, &clock, sent: NozzleFollower.ANY_LAYER)
        XCTAssertNil(nozzle.layer)
        at(nozzle, 110, 101, 0.2, &clock, sent: NozzleFollower.ANY_LAYER)
        XCTAssertEqual(nozzle.layer, 1)
        at(nozzle, 110, 101, 0.4, &clock, sent: NozzleFollower.ANY_LAYER)
        XCTAssertEqual(nozzle.layer, 2)
    }

    func testPrintedFromTheCardADisplayComingInLateFindsTheLayer() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 1.0, &clock, sent: NozzleFollower.ANY_LAYER)
        XCTAssertEqual(nozzle.layer, 5)
    }

    func testTheLayerNeverGoesBack() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 104, 1.0, &clock)
        at(nozzle, 110, 104, 0.6, &clock)
        XCTAssertEqual(nozzle.layer, 5)
    }

    func testADisplayThatLostTrackFindsItsWayAgain() {
        let nozzle = follower()
        var clock: Int64 = 1_000
        at(nozzle, 110, 101, 0.8, &clock)
        // far further on than it should have got to, and staying there
        for _ in 0..<40 { at(nozzle, 110, 118, 0.8, &clock) }
        XCTAssertGreaterThan(nozzle.done, 0.7, "it should give in and believe it, not sit at \(nozzle.done)")
    }

    func testWithoutTheFilesLayersThereIsNothingToSay() {
        let nozzle = NozzleFollower()
        nozzle.add(NozzleFix(x: 110, y: 104, z: 0.8, e: 1, millis: 1_000), arrivedAt: 1_000)
        nozzle.add(NozzleFix(x: 110, y: 104, z: 0.8, e: 2, millis: 1_250), arrivedAt: 1_250)
        XCTAssert(nozzle.look(2_000, sent: allSent))
        XCTAssertNil(nozzle.layer)
    }
}

/// How long the layer takes to move towards a new figure. Ported from LayerGlideTest.kt.
final class LayerGlideTests: XCTestCase {

    func testItRunsOnPastTheGapSoTheFillIsStillMovingWhenTheNextFigureComes() {
        // along straight lines ten moves can take seconds, which used to leave the fill standing
        for gap: Int64 in [1000, 2500, 4000, 6000] {
            XCTAssertGreaterThan(Int64(glideMillis(gap)), gap, "after a gap of \(gap)")
        }
    }

    func testFiguresComingQuicklyStillMoveVisibly() {
        XCTAssertEqual(glideMillis(50), 900)
    }

    /** One layer's figures going by: a figure, then how long until the next. Gives what was expected of each. */
    private func layer(_ pace: FigurePace, startingAt: Int64, _ figures: [(Int, Int64)]) -> [Int64] {
        var now = startingAt
        return figures.map { figure, then in
            let expected = pace.expect(figure, now)
            now += then
            return expected
        }
    }

    func testALongGapIsSeenComingFromTheLayerBefore() {
        // quick figures, then ten moves along straight lines that take five seconds, then quick again:
        // the shape of a real layer, where the fill used to stop at the same place every time
        let shape: [(Int, Int64)] = [(10, 900), (15, 900), (20, 900), (25, 5000), (30, 900), (35, 900)]
        let pace = FigurePace()
        _ = layer(pace, startingAt: 1_000, shape)
        let next = layer(pace, startingAt: 100_000, shape)
        XCTAssertEqual(next[3], 5000, "the long gap should be expected as it starts, from the layer before")
        XCTAssertEqual(next[1], 900, "and the quick ones as quick")
    }

    func testTheFirstLayerGoesByTheLastGap() {
        let pace = FigurePace()
        XCTAssertEqual(pace.expect(10, 5_000), 0, "nothing to go on at the very start")
        XCTAssertEqual(pace.expect(20, 6_200), 1_200)
    }

    func testANewLayerStartingOverIsNotCountedAsAGap() {
        let pace = FigurePace()
        _ = pace.expect(90, 1_000)
        _ = pace.expect(95, 2_000)
        _ = pace.expect(5, 50_000)         // the new layer, far lower down
        XCTAssertEqual(pace.expect(90, 60_000), 1_000, "what came before stands for 90 percent")
    }

    func testALongSilenceIsAStoppedPrintAndNotAPace() {
        // a pause of an hour shouldn't leave the next figure taking an hour to show
        XCTAssertEqual(glideMillis(3_600_000), 8000)
    }

    func testTheGlideSnapsBackForANewLayerAndRunsOnOtherwise() {
        let glide = LayerGlide()
        glide.set(0.2, now: 10)
        glide.set(0.5, now: 11)
        XCTAssertEqual(glide.value(11), 0.2, accuracy: 0.0001)
        XCTAssertGreaterThan(glide.value(11.5), 0.2)
        XCTAssertEqual(glide.value(30), 0.5, accuracy: 0.0001)
        glide.set(0.1, now: 31)
        XCTAssertEqual(glide.value(31), 0.1, accuracy: 0.0001, "a new layer starts from nothing at once")
    }
}
