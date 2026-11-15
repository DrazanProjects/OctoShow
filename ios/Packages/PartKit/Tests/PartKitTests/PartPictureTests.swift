import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import XCTest
@testable import PartKit

/// The panel's picture, drawn off screen: the layer fills in where the nozzle has been, and only there.
@MainActor
final class PartPictureTests: XCTestCase {

    private static let ember = CGColor(srgbRed: 1, green: 0x6B / 255.0, blue: 0x2C / 255.0, alpha: 1)
    private static let bone = CGColor(srgbRed: 0xE9 / 255.0, green: 0xE4 / 255.0, blue: 0xD8 / 255.0, alpha: 1)

    private func growth(layer: Int) throws -> Growth {
        let reading = try XCTUnwrap(try GcodePreview.read({ InputStream(data: Data(PartLibraryTests.marked.utf8)) }))
        let whole = try XCTUnwrap(GcodePreview.render(reading.shape)?.tinted(PartPictureTests.bone))
        let route = reading.route
        let one = route.layers.first { $0.number == layer }!
        let p = try XCTUnwrap(try GcodePreview.render(reading.shape, route: route, layer: one))
        return try XCTUnwrap(PartLibrary.growth(p, whole: whole, tint: RGB(PartPictureTests.bone), hot: RGB(PartPictureTests.ember),
                                                layer: layer, dated: false, view: reading.shape.view()))
    }

    private func render(_ growth: Growth, done: Float, laying: Bool = false) throws -> CGImage {
        let glide = LayerGlide()
        glide.set(done, now: 0)
        let view = PartPicture(
            thumb: nil, reveal: 0.5, note: "", grown: growth, waiting: false, laying: laying, layerDone: done, liveDone: done,
            glide: glide, glideEnds: 0, hot: PartView.ember, hotHi: PartView.hotHi(PartPictureTests.ember), memo: BoundsMemo()
        ).frame(width: 416, height: 312)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        return try XCTUnwrap(renderer.cgImage)
    }

    /// How much warmer than it is blue the picture is, summed over its pixels: ember shows as red well above blue.
    private func warmth(_ image: CGImage) -> Int {
        let w = image.width, h = image.height
        var px = [UInt8](repeating: 0, count: w * h * 4)
        px.withUnsafeMutableBytes { raw in
            let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)!
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        var warm = 0
        for i in 0..<(w * h) where Int(px[4 * i]) - Int(px[4 * i + 2]) > 80 { warm += 1 }
        return warm
    }

    private func save(_ image: CGImage, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["PARTKIT_OUT"] else { return }
        let url = URL(fileURLWithPath: dir).appendingPathComponent(name + ".png")
        if let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) {
            CGImageDestinationAddImage(dest, image, nil)
            CGImageDestinationFinalize(dest)
        }
    }

    func testTheLayerFillsInAsTheNozzleGoes() throws {
        let grown = try growth(layer: 5)
        XCTAssertNotNil(grown.order)
        let none = try render(grown, done: 0)
        let half = try render(grown, done: 0.5, laying: true)
        let all = try render(grown, done: 1)
        save(none, "panel-layer5-000")
        save(half, "panel-layer5-050")
        save(all, "panel-layer5-100")
        let (a, b, c) = (warmth(none), warmth(half), warmth(all))
        XCTAssertLessThan(a, b, "half the layer down shows more of it than none: \(a), \(b)")
        XCTAssertLessThan(b, c, "and all of it more than half: \(b), \(c)")
        XCTAssertGreaterThan(c, 500, "a whole layer down is plain to see")
    }

    func testTheOrderKeepsOnlyWhatIsDown() {
        // the color matrix on an order picture's alpha: nothing passes at the start, the first
        // moments by half way, and everything, down to the last moment's opacity, at the end
        let none = PartPicture.laidBy(0)
        let half = PartPicture.laidBy(0.5)
        let all = PartPicture.laidBy(1)
        func alpha(_ m: ColorMatrix, _ a: Float) -> Float { clamp(m.a4 * a + m.a5, 0, 1) }
        XCTAssertEqual(alpha(none, 255 / 255), 0, accuracy: 0.001)
        XCTAssertEqual(alpha(half, 255 / 255), 1, accuracy: 0.001)
        XCTAssertEqual(alpha(half, 100 / 255), 0, accuracy: 0.001)
        XCTAssertEqual(alpha(all, 16 / 255), 1, accuracy: 0.001)
    }
}
