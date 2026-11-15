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
