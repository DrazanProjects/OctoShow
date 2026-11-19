import XCTest
@testable import OctoShow

final class AddressTests: XCTestCase {
    func testNormalizeBase() {
        XCTAssertEqual(normalizeBase("octopi.local")?.absoluteString, "http://octopi.local/")
        XCTAssertEqual(normalizeBase(" https://pi:5000/octo//?x=1#y ")?.absoluteString, "https://pi:5000/octo/")
        XCTAssertEqual(normalizeBase("192.168.1.50")?.absoluteString, "http://192.168.1.50/")
        XCTAssertNil(normalizeBase(""))
        XCTAssertNil(normalizeBase("ftp://pi"))
    }

    func testCandidatesTryHttpsFirstUnlessHttpWasTyped() {
        XCTAssertEqual(candidates(for: "octopi.local").map(\.absoluteString), ["https://octopi.local/", "http://octopi.local/"])
        XCTAssertEqual(candidates(for: "http://octopi.local").map(\.absoluteString), ["http://octopi.local/", "https://octopi.local/"])
        // a port that was typed is kept for both schemes
        XCTAssertEqual(candidates(for: "10.0.0.2:5000").map(\.absoluteString), ["https://10.0.0.2:5000/", "http://10.0.0.2:5000/"])
        // OctoPrint's own announcement says http whatever it serves, so it counts for nothing
        XCTAssertEqual(candidates(for: "http://octopi.local", announced: true).first?.absoluteString, "https://octopi.local/")
    }

    func testKeyOnlyGoesToOctoPrintItself() {
        let api = OctoApi(route: Route(base: URL(string: "https://octopi.local/")!, headers: ["CF-Access-Client-Id": "id"]), apiKey: "KEY")
        let own = api.request(api.url("api/version"))
        XCTAssertEqual(own.value(forHTTPHeaderField: OctoApi.keyHeader), "KEY")
        XCTAssertEqual(own.value(forHTTPHeaderField: "CF-Access-Client-Id"), "id")
        // the camera on another port of the same Pi gets the route's headers but not the key
        let camera = api.request(URL(string: "https://octopi.local:8080/?action=stream")!)
        XCTAssertNil(camera.value(forHTTPHeaderField: OctoApi.keyHeader))
        XCTAssertEqual(camera.value(forHTTPHeaderField: "CF-Access-Client-Id"), "id")
        // and nothing at all goes anywhere else
        let elsewhere = api.request(URL(string: "https://example.com/x")!)
        XCTAssertNil(elsewhere.value(forHTTPHeaderField: OctoApi.keyHeader))
        XCTAssertNil(elsewhere.value(forHTTPHeaderField: "CF-Access-Client-Id"))
    }

    func testPathsAreEncoded() {
        let api = OctoApi(route: Route(base: URL(string: "http://pi/")!), apiKey: nil)
        XCTAssertEqual(api.url("api/files/local/Phone stand (angled)_PLA.gcode").absoluteString,
                       "http://pi/api/files/local/Phone%20stand%20(angled)_PLA.gcode")
        XCTAssertEqual(api.url("api/files/local/a#b?.gcode").absoluteString, "http://pi/api/files/local/a%23b%3F.gcode")
    }
}

final class FormatTests: XCTestCase {
    func testPrettyNameDropsTheSlicersPrintTime() {
        XCTAssertEqual(prettyName("CHESSBOARD_top_front_left_PLA_8h8m.gcode"), "CHESSBOARD top front left PLA")
        XCTAssertEqual(prettyName("Desk_clips_x6_PLA_48m.gcode"), "Desk clips x6 PLA")
        XCTAssertEqual(prettyName("bracket.gcode"), "bracket")
        XCTAssertEqual(prettyName("v2_PLA.gcode"), "v2 PLA")
    }

    func testDurations() {
        XCTAssertEqual(duration(6 * 3600 + 42 * 60), "6h 42m")
        XCTAssertEqual(duration(59), "under a minute")
        XCTAssertEqual(durationParts(3600 + 5 * 60).map(\.0), ["1", "05"])
        XCTAssertEqual(durationParts(30).map(\.0), ["<1"])
    }

    func testFilamentWeight() {
        XCTAssertEqual(filamentWeight(4.0), "5.0 g")
        XCTAssertEqual(filamentWeight(40.0), "50 g")
    }
}

final class FileListTests: XCTestCase {
    private let listing = """
    {"files": [
      {"name": "Organizers", "path": "Organizers", "type": "folder", "origin": "local", "children": [
        {"name": "hook.gcode", "display": "hook.gcode", "path": "Organizers/hook.gcode", "type": "machinecode",
         "origin": "local", "size": 1000, "date": 100}
      ]},
      {"name": "cube.gcode", "path": "cube.gcode", "type": "machinecode", "origin": "local", "size": "2048", "date": 300,
       "thumbnail": "plugin/thumbs/cube.png?1",
       "gcodeAnalysis": {"estimatedPrintTime": 1320.5, "filament": {"tool0": {"length": 600, "volume": 1.5}, "tool1": {"volume": 0.5}}},
       "prints": {"last": {"success": true, "date": 1}}},
      {"name": "model.stl", "path": "model.stl", "type": "model", "origin": "local"}
    ]}
    """

    func testParse() {
        let root = FileList.parse(Data(listing.utf8))
        XCTAssertEqual(root.map(\.name), ["Organizers", "cube.gcode"], "folders and G-code only")
        let cube = root[1]
        XCTAssertEqual(cube.size, 2048)
        XCTAssertEqual(cube.estimatedPrintTime, 1320.5)
        XCTAssertEqual(cube.filamentCm3, 2.0)
        XCTAssertEqual(cube.thumbnail, "plugin/thumbs/cube.png?1")
        XCTAssertEqual(cube.lastPrintSuccess, true)
        XCTAssertEqual(root[0].children.first?.path, "Organizers/hook.gcode")
        XCTAssertEqual(root.newestFirst.map(\.name), ["cube.gcode", "hook.gcode"])
    }

    func testSameFileForAnSDCardJob() {
        let files = FileList.parse(Data(listing.utf8)).newestFirst
        let byName = JobFile(name: "cube.gco", display: "cube.gco", path: "cube.gco", origin: sdCard)
        XCTAssertEqual(sameFile(as: byName, in: files)?.path, "cube.gcode")
        let short = JobFile(name: "HOOK~1.GCO", display: "HOOK~1.GCO", path: "HOOK~1.GCO", origin: sdCard)
        XCTAssertEqual(sameFile(as: short, in: files)?.path, "Organizers/hook.gcode")
        let unknown = JobFile(name: "OTHER~1.GCO", display: "OTHER~1.GCO", path: "OTHER~1.GCO", origin: sdCard)
        XCTAssertNil(sameFile(as: unknown, in: files))
    }
}

final class ModelTests: XCTestCase {
    func testProgressGoesByTimeWhenThereIsAnEstimate() {
        var p = PrinterSnapshot(phase: .printing, stateText: "Printing", errorText: nil, file: nil, estimatedPrintTime: nil,
                                completion: 80, printTime: 100, printTimeLeft: 300, currentZ: nil, tool: nil, bed: nil)
        XCTAssertEqual(p.fraction, 0.25, accuracy: 0.0001)
        p.printTimeLeft = nil
        XCTAssertEqual(p.fraction, 0.8, accuracy: 0.0001)
    }

    func testPhases() {
        XCTAssertFalse(Phase.ready.busy)
        XCTAssertTrue(Phase.paused.busy)
        XCTAssertTrue(Phase.paused.canCancel)
        XCTAssertFalse(Phase.paused.canPause)
    }

    func testLooseJson() {
        let o: JSON = ["a": "12%", "b": 12, "c": true, "d": "true", "e": NSNull()]
        XCTAssertEqual(o.int("a"), 12)
        XCTAssertEqual(o.int("b"), 12)
        XCTAssertNil(o.dbl("c"), "a bool is not a number")
        XCTAssertEqual(o.bool("d"), true)
        XCTAssertNil(o.str("e"))
    }
}

final class AlertsConfigTests: XCTestCase {
    /// Every command is one YAML single-quoted string: unquoted, the "Title: ..." headers
    /// break config.yaml, and a stray single quote inside would end the string early.
    func testEveryCommandIsQuotedForYaml() {
        let lines = alertsConfig(topicURL: "https://ntfy.sh/octoshow-test").split(separator: "\n").map(String.init)
        XCTAssertEqual(lines.prefix(3), ["events:", "  enabled: true", "  subscriptions:"])
        let commands = lines.filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix("command:") }
        XCTAssertEqual(commands.count, 4)
        for c in commands {
            let value = c.components(separatedBy: "command: ")[1]
            XCTAssertTrue(value.hasPrefix("'") && value.hasSuffix("'"), c)
            XCTAssertFalse(value.dropFirst().dropLast().contains("'"), c)
            XCTAssertTrue(value.contains("https://ntfy.sh/octoshow-test"), c)
        }
        XCTAssertEqual(lines.filter { $0.hasPrefix("  - event: ") }.count, 4, "list entries sit under subscriptions:")
    }
}

final class DiscoveryTests: XCTestCase {
    func testLooksOnTheAnnouncedPortFirstThenOctoPrintsUsualOnes() {
        // an OctoPrint announcing 80 while it serves on 5001, as one seen in the wild does
        XCTAssertEqual(discoveryPorts(announced: 80), [80, 5000, 5001])
        XCTAssertEqual(discoveryPorts(announced: 5001), [5001, 5000])
        XCTAssertEqual(discoveryPorts(announced: 0), [5000, 5001])
    }

    func testTidiesOctoPrintsDefaultName() {
        XCTAssertEqual(tidyServiceName("OctoPrint instance on octopi"), "octopi")
        XCTAssertEqual(tidyServiceName("Ender 3"), "Ender 3")
        XCTAssertEqual(tidyServiceName("OctoPrint instance on "), "OctoPrint instance on ")
    }
}

final class DisplayStatusTests: XCTestCase {
    func testReadsWhatTheEchoShowReports() throws {
        let now = Date()
        let o: JSON = ["name": "Echo Show 5", "plug": true, "plugOn": NSNull(), "canGoOff": true, "offWhenDone": false,
                       "step": ["kind": "cooling", "nozzle": 112], "age": 30]
        let d = try XCTUnwrap(DisplayStatus(o, now: now))
        XCTAssertEqual(d.name, "Echo Show 5")
        XCTAssertTrue(d.plug)
        XCTAssertNil(d.plugOn)
        XCTAssertEqual(d.step, PowerStepInfo(kind: .cooling, nozzle: 112))
        XCTAssertEqual(d.heard.timeIntervalSince(now), -30, accuracy: 0.01)
        XCTAssertTrue(d.fresh(at: now))
        XCTAssertFalse(d.fresh(at: now.addingTimeInterval(61)), "90 seconds without a report and it may have gone")
    }

    func testStepsKnowWhenTheyCanStillBeStopped() {
        XCTAssertTrue(PowerStepInfo(kind: .cooling).canBackOut)
        XCTAssertTrue(PowerStepInfo(kind: .afterPrint, seconds: 20).canBackOut)
        XCTAssertFalse(PowerStepInfo(kind: .shuttingDown).canBackOut, "the Pi has been told to go")
        XCTAssertTrue(PowerStepInfo(kind: .settling, seconds: 9).committed)
        XCTAssertNil(PowerStepInfo(["kind": "explode"]), "an unknown step is no step")
        XCTAssertNil(DisplayStatus(nil))
    }
}

final class SwitchOffStoryTests: XCTestCase {
    private let heard = Date(timeIntervalSince1970: 1_800_000_000)

    func testFollowedWhileOctoPrintAnswers() {
        let cooling = SwitchOffStory(step: PowerStepInfo(kind: .cooling, nozzle: 112), heard: heard, online: true, now: heard)
        XCTAssertEqual(cooling.phase, .running)
        XCTAssertTrue(cooling.detail.contains("112°"))
    }

    /// The display's last steps can't reach the phone: the Pi, and OctoPrint with it, has gone.
    /// A phone locked during "shutting down" and opened later should know how it ended.
    func testOctoPrintGoneQuietAfterTheShutdownMeansSwitchedOff() {
        let step = PowerStepInfo(kind: .shuttingDown)
        let soon = SwitchOffStory(step: step, heard: heard, online: false, now: heard.addingTimeInterval(20))
        XCTAssertEqual(soon.phase, .running, "the power goes about a minute after the Pi stops")
        let later = SwitchOffStory(step: step, heard: heard, online: false, now: heard.addingTimeInterval(10 * 60))
        XCTAssertEqual(later.phase, .done)
        XCTAssertEqual(later.title, "Switched off")
        XCTAssertEqual(SwitchOffStory(step: step, heard: heard, online: true, now: heard.addingTimeInterval(600)).phase, .running,
                       "while OctoPrint still answers, the Pi hasn't gone")
    }

    func testOctoPrintGoneQuietBeforeTheShutdownIsLostTouch() {
        let story = SwitchOffStory(step: PowerStepInfo(kind: .cooling, nozzle: 90), heard: heard, online: false, now: heard.addingTimeInterval(600))
        XCTAssertEqual(story.phase, .lostTouch)
    }

    func testAFailureSaysWhy() {
        let story = SwitchOffStory(step: PowerStepInfo(kind: .failed, message: "A print is running, so everything was left on."),
                                   heard: heard, online: true, now: heard)
        XCTAssertEqual(story.phase, .failed)
        XCTAssertEqual(story.detail, "A print is running, so everything was left on.")
    }

    func testAStepThatIsUnderWayIsKeptWhenOctoPrintGoesQuiet() {
        XCTAssertTrue(PowerStepInfo(kind: .cooling).underWay)
        XCTAssertTrue(PowerStepInfo(kind: .shuttingDown).underWay)
        XCTAssertFalse(PowerStepInfo(kind: .confirm).underWay)
        XCTAssertFalse(PowerStepInfo(kind: .failed).underWay)
    }
}

final class CardCopyTests: XCTestCase {
    func testReadsTheCardTransferPluginsAccountOfACopy() throws {
        let o: JSON = ["name": "benchy.gcode", "fraction": 0.42, "print_after": true, "heating": ["bed": 60, "tool0": 150.0]]
        let c = try XCTUnwrap(CardCopy(o))
        XCTAssertEqual(c, CardCopy(name: "benchy.gcode", fraction: 0.42, printAfter: true, bed: 60, nozzle: 150))
    }

    func testNothingHeatingAndOddFiguresAreTakenCalmly() throws {
        let c = try XCTUnwrap(CardCopy(["name": "cube.gcode", "fraction": "1.7", "heating": ["bed": 0]]))
        XCTAssertEqual(c.fraction, 1, "kept within 0...1")
        XCTAssertNil(c.bed, "a heater at 0 isn't heating")
        XCTAssertNil(c.nozzle)
        XCTAssertFalse(c.printAfter)
        XCTAssertNil(CardCopy(["fraction": 0.5]), "a copy of nothing is no copy")
        XCTAssertNil(CardCopy(nil))
    }

    func testARefusalForAFileNotOnTheCardSaysSo() {
        XCTAssertEqual(forPeople(OctoHttpError(code: 409, detail: "The file is not on the printer's SD card")),
                       "That file isn’t on the printer’s SD card yet.")
        XCTAssertEqual(forPeople(OctoHttpError(code: 409, detail: "Printer is not operational")), "The printer can't do that right now.")
    }
}
