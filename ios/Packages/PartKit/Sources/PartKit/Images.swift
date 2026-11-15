import CoreGraphics
import Foundation
import ImageIO

/// An sRGB color as the Kotlin had them: 0xRRGGBB, eight bits a channel.
struct RGB: Hashable, Sendable {
    let r: Int, g: Int, b: Int

    init(r: Int, g: Int, b: Int) { self.r = r; self.g = g; self.b = b }

    init(_ color: CGColor) {
        let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
        let c = color.converted(to: srgb, intent: .defaultIntent, options: nil) ?? color
        let parts = c.components ?? [1, 1, 1, 1]
        func channel(_ v: CGFloat) -> Int { clamp(Int((v * 255).rounded()), 0, 255) }
        if parts.count >= 3 {
            self.init(r: channel(parts[0]), g: channel(parts[1]), b: channel(parts[2]))
        } else {
            let v = channel(parts.first ?? 1)
            self.init(r: v, g: v, b: v)
        }
    }

    var key: Int { r << 16 | g << 8 | b }
}

/// Pictures in and out of CoreGraphics, and on and off disk.
enum Images {
    static let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    static let grey = CGColorSpaceCreateDeviceGray()

    /**
     * An image from ARGB pixels that are not premultiplied, as Android's Bitmap.createBitmap
     * takes them (0xAARRGGBB). Premultiplied here, which is what CoreGraphics draws fastest.
     */
    static func image(argb: [UInt32], width: Int, height: Int) -> CGImage? {
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<(width * height) {
            let p = argb[i]
            let a = Int(p >> 24)
            if a == 0 { continue }
            let r = Int(p >> 16 & 0xFF), g = Int(p >> 8 & 0xFF), b = Int(p & 0xFF)
            bytes[4 * i] = UInt8((r * a + 127) / 255)
            bytes[4 * i + 1] = UInt8((g * a + 127) / 255)
            bytes[4 * i + 2] = UInt8((b * a + 127) / 255)
            bytes[4 * i + 3] = UInt8(a)
        }
        return rgba(bytes, width: width, height: height)
    }

    /// Premultiplied RGBA bytes, four to a pixel.
    static func rgba(_ bytes: [UInt8], width: Int, height: Int) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(bytes) as CFData) else { return nil }
        return CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
            space: srgb, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }

    /**
     * A picture of greys with alphas, as [GcodePreview.Picture.shown] gives them, in the color [tint].
     * [shown] is 0xAAGGGGGG, the grey three times over.
     */
    static func tinted(_ shown: [UInt32], width: Int, height: Int, tint: RGB) -> CGImage? {
        var pixels = [UInt32](repeating: 0, count: shown.count)
        for i in shown.indices {
            let p = shown[i]
            let light = Int(p & 0xFF)
            pixels[i] = (p & 0xFF00_0000) | UInt32(tint.r * light / 255) << 16 | UInt32(tint.g * light / 255) << 8 | UInt32(tint.b * light / 255)
        }
        return image(argb: pixels, width: width, height: height)
    }

    /* ---------------- on disk ---------------- */

    /// Grey-and-alpha pixels as stored: (grey, alpha) pairs, not premultiplied.
    struct GreyAlpha {
        let width: Int
        let height: Int
        let bytes: [UInt8]

        /// As [GcodePreview.Picture.shown] gives them.
        var shown: [UInt32] {
            var out = [UInt32](repeating: 0, count: width * height)
            for i in out.indices {
                let g = UInt32(bytes[2 * i])
                out[i] = UInt32(bytes[2 * i + 1]) << 24 | g << 16 | g << 8 | g
            }
            return out
        }
    }

    /// Writes a picture as a grey-and-alpha PNG, under another name first so a half-written one is never taken for finished.
    static func savePNG(_ picture: GcodePreview.Picture, to url: URL) throws {
        var bytes = [UInt8](repeating: 0, count: picture.width * picture.height * 2)
        for i in 0..<(picture.width * picture.height) {
            let s = picture.shown(i)
            bytes[2 * i] = UInt8(s & 0xFF)
            bytes[2 * i + 1] = UInt8(s >> 24)
        }
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let image = CGImage(
                width: picture.width, height: picture.height, bitsPerComponent: 8, bitsPerPixel: 16, bytesPerRow: picture.width * 2,
                space: grey, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
              ) else { throw CocoaError(.fileWriteUnknown) }
        let part = url.appendingPathExtension("part")
        guard let dest = CGImageDestinationCreateWithURL(part as CFURL, "public.png" as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        CGImageDestinationAddImage(dest, image, nil)
        if !CGImageDestinationFinalize(dest) {
            try? FileManager.default.removeItem(at: part)
            throw CocoaError(.fileWriteUnknown)
        }
        try moveOver(part, to: url)
    }

    /// Reads back what [savePNG] wrote, exactly.
    static func loadPNG(_ url: URL) -> GreyAlpha? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let w = image.width, h = image.height
        // as written: eight-bit grey then alpha, not premultiplied
        if image.bitsPerComponent == 8, image.bitsPerPixel == 16, image.colorSpace?.model == .monochrome,
           image.alphaInfo == .last, let data = image.dataProvider?.data {
            let row = image.bytesPerRow
            let src = data as Data
            var bytes = [UInt8](repeating: 0, count: w * h * 2)
            src.withUnsafeBytes { raw in
                let p = raw.bindMemory(to: UInt8.self)
                for y in 0..<h {
                    for x in 0..<(w * 2) { bytes[y * w * 2 + x] = p[y * row + x] }
                }
            }
            return GreyAlpha(width: w, height: h, bytes: bytes)
        }
        // otherwise through a drawing, which premultiplies; undone as well as it can be
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        let drawn: Bool = rgba.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: srgb,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        if !drawn { return nil }
        var bytes = [UInt8](repeating: 0, count: w * h * 2)
        for i in 0..<(w * h) {
            let a = Int(rgba[4 * i + 3])
            bytes[2 * i + 1] = UInt8(a)
            bytes[2 * i] = a == 0 ? 0 : UInt8(min(255, (Int(rgba[4 * i]) * 255 + a / 2) / a))
        }
        return GreyAlpha(width: w, height: h, bytes: bytes)
    }

    /// Takes the place of [to], which a drawing being made again already has.
    static func moveOver(_ from: URL, to: URL) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: to.path) { try? fm.removeItem(at: to) }
        do {
            try fm.moveItem(at: from, to: to)
        } catch {
            try? fm.removeItem(at: from)
            throw error
        }
    }

    /// Any CGImage as PNG data, for looking at.
    public static func pngData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data as CFMutableData, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        return CGImageDestinationFinalize(dest) ? data as Data : nil
    }
}

extension GcodePreview.Picture {
    /// The picture in [tint], as the display shows a finished part.
    public func tinted(_ tint: CGColor) -> CGImage? {
        Images.tinted((0..<(width * height)).map { shown($0) }, width: width, height: height, tint: RGB(tint))
    }
}
