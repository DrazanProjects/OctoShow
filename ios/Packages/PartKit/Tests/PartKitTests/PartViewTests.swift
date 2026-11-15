#if os(macOS)
import AppKit
import CoreGraphics
import Foundation
import SwiftUI
import XCTest
@testable import PartKit

/// The panel on screen, with its tasks running: it asks for the drawing, grows the part, and says when it can't.
@MainActor
final class PartViewTests: XCTestCase {

    private var dir: URL!
    private var window: NSWindow?

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("PartViewTests-\(UUID().uuidString)")
        _ = NSApplication.shared
    }

    override func tearDown() async throws {
        window?.close()
        window = nil
        try? FileManager.default.removeItem(at: dir)
    }

    private func show<V: View>(_ view: V) {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 300), styleMask: [.borderless], backing: .buffered, defer: false)
        w.isReleasedWhenClosed = false
        w.contentView = NSHostingView(rootView: view)
        w.orderFrontRegardless()
        window = w
    }

    private func waitFor(_ seconds: TimeInterval, _ done: () -> Bool) async {
        let until = Date().addingTimeInterval(seconds)
        while !done() && Date() < until {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    private final class Seen: @unchecked Sendable {
        var undrawable: [Bool] = []
        var fetched = 0
    }

    func testAJobsPartIsDrawnAndGrown() async throws {
        let seen = Seen()
        let library = PartLibrary(directory: dir) { _, to, _ in
            seen.fetched += 1
            try Data(PartLibraryTests.marked.utf8).write(to: to)
        }
        let file = PartFile(origin: "local", path: "slab.gcode", display: "slab", date: 1, size: 40_000)
        var turn = 0
        let binding = Binding(get: { turn }, set: { turn = $0 })
        show(PartView(library: library, file: file, fraction: 0.4, layer: 4, layerTotal: 10, layerProgress: 50,
                      printing: true, tint: Color(red: 0.91, green: 0.89, blue: 0.85), turn: binding,
                      onUndrawable: { seen.undrawable.append($0) }).frame(width: 400, height: 300))
        let tint = Color(red: 0.91, green: 0.89, blue: 0.85).resolve(in: EnvironmentValues()).cgColor
        let hot = PartView.ember.resolve(in: EnvironmentValues()).cgColor
        await waitFor(60) { library.lastGrown(file, tint: tint, hot: hot) != nil }
        await waitFor(0.5) { false }
        XCTAssertEqual(seen.fetched, 1, "the job's file is fetched once, as soon as the panel wants it")
        let grown = try XCTUnwrap(library.lastGrown(file, tint: tint, hot: hot), "the panel grew the part")
        XCTAssertEqual(grown.layer, 4, "at the layer the job is on")
        XCTAssertNotNil(grown.order)
        XCTAssertEqual(seen.undrawable.last, false)
        snapshot("panel-hosted")
    }

    /// The window's contents as a picture, to PARTKIT_OUT if it is set.
    private func snapshot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["PARTKIT_OUT"], let view = window?.contentView,
              let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
    }

    func testAFileWithNothingToDrawIsReportedUndrawable() async throws {
        let seen = Seen()
        let library = PartLibrary(directory: dir) { _, to, _ in
            seen.fetched += 1
            try Data("M83\nG1 Z0.2\nG1 X1 Y1\n".utf8).write(to: to)
        }
        let file = PartFile(origin: "local", path: "empty.gcode", display: "empty", date: 1, size: 100)
        var turn = 0
        let binding = Binding(get: { turn }, set: { turn = $0 })
        show(PartView(library: library, file: file, fraction: 0.4, layer: nil, layerTotal: nil, layerProgress: nil,
                      printing: true, tint: .white, turn: binding,
                      onUndrawable: { seen.undrawable.append($0) }).frame(width: 400, height: 300))
        await waitFor(60) { seen.undrawable.last == true }
        XCTAssertEqual(seen.undrawable.last, true, "a file with nothing in it to draw is given up on: \(seen.undrawable)")
        XCTAssert(library.settled(file))
        await waitFor(1.5) { false }
        XCTAssertEqual(seen.fetched, 1, "and isn't fetched again and again")
    }

    func testAFileThatCannotBeFetchedIsNotAskedForOverAndOver() async throws {
        let seen = Seen()
        let library = PartLibrary(directory: dir) { _, _, _ in
            seen.fetched += 1
            throw URLError(.cannotConnectToHost)
        }
        let file = PartFile(origin: "local", path: "away.gcode", display: "away", date: 1, size: 100)
        var turn = 0
        let binding = Binding(get: { turn }, set: { turn = $0 })
        show(PartView(library: library, file: file, fraction: 0.4, layer: nil, layerTotal: nil, layerProgress: nil,
                      printing: true, tint: .white, turn: binding,
                      onUndrawable: { seen.undrawable.append($0) }).frame(width: 400, height: 300))
        await waitFor(3) { false }
        XCTAssertGreaterThanOrEqual(seen.fetched, 1)
        XCTAssertLessThanOrEqual(seen.fetched, 2, "asked for once (and once more for its picture), then left for a minute")
        XCTAssertNotEqual(seen.undrawable.last, true, "still worth waiting for: the next try may work")
    }
}
#endif
