import Foundation
import zlib

// The drawing is ported from Kotlin, and is meant to come out the same to the pixel. These
// do what the Kotlin did where Swift's own would differ: Kotlin's Float.toInt() truncates,
// saturates at the 32-bit range and makes NaN 0 (Swift's traps); roundToInt() rounds halves
// up, as Java's Math.round does (Swift's rounds them away from zero); and hypot of two floats
// is worked out in doubles.

/// Kotlin's `Float.toInt()`.
@inline(__always) func kInt(_ f: Float) -> Int {
    if f.isNaN { return 0 }
    if f >= 2_147_483_648.0 { return Int(Int32.max) }
    if f <= -2_147_483_648.0 { return Int(Int32.min) }
    return Int(f)
}

/// Kotlin's `Double.toInt()`.
@inline(__always) func kInt(_ d: Double) -> Int {
    if d.isNaN { return 0 }
    if d >= 2_147_483_647.0 { return Int(Int32.max) }
    if d <= -2_147_483_648.0 { return Int(Int32.min) }
    return Int(d)
}

/// Kotlin's `Float.roundToInt()`, which is Java's `Math.round(float)`: the floor of x + 0.5, worked out exactly.
@inline(__always) func kRound(_ f: Float) -> Int {
    if f.isNaN { return 0 }
    return kInt((Double(f) + 0.5).rounded(.down))
}

/// Kotlin's `Double.roundToInt()`.
@inline(__always) func kRound(_ d: Double) -> Int {
    if d.isNaN { return 0 }
    return kInt((d + 0.5).rounded(.down))
}

/// Kotlin's `hypot(Float, Float)`, which goes by way of doubles.
@inline(__always) func khypot(_ a: Float, _ b: Float) -> Float {
    Float(Foundation.hypot(Double(a), Double(b)))
}

@inline(__always) func clamp<T: Comparable>(_ v: T, _ lo: T, _ hi: T) -> T {
    v < lo ? lo : (v > hi ? hi : v)
}

/// Kotlin's `Math.toRadians` on the JVM.
@inline(__always) func radians(_ degrees: Double) -> Double { degrees * (Double.pi / 180.0) }

/* ---------------- zlib, for what is packed small ---------------- */

enum Zlib {
    struct Failed: Error {}

    /// Packs [bytes] the way java.util.zip.Deflater does by default: zlib's own wrapper, its default level.
    static func deflate(_ bytes: [UInt8], gzip: Bool = false) -> [UInt8] {
        var stream = z_stream()
        let windowBits: Int32 = gzip ? 15 + 16 : 15
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, windowBits, 8, Z_DEFAULT_STRATEGY,
                            ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return [] }
        defer { deflateEnd(&stream) }
        var out = [UInt8](repeating: 0, count: Int(deflateBound(&stream, uLong(bytes.count))) + 64)
        var input = bytes
        let produced: Int = input.withUnsafeMutableBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                stream.next_in = src.baseAddress
                stream.avail_in = uInt(src.count)
                stream.next_out = dst.baseAddress
                stream.avail_out = uInt(dst.count)
                _ = zlib.deflate(&stream, Z_FINISH)
                return dst.count - Int(stream.avail_out)
            }
        }
        out.removeSubrange(produced...)
        return out
    }

    /**
     * Unpacks into [into] as far as it goes, as java.util.zip.Inflater does: a stream that ends
     * early leaves the rest as it was, and one that isn't zlib at all is an error.
     */
    static func inflate(_ packed: [UInt8], into out: inout [UInt8], gzip: Bool = false) throws {
        if out.isEmpty { return }
        var stream = z_stream()
        let windowBits: Int32 = gzip ? 15 + 16 : 15
        guard inflateInit2_(&stream, windowBits, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw Failed() }
        defer { inflateEnd(&stream) }
        var input = packed
        let result: Int32 = input.withUnsafeMutableBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                stream.next_in = src.baseAddress
                stream.avail_in = uInt(src.count)
                stream.next_out = dst.baseAddress
                stream.avail_out = uInt(dst.count)
                return zlib.inflate(&stream, Z_NO_FLUSH)
            }
        }
        if result == Z_DATA_ERROR || result == Z_NEED_DICT || result == Z_MEM_ERROR || result == Z_STREAM_ERROR { throw Failed() }
    }

    /// The whole of a gzip stream, however long it unpacks to.
    static func gunzip(_ packed: [UInt8]) throws -> [UInt8] {
        var stream = z_stream()
        guard inflateInit2_(&stream, 15 + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw Failed() }
        defer { inflateEnd(&stream) }
        var input = packed
        var out = [UInt8]()
        var chunk = [UInt8](repeating: 0, count: 1 << 18)
        try input.withUnsafeMutableBufferPointer { src in
            stream.next_in = src.baseAddress
            stream.avail_in = uInt(src.count)
            while true {
                let (code, got): (Int32, Int) = chunk.withUnsafeMutableBufferPointer { dst in
                    stream.next_out = dst.baseAddress
                    stream.avail_out = uInt(dst.count)
                    let code = zlib.inflate(&stream, Z_NO_FLUSH)
                    return (code, dst.count - Int(stream.avail_out))
                }
                out.append(contentsOf: chunk[0..<got])
                if code == Z_STREAM_END { break }
                if code != Z_OK || (got == 0 && stream.avail_in == 0) { throw Failed() }
            }
        }
        return out
    }
}
