import XCTest
@testable import OctoShow

/// Talks to the stand-in OctoPrint in tools/mock-octoprint, as the Android tests do, and is
/// skipped when it isn't running. Start one copy with `node server.js 5001` (http 5001,
/// https 5444); the Simulator reaches this Mac's localhost directly. Port 5000 is left alone
/// because macOS's AirPlay Receiver usually has it.
final class MockOctoPrintTests: XCTestCase {
    let key = "MOCKKEY0123456789ABCDEF"
    let http = URL(string: "http://127.0.0.1:5001/")!
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

extension MockOctoPrintTests {
    /// The phone asking the Echo Show to switch off, through the OctoShow plugin, with the mock
    /// playing the display. A print is running, so it refuses, then takes "when done" and "stay".
    func testAskingTheEchoShowThroughThePlugin() async throws {
        _ = try? await URLSession.shared.data(from: http.appending(path: "mock/set").appending(queryItems: [
            URLQueryItem(name: "scene", value: "printing"), URLQueryItem(name: "echoshow", value: "1"),
        ]))
        let api = OctoApi(route: Route(base: http), apiKey: key)
        let answer = await api.displayStatus()
        let listening = try XCTUnwrap(answer, "the mock has the plugin")
        XCTAssertEqual(listening?.plug, true)

        let heard = Heard()
        let live = OctoLive(api: api) { update in
            if case .display(let d) = update { await heard.add(d) }
        }
        let task = Task { try await live.listen() }
        defer { task.cancel() }
        try await Task.sleep(for: .seconds(1))

        try await api.askDisplay("off")
        try await heard.waitFor { $0.step?.kind == .failed && $0.step?.message == "A print is running" }
        try await api.askDisplay("offWhenDone")
        try await heard.waitFor { $0.offWhenDone }
        try await api.askDisplay("stay")
        try await heard.waitFor { !$0.offWhenDone && $0.step == nil }
    }

    func testNoPluginMeansNoButton() async throws {
        _ = try? await URLSession.shared.data(from: http.appending(path: "mock/set").appending(queryItems: [
            URLQueryItem(name: "scene", value: "ready"), URLQueryItem(name: "without", value: "octoshow"),
        ]))
        let answer = await OctoApi(route: Route(base: http), apiKey: key).displayStatus()
        XCTAssertNil(answer, "an OctoPrint without the plugin")
        _ = try? await URLSession.shared.data(from: http.appending(path: "mock/set").appending(queryItems: [
            URLQueryItem(name: "scene", value: "ready"),
        ]))
        let none = await OctoApi(route: Route(base: http), apiKey: key).displayStatus()
        XCTAssertNotNil(none, "the plugin is there")
        XCTAssertNil(none!, "but no display has reported")
        do {
            try await OctoApi(route: Route(base: http), apiKey: key).askDisplay("off")
            XCTFail("with no display listening the request should be turned down")
        } catch let e as OctoHttpError {
            XCTAssertEqual(e.code, 409)
        }
    }
}

extension MockOctoPrintTests {
    private func scene(_ items: [String: String]) async {
        let q = items.map { URLQueryItem(name: $0.key, value: $0.value) }
        _ = try? await URLSession.shared.data(from: http.appending(path: "mock/set").appending(queryItems: q))
    }

    private func mock(_ path: String, _ items: [String: String]) async {
        let q = items.map { URLQueryItem(name: $0.key, value: $0.value) }
        _ = try? await URLSession.shared.data(from: http.appending(path: path).appending(queryItems: q))
    }

    /// A file going onto the printer's SD card, with the Card Transfer plugin: OctoPrint calls the
    /// printer offline meanwhile. The session follows the copy as it goes and says when it fails.
    @MainActor
    func testACopyOntoTheCardIsFollowedToItsEnd() async throws {
        await scene(["scene": "offline", "copying": "0.42", "copyprint": "1", "copyheat": "1"])
        let session = PrinterSession(server: Server(name: "mock", apiKey: key, home: Route(base: http)), prefs: Prefs())
        session.start()
        defer { session.stop() }
        try await until { session.cardCopy?.fraction == 0.42 }
        XCTAssertEqual(session.cardCopy?.printAfter, true)
        XCTAssertEqual(session.cardCopy?.bed, 60)
        XCTAssertEqual(session.cardCopy?.nozzle, 150)
        XCTAssertEqual(session.printer?.phase, .offline)

        await mock("mock/copy", ["stage": "progress", "fraction": "0.8"])
        try await until { session.cardCopy?.fraction == 0.8 }

        await mock("mock/copy", ["stage": "failed", "error": "the printer stopped answering"])
        try await until { session.cardCopy == nil }
        XCTAssertTrue(session.notice?.contains("SD card failed: the printer stopped answering") == true, session.notice ?? "nothing said")
    }

    /// Asked about a file in OctoPrint's storage, the plugin says whether the card has it; the two
    /// ways of printing one that it hasn't go to the plugin.
    func testThePluginIsAskedWhetherTheCardHasTheFile() async throws {
        await scene(["scene": "ready", "cardtransfer": "missing"])
        let api = OctoApi(route: Route(base: http), apiKey: key)
        let listed = try await api.files()
        let file = try XCTUnwrap(listed.newestFirst.first)
        let missing = await api.onCard(file)
        XCTAssertEqual(missing, false)
        try await api.copyToCardAndPrint(file)

        await scene(["scene": "ready", "cardtransfer": "oncard"])
        let there = await api.onCard(file)
        XCTAssertEqual(there, true)

        await scene(["scene": "ready"])
        let none = await api.onCard(file)
        XCTAssertNil(none, "without the plugin there is nothing to ask")
        let copying = await api.cardCopy()
        XCTAssertNil(copying)
    }

    /// OctoPrint calls a job done once its last line is in the printer, then says Finishing while
    /// the printer works through what it holds, and only then Operational. Done is still done after
    /// that, and it finished when the printer stopped.
    @MainActor
    func testAJobThatEndsThroughFinishingIsDoneWhenThePrinterStops() async throws {
        await scene(["scene": "printing"])
        let session = PrinterSession(server: Server(name: "mock", apiKey: key, home: Route(base: http)), prefs: Prefs())
        session.start()
        defer { session.stop() }
        try await until { session.printer?.phase == .printing }

        await scene(["scene": "finishing"])
        try await until { session.printer?.phase == .finishing && session.outcome != nil }
        let told = try XCTUnwrap(session.outcome?.at, "the end was seen, so it has a time")
        try await until(within: .seconds(8)) { session.printer?.phase == .ready }
        XCTAssertEqual(session.outcome?.kind, .done, "the job should still count as done")
        let stopped = try XCTUnwrap(session.outcome?.at)
        XCTAssertGreaterThan(stopped, told, "and say when the printer stopped, not when it was told")
    }

    @MainActor
    private func until(within: Duration = .seconds(6), _ check: () -> Bool) async throws {
        let end = ContinuousClock.now + within
        while ContinuousClock.now < end {
            if check() { return }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTFail("waited \(within) in vain")
        throw CancellationError()
    }
}

/// What the socket has passed on from the display, waited on by a test.
private actor Heard {
    private var seen: [DisplayStatus] = []
    func add(_ d: DisplayStatus) { seen.append(d) }
    func waitFor(_ match: @Sendable (DisplayStatus) -> Bool, within: Duration = .seconds(5)) async throws {
        let until = ContinuousClock.now + within
        while ContinuousClock.now < until {
            if seen.contains(where: match) { return }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTFail("the display never said so; it said: \(seen.map { "\($0.offWhenDone) \(String(describing: $0.step))" })")
    }
}

private actor Seen {
    private var done = false
    func once() -> Bool {
        defer { done = true }
        return !done
    }
}
