import CoreGraphics
import Foundation
import XCTest
@testable import PartKit

/**
 * The library fetching from a running mock OctoPrint (tools/mock-octoprint) through
 * [PartLibrary.download]. Skipped unless asked for:
 *
 *   node tools/mock-octoprint/server.js 5010 &
 *   MOCK_OCTOPRINT=http://127.0.0.1:5010 swift test -c release -Xswiftc -enable-testing --filter MockOctoPrintTests
 */
final class MockOctoPrintTests: XCTestCase {

    private let key = "MOCKKEY0123456789ABCDEF"

    private func library(_ base: URL, _ dir: URL, gentle: Bool) -> PartLibrary {
        let key = self.key
        return PartLibrary(directory: dir) { file, to, gentleNow in
            let path = file.path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? file.path
            var request = URLRequest(url: URL(string: "\(base.absoluteString)/downloads/files/\(file.origin)/\(path)")!)
            request.setValue(key, forHTTPHeaderField: "X-Api-Key")
            try await PartLibrary.download(request, to: to, gentle: { gentle && gentleNow })
        }
    }

    func testDrawsTheJobsFileFetchedFromOctoPrint() async throws {
        guard let base = ProcessInfo.processInfo.environment["MOCK_OCTOPRINT"].flatMap(URL.init(string:)) else {
            throw XCTSkip("set MOCK_OCTOPRINT to a running mock's address")
        }
        let file = PartFile(origin: "local", path: "Organizers/Pegboard_hook_PLA_35m.gcode", display: "Pegboard hook",
                            date: 1_700_000_000, size: 243_305)
        for gentle in [false, true] {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("PartKitMock-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: dir) }
            let lib = library(base, dir, gentle: gentle)
            let began = Date()
            let outcome = await lib.want(file, gentle: true)
            let took = Date().timeIntervalSince(began)
            print(String(format: "fetched and drew %@ in %.2f s, %@", file.display, took, gentle ? "held to a trickle" : "at full speed"))
            XCTAssertEqual(outcome, .drawn)
            XCTAssert(lib.hasShape(file))
            if gentle {
                // 243 kB at 1.2 MB/s is a fifth of a second at the least
                XCTAssertGreaterThan(took, 0.18, "the trickle should hold it back")
            }
            let layers = try XCTUnwrap(lib.layersOf(file))
            XCTAssertEqual(layers.numbers.count, 150, "the mock marks every layer of the hook")
            let tint = CGColor(srgbRed: 0xE9 / 255.0, green: 0xE4 / 255.0, blue: 0xD8 / 255.0, alpha: 1)
            let hot = CGColor(srgbRed: 1, green: 0x6B / 255.0, blue: 0x2C / 255.0, alpha: 1)
            let grown = await lib.grown(file, fraction: 0.5, layer: 75, tint: tint, hot: hot)
            XCTAssertEqual(grown?.layer, 75)
            XCTAssertNotNil(grown?.order)
        }
    }

    func testAFileOctoPrintDoesNotHaveFails() async throws {
        guard let base = ProcessInfo.processInfo.environment["MOCK_OCTOPRINT"].flatMap(URL.init(string:)) else {
            throw XCTSkip("set MOCK_OCTOPRINT to a running mock's address")
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("PartKitMock-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let lib = library(base, dir, gentle: false)
        let missing = PartFile(origin: "local", path: "no_such_file.gcode", display: "missing", date: 1, size: 10)
        let outcome = await lib.want(missing, gentle: false)
        XCTAssertEqual(outcome, .failed)
        XCTAssertFalse(lib.settled(missing))
    }
}
