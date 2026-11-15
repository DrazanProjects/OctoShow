import XCTest
@testable import OctoShow

/// Talks to the stand-in OctoPrint in tools/mock-octoprint, as the Android tests do, and is
/// skipped when it isn't running. Start one copy with `node server.js 5001` (http 5001,
/// https 5444); the Simulator reaches this Mac's localhost directly. Port 5000 is left alone
/// because macOS's AirPlay Receiver usually has it.
final class MockOctoPrintTests: XCTestCase {
    private let key = "MOCKKEY0123456789ABCDEF"
    private let http = URL(string: "http://127.0.0.1:5001/")!
    private let https = URL(string: "https://127.0.0.1:5444/")!

    override func setUp() async throws {
        let probe = OctoApi(route: Route(base: http), apiKey: nil)
        guard await probe.reachable(timeout: 1) else { throw XCTSkip("mock OctoPrint isn't running on 5001") }
        _ = try? await URLSession.shared.data(from: http.appending(path: "mock/set").appending(queryItems: [
            URLQueryItem(name: "scene", value: "printing"), URLQueryItem(name: "progress", value: "0.3"),
        ]))
    }

    func testSelfSignedCertificateIsLearnedThenPinned() async throws {
        let learning = OctoApi(route: Route(base: https), apiKey: key, learning: true)
        _ = try await learning.keyHome()
        let print = try XCTUnwrap(learning.trust.learned, "the Pi's own certificate is noted at setup")

        let pinned = OctoApi(route: Route(base: https, cert: print), apiKey: key)
        let version = try await pinned.version()
        XCTAssertFalse(version.isEmpty)

        // A different certificate from the one set up with is refused, and said so.
        let wrong = OctoApi(route: Route(base: https, cert: String(repeating: "0", count: 64)), apiKey: key)
        do {
            _ = try await wrong.version()
            XCTFail("a certificate other than the pinned one must be refused")
        } catch {
            XCTAssertTrue(wrong.trust.changed)
        }

        // Without learning or a pin, a self-signed certificate is refused too.
        let stranger = OctoApi(route: Route(base: https), apiKey: key)
        let reached = await stranger.reachable(timeout: 3)
        XCTAssertFalse(reached)
    }

    func testFilesAreFetchedOnceAndThenRevalidated() async throws {
        let api = OctoApi(route: Route(base: http), apiKey: key)
        let first = try await api.files()
        XCTAssertGreaterThanOrEqual(first.newestFirst.count, 6)
        let again = try await api.files()
        XCTAssertEqual(again.newestFirst.map(\.path), first.newestFirst.map(\.path))
    }

    func testKeyIsRefusedWhenWrong() async throws {
        let api = OctoApi(route: Route(base: http), apiKey: "NOTTHEKEY")
        let accepted = await api.keyAccepted()
        XCTAssertEqual(accepted, false)
    }

    func testPushSocketDeliversPrinterState() async throws {
        let api = OctoApi(route: Route(base: http), apiKey: key)
        let got = expectation(description: "printer state over the socket")
        got.assertForOverFulfill = false
        let seen = Seen()
        let live = OctoLive(api: api) { update in
            if case .printer(let p) = update, p.phase.busy, await seen.once() { got.fulfill() }
        }
        let task = Task { try await live.listen() }
        await fulfillment(of: [got], timeout: 15)
        task.cancel()
    }
}

private actor Seen {
    private var done = false
    func once() -> Bool {
        defer { done = true }
        return !done
    }
}
