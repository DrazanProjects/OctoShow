import Foundation

/**
 * Draws a picture of a part from nothing but its G-code, for files the slicer
 * didn't put a thumbnail in.
 *
 * The file is read twice. The first pass finds where the part sits and how
 * thick its layers are; the second lays every extruded line into a [Stack] of
 * the build plate, slice by slice, and notes how high the plastic reaches on
 * each small square of the bed. That is then looked at from above and to one
 * side, like a slicer's preview, turned so the part fills the picture, and
 * shaded by which way each bit of surface faces.
 *
 * Because the stack knows every layer, the air under an overhang stays air, a
 * pocket in the side of a part stays a pocket, and a part being printed shows
 * what is really inside it. Supports and brims are drawn too, dimmer than the
 * part. Skirts and purge lines are left out when the slicer labels them.
 *
 * The result is brightness and coverage only. Color is added when it is shown,
 * so the part appears in whatever filament color the display is set to.
 *
 * The second pass also notes the order each layer is laid down in, so that a
 * layer being printed can be shown filling in the way the nozzle really goes
 * over it: see [GcodePreview.Route].
 *
 * Ported line for line from the Android app's octo/GcodePreview.kt, so that the
 * two draw the same pictures. Floats stay floats where the Kotlin had them, and
 * conversions go through kInt/kRound, which do what Kotlin's do.
 */
public enum GcodePreview {

    /** The G-code couldn't be read. */
    public struct ReadFailed: Error {}

    /**
     * [width] x [height] pixels: how bright the surface is, and how much of the pixel it
     * covers. [fresh] is how much of what shows there is the layer being laid down,
     * which only a picture of the part half-printed has any of. [shadow] is how far the
     * bed is darkened around the part, where the part doesn't cover it.
     *
     * A picture drawn for one layer of a [Route] also says when in that layer the
     * nozzle gets to each pixel of it, in [reached]: 0 for never, otherwise 1..255
     * from the layer's start to its end. [trail] is where in the picture the nozzle
     * is at each of those 255 moments, as (across, down) pairs of fractions of its size.
     */
    public final class Picture: @unchecked Sendable {
        public let width: Int
        public let height: Int
        public let light: [UInt8]
        public let alpha: [UInt8]
        public let fresh: [UInt8]
        public let shadow: [UInt8]
        public let reached: [UInt8]?
        public let trail: [Float]?

        public init(width: Int, height: Int, light: [UInt8], alpha: [UInt8], fresh: [UInt8], shadow: [UInt8],
                    reached: [UInt8]? = nil, trail: [Float]? = nil) {
            self.width = width; self.height = height
            self.light = light; self.alpha = alpha; self.fresh = fresh; self.shadow = shadow
            self.reached = reached; self.trail = trail
        }

        /** Pixel [i] with its shadow under it, as a grey with an alpha: A, then the grey three times. */
        public func shown(_ i: Int) -> UInt32 {
            let cover = Int(alpha[i])
            let dark = Int(shadow[i])
            let all = cover + dark * (255 - cover) / 255
            if all == 0 { return 0 }
            let grey = Int(light[i]) * cover / all
            return UInt32(all) << 24 | UInt32(grey) << 16 | UInt32(grey) << 8 | UInt32(grey)
        }
    }

    /**
     * The part itself: every layer of it, and how high the plastic reaches on each square
     * of the bed. Kept alongside its picture so the part can be drawn again as it stands
     * part-way through being printed, and from other sides.
     */
    public final class Shape: @unchecked Sendable {
        public let originX: Float, originY: Float, cell: Float
        public let cols: Int, rows: Int
        /** How tall the finished part is, in millimetres. */
        public let top: Float
        public let height: [Float]
        public let stack: Stack
        private var views = [Viewpoint?](repeating: nil, count: TURNS)
        private let lock = NSLock()

        public init(originX: Float, originY: Float, cell: Float, cols: Int, rows: Int, top: Float, height: [Float], stack: Stack) {
            self.originX = originX; self.originY = originY; self.cell = cell
            self.cols = cols; self.rows = rows; self.top = top
            self.height = height; self.stack = stack
        }

        /**
         * Where it is looked at from, [turn] quarter turns round from the side that shows
         * it best. Worked out from the shape, so a stored one needn't keep it.
         */
        public func view(_ turn: Int = 0) -> Viewpoint {
            let turn = ((turn % TURNS) + TURNS) % TURNS
            lock.lock()
            defer { lock.unlock() }
            if let seen = views[turn] { return seen }
            let made = Viewpoint.of(self, turn)
            views[turn] = made
            return made
        }
    }

    /** One slice's bits: a word for every 32 squares of each row of the bed. */
    public final class Plane: @unchecked Sendable {
        let bits: UnsafeMutablePointer<UInt32>
        public let count: Int

        init(count: Int) {
            self.count = count
            bits = .allocate(capacity: max(count, 1))
            bits.initialize(repeating: 0, count: max(count, 1))
        }

        init(words: [UInt32]) {
            count = words.count
            bits = .allocate(capacity: max(count, 1))
            bits.initialize(repeating: 0, count: max(count, 1))
            words.withUnsafeBufferPointer { src in
                if let base = src.baseAddress { bits.update(from: base, count: count) }
            }
        }

        deinit { bits.deallocate() }

        public subscript(i: Int) -> UInt32 {
            get { precondition(i >= 0 && i < count); return bits[i] }
            set { precondition(i >= 0 && i < count); bits[i] = newValue }
        }

        public var words: [UInt32] { Array(UnsafeBufferPointer(start: bits, count: count)) }
    }

    /**
     * The plastic slice by slice, from the bed up: for every [slice] millimetres of
     * height, a bit for each square of the bed saying whether there is any there.
     * [part] is the model itself; [extra] is what is printed to help it, supports and
     * brims. A slice with none of it has no array at all.
     */
    public final class Stack: @unchecked Sendable {
        public let cols: Int, rows: Int, slices: Int
        public let slice: Float
        /** How many 32-bit words one row of bits takes. 32 because the Echo Show the drawing was made for is 32-bit. */
        public let words: Int
        public internal(set) var part: [Plane?]
        public internal(set) var extra: [Plane?]

        public init(cols: Int, rows: Int, slices: Int, slice: Float) {
            self.cols = cols; self.rows = rows; self.slices = slices; self.slice = slice
            words = (cols + 31) / 32
            part = [Plane?](repeating: nil, count: slices)
            extra = [Plane?](repeating: nil, count: slices)
        }

        /** Which slice height [z] is in. A height right on the line between two belongs to the lower. */
        @inline(__always) public func of(_ z: Float) -> Int { kInt(z / slice - 0.001) }

        public func solid(_ gx: Int, _ gy: Int, _ s: Int) -> Bool {
            if gx < 0 || gy < 0 || gx >= cols || gy >= rows || s < 0 || s >= slices { return false }
            let at = gy * words + (gx >> 5)
            if let inPart = part[s], (inPart.bits[at] &>> UInt32(gx & 31)) & 1 != 0 { return true }
            if let inExtra = extra[s] { return (inExtra.bits[at] &>> UInt32(gx & 31)) & 1 != 0 }
            return false
        }

        /**
         * Neighbouring lines can leave hairline gaps where they didn't quite meet on the
         * grid. Grow every slice by a square, then shrink it back: gaps close, edges stay put.
         */
        func closeGaps() {
            let n = words * rows
            let grown = UnsafeMutablePointer<UInt32>.allocate(capacity: max(n, 1))
            let across = UnsafeMutablePointer<UInt32>.allocate(capacity: max(n, 1))
            grown.initialize(repeating: 0, count: max(n, 1))
            across.initialize(repeating: 0, count: max(n, 1))
            defer { grown.deallocate(); across.deallocate() }
            for case let bits? in part {
                spread(bits.bits, across, grown, wider: true)
                spread(grown, across, bits.bits, wider: false)
            }
        }

        /** Each square of [to] is the squares of [from] around it, any of them if [wider] and otherwise all. */
        private func spread(_ from: UnsafeMutablePointer<UInt32>, _ across: UnsafeMutablePointer<UInt32>,
                            _ to: UnsafeMutablePointer<UInt32>, wider: Bool) {
            for y in 0..<rows {
                let row = y * words
                for w in 0..<words {
                    let v = from[row + w]
                    let left = (v &<< 1) | (w > 0 ? from[row + w - 1] &>> 31 : 0)
                    let right = (v &>> 1) | (w < words - 1 ? from[row + w + 1] &<< 31 : 0)
                    across[row + w] = wider ? v | left | right : v & left & right
                }
            }
            for y in 0..<rows {
                let row = y * words
                let up = y > 0 ? row - words : row
                let down = y < rows - 1 ? row + words : row
                for w in 0..<words {
                    let a = across[up + w]
                    let b = across[row + w]
                    let c = across[down + w]
                    to[row + w] = wider ? a | b | c : a & b & c
                }
            }
        }
    }

    /**
     * Where a part is looked at from, and how it sits in the picture. A long part is
     * turned to lie across the picture whichever way the slicer put it on the bed,
     * and the framing is of the plastic itself: a bar laid corner to corner covers a
     * nearly square patch of bed, and framing that left it a sliver in an empty picture.
     *
     * [right] and [up] are the middle of what shows, measured along the picture's own
     * axes, and [wide] and [tall] how far it spans along them. [long], [broad] and
     * [high] are the model's own size, for saying how big it really is.
     *
     * (GcodePreview.View in the Kotlin; renamed so as not to be mistaken for SwiftUI's.)
     */
    public final class Viewpoint: @unchecked Sendable {
        public let azimuth: Double
        public let right: Float, up: Float, wide: Float, tall: Float
        public let long: Float, broad: Float, high: Float

        init(azimuth: Double, right: Float, up: Float, wide: Float, tall: Float, long: Float, broad: Float, high: Float) {
            self.azimuth = azimuth; self.right = right; self.up = up; self.wide = wide; self.tall = tall
            self.long = long; self.broad = broad; self.high = high
        }

        static func of(_ shape: Shape, _ turn: Int) -> Viewpoint {
            let stack = shape.stack
            // where the model itself stands, at any height: supports and brims don't say which way it lies
            var foot = [UInt32](repeating: 0, count: stack.words * stack.rows)
            for case let bits? in stack.part { for i in foot.indices { foot[i] |= bits.bits[i] } }

            let lying = Lying()
            forEachSquare(shape, foot) { lying.add($0, $1) }
            let lie = lying.direction()
            let isLong = lying.isLong()

            let base: Double
            if !isLong {
                base = AZIMUTH
            } else {
                // along its length with a little of its end showing, from whichever side is nearest the usual view
                var best: Double? = nil
                for it in [-lie - SKEW, -lie + SKEW] {
                    for a in [it - .pi, it, it + .pi] where abs(a) <= .pi / 2 {
                        if best == nil || abs(a - AZIMUTH) < abs(best! - AZIMUTH) { best = a }
                    }
                }
                base = best ?? AZIMUTH
            }
            let azimuth = Double(turn) * .pi / 2 + base

            // how big it is: along its own length if it has one, otherwise along the bed
            let size = Reach(isLong ? cos(lie) : 1.0, isLong ? sin(lie) : 0.0)
            forEachSquare(shape, foot) { size.add($0, $1) }

            // the picture's right and up, as in render
            let sinA = Float(sin(azimuth)); let cosA = Float(cos(azimuth))
            let sinE = Float(sin(ELEVATION)); let cosE = Float(cos(ELEVATION))
            let ux = sinA * sinE; let uy = cosA * sinE
            var minR = Float.greatestFiniteMagnitude; var maxR = -Float.greatestFiniteMagnitude
            var minU = Float.greatestFiniteMagnitude; var maxU = -Float.greatestFiniteMagnitude
            for gy in 0..<shape.rows {
                for gx in 0..<shape.cols {
                    let h = shape.height[gy * shape.cols + gx]
                    if h <= 0 { continue }
                    let x = shape.originX + (Float(gx) + 0.5) * shape.cell
                    let y = shape.originY + (Float(gy) + 0.5) * shape.cell
                    let r = x * cosA - y * sinA
                    let u = x * ux + y * uy
                    if r < minR { minR = r }
                    if r > maxR { maxR = r }
                    if u < minU { minU = u }
                    if u + h * cosE > maxU { maxU = u + h * cosE }
                }
            }
            let edge = shape.cell * 2
            return Viewpoint(
                azimuth: azimuth, right: (minR + maxR) / 2, up: (minU + maxU) / 2,
                wide: maxR - minR + edge, tall: maxU - minU + edge,
                // a file labelled as nothing but supports has no model to measure
                long: lying.squares > 0 ? size.along() + shape.cell : 0,
                broad: lying.squares > 0 ? size.across() + shape.cell : 0,
                high: shape.top
            )
        }

        /** Every square of bed set in [bits], as its middle in millimetres. */
        private static func forEachSquare(_ shape: Shape, _ bits: [UInt32], _ each: (Double, Double) -> Void) {
            let words = shape.stack.words
            for gy in 0..<shape.rows {
                for gx in 0..<shape.cols {
                    if (bits[gy * words + (gx >> 5)] &>> UInt32(gx & 31)) & 1 == 0 { continue }
                    each(Double(shape.originX + (Float(gx) + 0.5) * shape.cell), Double(shape.originY + (Float(gy) + 0.5) * shape.cell))
                }
            }
        }

        /** Which way a model's squares lie on the bed, from how they are spread about their middle. */
        private final class Lying {
            var squares = 0
            private var sumX = 0.0
            private var sumY = 0.0
            private var sumXX = 0.0
            private var sumYY = 0.0
            private var sumXY = 0.0

            func add(_ x: Double, _ y: Double) {
                squares += 1
                sumX += x
                sumY += y
                sumXX += x * x
                sumYY += y * y
                sumXY += x * y
            }

            private func spreadX() -> Double { squares > 0 ? sumXX / Double(squares) - sumX / Double(squares) * (sumX / Double(squares)) : 0 }
            private func spreadY() -> Double { squares > 0 ? sumYY / Double(squares) - sumY / Double(squares) * (sumY / Double(squares)) : 0 }
            private func spreadXY() -> Double { squares > 0 ? sumXY / Double(squares) - sumX / Double(squares) * (sumY / Double(squares)) : 0 }

            /** The angle of its length on the bed. */
            func direction() -> Double { 0.5 * atan2(2 * spreadXY(), spreadX() - spreadY()) }

            func isLong() -> Bool {
                let half = Foundation.hypot((spreadX() - spreadY()) / 2, spreadXY())
                let mean = (spreadX() + spreadY()) / 2
                return mean + half >= LONG_PART * LONG_PART * max(mean - half, 1e-6)
            }
        }

        /** How far a model's squares reach, along a direction and across it. */
        private final class Reach {
            private let alongX: Double
            private let alongY: Double
            private var minA = Double.greatestFiniteMagnitude
            private var maxA = -Double.greatestFiniteMagnitude
            private var minB = Double.greatestFiniteMagnitude
            private var maxB = -Double.greatestFiniteMagnitude

            init(_ alongX: Double, _ alongY: Double) { self.alongX = alongX; self.alongY = alongY }

            func add(_ x: Double, _ y: Double) {
                let a = x * alongX + y * alongY
                let b = y * alongX - x * alongY
                if a < minA { minA = a }
                if a > maxA { maxA = a }
                if b < minB { minB = b }
                if b > maxB { maxB = b }
            }

            func along() -> Float { Float(maxA - minA) }
            func across() -> Float { Float(maxB - minB) }
        }
    }

    /**
     * The order the part is laid down in: for every layer, a map of the bed saying
     * when in that layer the nozzle first reaches each spot. The map is of the same
     * stretch of bed as the [Shape]'s, in squares [ROUTE_CELLS] times the size.
     *
     * "When" is counted the way OctoPrint's Dashboard plugin counts how far through
     * a layer a print is, so that its figure can be turned straight back into a
     * place: movement commands sent since the layer began, out of all the layer
     * has. The layers are the plugin's too. It marks each one in the files it
     * stores, and a file it never marked has no layers here.
     */
    public final class Route: @unchecked Sendable {
        public let originX: Float, originY: Float, cell: Float
        public let cols: Int, rows: Int
        public let layers: [Layer]

        public init(originX: Float, originY: Float, cell: Float, cols: Int, rows: Int, layers: [Layer]) {
            self.originX = originX; self.originY = originY; self.cell = cell
            self.cols = cols; self.rows = rows; self.layers = layers
        }

        /**
         * One layer: its [number] as the plugin counts them, from 1, and the height
         * [z] it is laid at. Only the [width] x [height] squares around its plastic
         * are kept, the first of them at ([left], [top]), and those [packed] small.
         */
        public final class Layer: @unchecked Sendable {
            public let number: Int
            public let z: Float
            public let left: Int, top: Int, width: Int, height: Int
            public let packed: [UInt8]

            public init(number: Int, z: Float, left: Int, top: Int, width: Int, height: Int, packed: [UInt8]) {
                self.number = number; self.z = z
                self.left = left; self.top = top; self.width = width; self.height = height
                self.packed = packed
            }

            /**
             * Unpacked: a byte for each square, 0 where the layer lays nothing and
             * otherwise 1..255 for its start through to its end. After those, where
             * the nozzle is at each of the 255 moments, as (x, y) pairs of bytes that
             * run 0..255 across the same squares. Throws if what was packed isn't
             * packed data at all.
             */
            public func unpacked() throws -> [UInt8] {
                var out = [UInt8](repeating: 0, count: width * height + 2 * MOMENTS)
                try Zlib.inflate(packed, into: &out)
                // packed as how each square differs from the one before it
                let squares = width * height
                if squares > 1 {
                    for i in 1..<squares where i % width != 0 { out[i] = out[i] &+ out[i - 1] }
                }
                return out
            }
        }
    }

    /** What reading a file gives: the part, and the order it is laid down in. */
    public final class Reading: @unchecked Sendable {
        public let shape: Shape
        public let route: Route
        init(shape: Shape, route: Route) { self.shape = shape; self.route = route }
    }

    /// Opens the G-code from its start. Called twice.
    public typealias Opener = () throws -> InputStream

    /** Reads the part out of its G-code and draws it finished. Nil as for [read]. */
    public static func draw(
        _ open: Opener, width: Int = WIDTH, height: Int = HEIGHT, keepGoing: () -> Bool = { true }
    ) throws -> Picture? {
        guard let shape = try shape(open, keepGoing: keepGoing) else { return nil }
        return render(shape, width: width, height: height, keepGoing: keepGoing)
    }

    /**
     * Draws [shape] as it stands once the print has reached [upTo] millimetres (the whole
     * of it when nil), from [turn] quarter turns round. The framing is the finished part's
     * whatever the height, so pictures of different stages lie exactly over one another.
     */
    public static func render(
        _ shape: Shape, width: Int = WIDTH, height: Int = HEIGHT, upTo: Float? = nil, turn: Int = 0,
        keepGoing: () -> Bool = { true }
    ) -> Picture? {
        let to = upTo ?? shape.top
        return render(HeightMap(shape, upTo: to), shape.view(turn), width, height, keepGoing)
    }

    /**
     * Draws [shape] as it stands while [layer] of [route] is being laid: built up
     * to that layer and no further, with the layer itself marked and the order it
     * goes down in given by [Picture.reached] and [Picture.trail]. Throws if the
     * layer's order doesn't read back.
     */
    public static func render(
        _ shape: Shape, route: Route, layer: Route.Layer, width: Int = WIDTH, height: Int = HEIGHT, turn: Int = 0,
        keepGoing: () -> Bool = { true }
    ) throws -> Picture? {
        let order = try layer.unpacked()
        return render(HeightMap(shape, upTo: layer.z - KEPT_TO_MM, laying: true), shape.view(turn), width, height, keepGoing,
                      route, layer, order)
    }

    /** The part alone. Nil as for [read]. */
    public static func shape(_ open: Opener, keepGoing: () -> Bool = { true }) throws -> Shape? {
        try read(open, keepGoing: keepGoing)?.shape
    }

    /**
     * - Parameter open: opens the G-code from the start; called twice.
     * - Parameter keepGoing: checked regularly; returning false abandons the work.
     * - Returns: nil when the file has nothing printable in it, or the work was abandoned.
     */
    public static func read(_ open: Opener, keepGoing: () -> Bool = { true }) throws -> Reading? {
        let survey = Survey()
        if !(try walk(open(), keepGoing, survey)) { return nil }
        survey.close()
        let labelled = survey.labelled.count > 0
        let extent = labelled ? survey.labelled : survey.everything
        if extent.count == 0 || extent.maxZ <= 0 { return nil }

        let bed = HeightMap.over(extent, (labelled ? survey.labelledLevels : survey.levels).thickness())
        let tracer = Tracer(bed, survey.moves)
        let painter = Painter(bed: bed, tracer: tracer, labelled: labelled)
        if !(try walk(open(), keepGoing, painter)) { return nil }
        bed.closeGaps()
        if !keepGoing() { return nil }
        return Reading(
            shape: Shape(originX: bed.originX, originY: bed.originY, cell: bed.cell, cols: bed.cols, rows: bed.rows,
                         top: bed.top, height: bed.height, stack: bed.stack),
            route: tracer.route()
        )
    }

    /* ---------------- reading the G-code ---------------- */

    fileprivate class Sink {
        /**
         * One extruded line. [kind] is what the slicer labelled it as: [PART], [EXTRA] or
         * [LEFT_OUT]. It is the stretch [from]..[to] of the movement command it came from:
         * all of it, 0 to 1, unless that was an arc, which arrives in pieces.
         */
        func line(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ z: Float, _ width: Float, _ kind: Int, _ from: Float, _ to: Float) {}

        /** A movement command, whether or not it lays anything. Comes ahead of its own [line]s. */
        func move() {}

        /** The Dashboard plugin's mark that layer [number] starts here. */
        func layer(_ number: Int) {}
    }

    fileprivate final class Painter: Sink {
        let bed: HeightMap
        let tracer: Tracer
        let labelled: Bool

        init(bed: HeightMap, tracer: Tracer, labelled: Bool) {
            self.bed = bed; self.tracer = tracer; self.labelled = labelled
        }

        override func line(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ z: Float, _ width: Float, _ kind: Int, _ from: Float, _ to: Float) {
            if kind != LEFT_OUT || !labelled {
                bed.lay(x0, y0, x1, y1, z, width, extra: kind == EXTRA && labelled)
                tracer.lay(x0, y0, x1, y1, z, width, from, to)
            }
        }

        override func move() { tracer.move() }
        override func layer(_ number: Int) { tracer.begin(number) }
    }

    fileprivate final class Extent {
        var count = 0
        var minX = Float.greatestFiniteMagnitude
        var minY = Float.greatestFiniteMagnitude
        var maxX = -Float.greatestFiniteMagnitude
        var maxY = -Float.greatestFiniteMagnitude
        var maxZ: Float = 0

        func add(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ z: Float) {
            count += 1
            minX = min(minX, min(x0, x1)); maxX = max(maxX, max(x0, x1))
            minY = min(minY, min(y0, y1)); maxY = max(maxY, max(y0, y1))
            maxZ = max(maxZ, z)
        }
    }

    /**
     * How much plastic is laid at each height, to tell how thick a file's layers are.
     * Not from the moves between them: a seam that climbs into its layer makes far more
     * small rises than the file has layers, each with next to nothing laid at its height.
     */
    fileprivate final class Levels {
        private var laid = [Float](repeating: 0, count: MAX_LEVELS)

        func add(_ z: Float, _ length: Float) {
            let level = kRound(z * 100)
            if level >= 0 && level < laid.count { laid[level] += length }
        }

        /** The largest step between heights that much is laid at, so that no layer is left with a gap under it. */
        func thickness() -> Float {
            let most = laid.max() ?? 0
            var last = 0
            var largest = 0
            for level in laid.indices {
                if laid[level] <= 0 || laid[level] < most / 40 { continue }
                let step = level - last
                if step >= largest + 1 && step <= MAX_LAYER_STEP { largest = step }
                last = level
            }
            return Float(largest > 0 ? largest : MAX_LAYER_STEP) / 100
        }
    }

    fileprivate final class Survey: Sink {
        let labelled = Extent()
        let everything = Extent()
        let labelledLevels = Levels()
        let levels = Levels()
        /** How many movement commands each layer has, by its number: what the plugin divides by. */
        var moves = [Int: Int]()
        private var layerNumber = 0
        private var count = 0

        override func line(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ z: Float, _ width: Float, _ kind: Int, _ from: Float, _ to: Float) {
            let length = khypot(x1 - x0, y1 - y0)
            everything.add(x0, y0, x1, y1, z)
            levels.add(z, length)
            if kind != LEFT_OUT {
                labelled.add(x0, y0, x1, y1, z)
                labelledLevels.add(z, length)
            }
        }

        override func move() { count += 1 }

        override func layer(_ number: Int) {
            close()
            layerNumber = number
        }

        /** The last layer ends with the file. */
        func close() {
            if layerNumber > 0 { moves[layerNumber] = count }
            count = 0
        }
    }

    /**
     * Follows the moves in a file and reports each one that lays plastic.
     * Hand-rolled over raw bytes: these files run to a hundred megabytes, so no
     * line is ever turned into a String. Returns false if [keepGoing] said to stop.
     */
    fileprivate static func walk(_ input: InputStream, _ keepGoing: () -> Bool, _ sink: Sink) throws -> Bool {
        input.open()
        defer { input.close() }
        let bufferSize = 1 << 16
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        var filled = 0
        var at = 0
        let lineSize = 256
        let line = UnsafeMutablePointer<UInt8>.allocate(capacity: lineSize)
        line.initialize(repeating: 0, count: lineSize)
        defer { line.deallocate() }

        var x: Float = 0, y: Float = 0, z: Float = 0
        var e = 0.0
        var absolute = true
        var absoluteE = true
        var width = DEFAULT_WIDTH
        var kind = LEFT_OUT       // what the section the slicer has labelled us into is
        var lines: Int32 = 0

        while true {
            // next line into `line`
            var n = 0
            var ended = false
            while true {
                if at == filled {
                    filled = input.read(buffer, maxLength: bufferSize)
                    at = 0
                    if filled < 0 { throw input.streamError ?? ReadFailed() }
                    if filled <= 0 { ended = true; break }
                }
                let b = buffer[at]; at += 1
                if b == NEWLINE { break }
                if n < lineSize { line[n] = b; n += 1 }
            }
            if ended && n == 0 { return true }
            lines &+= 1
            if lines & 0x3FFF == 0 && !keepGoing() { return false }

            var i = 0
            while i < n && (line[i] == SPACE || line[i] == TAB) { i += 1 }
            if i >= n { if ended { return true } else { continue } }

            switch line[i] {
            case SEMICOLON:
                // ";TYPE:Outer wall" (Orca, Prusa, Cura), "; FEATURE: Outer wall" (Bambu), ";WIDTH:0.42"
                var j = i + 1
                while j < n && line[j] == SPACE { j += 1 }
                if startsWith(line, j, n, "TYPE:") { kind = kindOf(line, j + 5, n) }
                else if startsWith(line, j, n, "FEATURE:") { kind = kindOf(line, j + 8, n) }
                else if startsWith(line, j, n, "WIDTH:") {
                    let w = number(line, j + 6, n)
                    if w > 0.1 && w < 2 { width = w }
                }
            case G, G_LOWER:
                let code = integer(line, i + 1, n)
                switch code {
                case 0, 1, 2, 3:
                    // The plugin counts a line as a move when it starts "G" and one of
                    // these digits, then a gap: so not lower case, and not "G01".
                    let after = i + 2 < n ? line[i + 2] : NEWLINE
                    if line[i] == G && (after == SPACE || after == TAB || after == RETURN || after == NEWLINE) { sink.move() }
                    var nx = x, ny = y, nz = z
                    var de = 0.0
                    var hasE = false
                    var ci: Float = 0, cj: Float = 0
                    var j = i + 1
                    while j < n {
                        let c = line[j]
                        if c == SEMICOLON { break }
                        let letter = Int(c) | 0x20      // lower-case
                        if letter >= 0x61 && letter <= 0x7A && j + 1 < n {
                            let v = number(line, j + 1, n)
                            switch letter {
                            case 0x78: nx = absolute ? v : x + v                     // x
                            case 0x79: ny = absolute ? v : y + v                     // y
                            case 0x7A: nz = absolute ? v : z + v                     // z
                            case 0x65:                                               // e
                                hasE = true
                                de = absoluteE ? Double(v) - e : Double(v)
                                e = absoluteE ? Double(v) : e + Double(v)
                            case 0x69: ci = v                                        // i
                            case 0x6A: cj = v                                        // j
                            default: break
                            }
                        }
                        j += 1
                        while j < n && isNumberChar(line[j]) { j += 1 }
                    }
                    let laying = hasE && de > 0.0 && (nx != x || ny != y)
                    if laying {
                        if code >= 2 && (ci != 0 || cj != 0) {
                            arc(x, y, nx, ny, ci, cj, clockwise: code == 2, z: nz, width: width, kind: kind, sink: sink)
                        } else {
                            sink.line(x, y, nx, ny, nz, width, kind, 0, 1)
                        }
                    }
                    x = nx; y = ny; z = nz
                case 90: absolute = true
                case 91: absolute = false
                case 92:
                    // set position: almost always "G92 E0"
                    var j = i + 1
                    while j < n {
                        let c = line[j]
                        if c == SEMICOLON { break }
                        let letter = Int(c) | 0x20
                        if letter >= 0x61 && letter <= 0x7A && j + 1 < n {
                            let v = number(line, j + 1, n)
                            switch letter {
                            case 0x78: x = v
                            case 0x79: y = v
                            case 0x7A: z = v
                            case 0x65: e = Double(v)
                            default: break
                            }
                        }
                        j += 1
                        while j < n && isNumberChar(line[j]) { j += 1 }
                    }
                default: break
                }
            case M, M_LOWER:
                switch integer(line, i + 1, n) {
                case 82: absoluteE = true
                case 83: absoluteE = false
                case 117:
                    if startsWith(line, i, n, LAYER_MARK) {
                        let number = integer(line, i + LAYER_MARK.utf8.count, n)
                        if number >= 1 && number <= MAX_LAYERS { sink.layer(number) }
                    }
                default: break
                }
            default: break
            }
            if ended { return true }
        }
    }

    /** An arc move, as short straight pieces. [ci], [cj] are the centre's offset from the start. */
    private static func arc(
        _ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ ci: Float, _ cj: Float,
        clockwise: Bool, z: Float, width: Float, kind: Int, sink: Sink
    ) {
        let cx = x0 + ci
        let cy = y0 + cj
        let radius = khypot(ci, cj)
        let a0 = atan2(Double(y0 - cy), Double(x0 - cx))
        var sweep = atan2(Double(y1 - cy), Double(x1 - cx)) - a0
        if clockwise { if sweep >= 0 { sweep -= 2 * .pi } } else { if sweep <= 0 { sweep += 2 * .pi } }
        let pieces = clamp(kInt((abs(sweep) * Double(radius) / ARC_PIECE_MM).rounded(.up)), 1, 360)
        var px = x0
        var py = y0
        for k in 1...pieces {
            let a = a0 + sweep * Double(k) / Double(pieces)
            let qx = k == pieces ? x1 : cx + radius * Float(cos(a))
            let qy = k == pieces ? y1 : cy + radius * Float(sin(a))
            sink.line(px, py, qx, qy, z, width, kind, (Float(k) - 1) / Float(pieces), Float(k) / Float(pieces))
            px = qx; py = qy
        }
    }

    /** What a slicer's section label says is being laid. "Skirt/Brim" is one label, and comes out as a skirt. */
    private static func kindOf(_ line: UnsafeMutablePointer<UInt8>, _ from: Int, _ to: Int) -> Int {
        for word in NOT_DRAWN where contains(line, from, to, word) { return LEFT_OUT }
        for word in HELPERS where contains(line, from, to, word) { return EXTRA }
        return PART
    }

    private static let NOT_DRAWN: [[UInt8]] = ["skirt", "custom", "prime", "purge", "wipe", "tower"].map { Array($0.utf8) }
    private static let HELPERS: [[UInt8]] = ["support", "brim"].map { Array($0.utf8) }

    private static func startsWith(_ line: UnsafeMutablePointer<UInt8>, _ from: Int, _ to: Int, _ word: String) -> Bool {
        let bytes = word.utf8
        if from + bytes.count > to { return false }
        var k = from
        for b in bytes {
            if line[k] != b { return false }
            k += 1
        }
        return true
    }

    private static func contains(_ line: UnsafeMutablePointer<UInt8>, _ from: Int, _ to: Int, _ lower: [UInt8]) -> Bool {
        var s = from
        while s + lower.count <= to {
            var k = 0
            while k < lower.count && (line[s + k] | 0x20) == lower[k] { k += 1 }
            if k == lower.count { return true }
            s += 1
        }
        return false
    }

    @inline(__always) private static func isNumberChar(_ b: UInt8) -> Bool {
        (b >= ZERO && b <= NINE) || b == DOT || b == MINUS || b == PLUS
    }

    /// Kotlin's 32-bit Int, wrapping as it does on a run of digits too long for it.
    private static func integer(_ line: UnsafeMutablePointer<UInt8>, _ from: Int, _ to: Int) -> Int {
        var i = from
        var v: Int32 = 0
        if i >= to || !(line[i] >= ZERO && line[i] <= NINE) { return -1 }
        while i < to && line[i] >= ZERO && line[i] <= NINE { v = v &* 10 &+ Int32(line[i] - ZERO); i += 1 }
        return Int(v)
    }

    private static func number(_ line: UnsafeMutablePointer<UInt8>, _ from: Int, _ to: Int) -> Float {
        var i = from
        var negative = false
        if i < to && (line[i] == MINUS || line[i] == PLUS) { negative = line[i] == MINUS; i += 1 }
        var whole = 0.0
        while i < to && line[i] >= ZERO && line[i] <= NINE { whole = whole * 10 + Double(line[i] - ZERO); i += 1 }
        if i < to && line[i] == DOT {
            i += 1
            var scale = 0.1
            while i < to && line[i] >= ZERO && line[i] <= NINE { whole += Double(line[i] - ZERO) * scale; scale *= 0.1; i += 1 }
        }
        return Float(negative ? -whole : whole)
    }

    /* ---------------- the height map ---------------- */

    fileprivate final class HeightMap {
        let originX: Float
        let originY: Float
        let cell: Float
        let cols: Int
        let rows: Int
        /** The finished part's height. */
        let top: Float
        var height: [Float]
        let stack: Stack
        /** A slightly softened copy, for judging which way a surface faces. */
        var smooth: [Float] = []
        /** 0..1: how far a spot sits below its surroundings. Hollows and inside corners are darker. */
        var hollow: [Float] = []
        /** 0..1 on the bed: how far the part's footprint reaches, softly, for the shadow it stands in. */
        var ground: [Float] = []
        /** The highest slice printed so far. */
        var last: Int
        /** Printing has only got to [last], so what is in that slice is the layer being laid, not a surface of the finished part. */
        var cut = false
        private var thickness: Float = 0
        /** Room for the slices one line goes down through, for [lay]. */
        private var planeList: UnsafeMutablePointer<UnsafeMutablePointer<UInt32>>?

        init(originX: Float, originY: Float, cell: Float, cols: Int, rows: Int, top: Float, height: [Float], stack: Stack) {
            self.originX = originX; self.originY = originY; self.cell = cell
            self.cols = cols; self.rows = rows; self.top = top
            self.height = height; self.stack = stack
            last = stack.slices - 1
        }

        deinit { planeList?.deallocate() }

        /**
         * [shape] as it stands with only the first [upTo] millimetres of it printed.
         * Whether its top is a layer being [laying] is taken from how far short of
         * the finished part that is, unless the caller knows.
         */
        convenience init(_ shape: Shape, upTo: Float, laying: Bool? = nil) {
            self.init(originX: shape.originX, originY: shape.originY, cell: shape.cell, cols: shape.cols, rows: shape.rows,
                      top: shape.top, height: shape.height, stack: shape.stack)
            last = clamp(stack.of(upTo), 0, stack.slices - 1)
            cut = laying ?? (upTo < shape.top - UNFINISHED_MM)
            if last < stack.slices - 1 { heightsUpTo(last) }
            shade()
            let footprint = shape.height.map { $0 > 0 ? Float(1) : Float(0) }
            ground = blur(footprint, clamp(kInt(GROUND_BLUR_MM / cell), 2, 14))
        }

        /** An empty map of the bed under [extent], with room around it, for layers [thickness] mm thick. */
        static func over(_ extent: Extent, _ thickness: Float) -> HeightMap {
            let w = extent.maxX - extent.minX + 2 * MARGIN_MM
            let d = extent.maxY - extent.minY + 2 * MARGIN_MM
            let cell = max(max(w, d) / GRID, MIN_CELL_MM)
            let cols = max(kInt((w / cell).rounded(.up)), 2)
            let rows = max(kInt((d / cell).rounded(.up)), 2)
            let slice = max(thickness, extent.maxZ / MAX_SLICES)
            let slices = max(kInt((extent.maxZ / slice - 0.001).rounded(.up)), 1)
            let map = HeightMap(
                originX: extent.minX - MARGIN_MM, originY: extent.minY - MARGIN_MM, cell: cell, cols: cols, rows: rows,
                top: Float(slices) * slice,
                height: [Float](repeating: 0, count: cols * rows), stack: Stack(cols: cols, rows: rows, slices: slices, slice: slice)
            )
            map.thickness = thickness
            return map
        }

        /**
         * Presses one extruded line into the map, as wide as it was printed and down
         * through its layer's thickness, into the [Stack.extra] slices if it is [extra].
         */
        func lay(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ z: Float, _ width: Float, extra: Bool) {
            let highest = clamp(stack.of(z), 0, stack.slices - 1)
            let lowest = clamp(stack.of(max(z - thickness, 0) + stack.slice * 0.01), 0, highest)
            let words = stack.words
            for s in lowest...highest {
                if extra {
                    if stack.extra[s] == nil { stack.extra[s] = Plane(count: words * rows) }
                } else {
                    if stack.part[s] == nil { stack.part[s] = Plane(count: words * rows) }
                }
            }
            // the slices this line goes down through, gathered once rather than looked up at every square
            if planeList == nil {
                planeList = .allocate(capacity: stack.slices)
            }
            let planes = planeList!
            let planeCount = highest - lowest + 1
            for s in lowest...highest { planes[s - lowest] = (extra ? stack.extra[s] : stack.part[s])!.bits }
            let reachesTo = Float(highest + 1) * stack.slice
            let gx0 = (x0 - originX) / cell
            let gy0 = (y0 - originY) / cell
            let gx1 = (x1 - originX) / cell
            let gy1 = (y1 - originY) / cell
            let reach = max(width * 0.55 / cell, 0.5)
            let r = kInt(reach.rounded(.up))
            let reach2 = (reach + 0.35) * (reach + 0.35)
            let steps = max(kInt((khypot(gx1 - gx0, gy1 - gy0) / 0.7).rounded(.up)), 1)
            let cols = self.cols, rows = self.rows
            height.withUnsafeMutableBufferPointer { h in
                for s in 0...steps {
                    let t = Float(s) / Float(steps)
                    let cx = gx0 + (gx1 - gx0) * t
                    let cy = gy0 + (gy1 - gy0) * t
                    let ix = kInt(cx)
                    let iy = kInt(cy)
                    for yy in (iy - r)...(iy + r) {
                        if yy < 0 || yy >= rows { continue }
                        let dy = Float(yy) + 0.5 - cy
                        let row = yy * cols
                        for xx in (ix - r)...(ix + r) {
                            if xx < 0 || xx >= cols { continue }
                            let dx = Float(xx) + 0.5 - cx
                            if dx * dx + dy * dy > reach2 { continue }
                            if h[row + xx] < reachesTo { h[row + xx] = reachesTo }
                            let at = yy * words + (xx >> 5)
                            let bit: UInt32 = 1 &<< UInt32(xx & 31)
                            for k in 0..<planeCount { planes[k][at] |= bit }
                        }
                    }
                }
            }
        }

        /**
         * Neighbouring lines can leave hairline gaps where they didn't quite meet on the grid.
         * Grow every spot to its tallest neighbour, then shrink back: gaps close, edges stay put.
         */
        func closeGaps() {
            height = pick(pick(height, tallest: true), tallest: false)
            stack.closeGaps()
        }

        /**
         * How high the plastic reaches on each square with only the slices up to [last]
         * printed. Cutting the finished heights off would not do: a square whose plastic is
         * all still to come has nothing on it yet, however high that plastic will stand.
         */
        private func heightsUpTo(_ last: Int) {
            let words = stack.words
            var seen = [UInt32](repeating: 0, count: words * rows)
            for i in height.indices { height[i] = 0 }
            for s in stride(from: last, through: 0, by: -1) {
                let part = stack.part[s]
                let extra = stack.extra[s]
                if part == nil && extra == nil { continue }
                let top = Float(s + 1) * stack.slice
                for i in seen.indices {
                    var found = ((part?.bits[i] ?? 0) | (extra?.bits[i] ?? 0)) & ~seen[i]
                    if found == 0 { continue }
                    seen[i] |= found
                    let row = i / words * cols
                    let first = i % words * 32
                    while found != 0 {
                        let x = first + found.trailingZeroBitCount
                        if x < cols { height[row + x] = top }
                        found &= found &- 1
                    }
                }
            }
        }

        /** Works out what the shading needs, from the heights as they now are. */
        func shade() {
            smooth = blur(height, 2)
            let around = blur(height, HOLLOW_REACH_CELLS)
            hollow = [Float](repeating: 0, count: height.count)
            for i in hollow.indices { hollow[i] = clamp((around[i] - height[i]) / HOLLOW_DEPTH_MM, 0, 1) }
        }

        private func pick(_ src: [Float], tallest: Bool) -> [Float] {
            var out = [Float](repeating: 0, count: src.count)
            for y in 0..<rows {
                for x in 0..<cols {
                    var v = src[y * cols + x]
                    for yy in max(0, y - 1)...min(rows - 1, y + 1) {
                        for xx in max(0, x - 1)...min(cols - 1, x + 1) {
                            let s = src[yy * cols + xx]
                            v = tallest ? max(v, s) : min(v, s)
                        }
                    }
                    out[y * cols + x] = v
                }
            }
            return out
        }

        /** Box blur of the given radius, done as one pass along rows and one along columns. */
        private func blur(_ src: [Float], _ radius: Int) -> [Float] {
            var mid = [Float](repeating: 0, count: src.count)
            var out = [Float](repeating: 0, count: src.count)
            for y in 0..<rows {
                var sum: Float = 0
                var count = 0
                for x in -radius..<cols {
                    let add = x + radius
                    if add < cols { sum += src[y * cols + add]; count += 1 }
                    let drop = x - radius - 1
                    if drop >= 0 { sum -= src[y * cols + drop]; count -= 1 }
                    if x >= 0 { mid[y * cols + x] = sum / Float(count) }
                }
            }
            for x in 0..<cols {
                var sum: Float = 0
                var count = 0
                for y in -radius..<rows {
                    let add = y + radius
                    if add < rows { sum += mid[add * cols + x]; count += 1 }
                    let drop = y - radius - 1
                    if drop >= 0 { sum -= mid[drop * cols + x]; count -= 1 }
                    if y >= 0 { out[y * cols + x] = sum / Float(count) }
                }
            }
            return out
        }
    }

    /* ---------------- the order it is laid in ---------------- */

    /**
     * Notes the order each layer goes down in, as its lines are pressed into the
     * height map: see [Route]. [moves] is how many movement commands each layer
     * has in all, which the first pass through the file counted.
     */
    fileprivate final class Tracer {
        private let moves: [Int: Int]
        private let originX: Float
        private let originY: Float
        private let cell: Float
        private let cols: Int
        private let rows: Int
        private var layers = [Route.Layer]()
        private var kept = 0

        // the layer being read
        private var number = 0
        private var total = 0
        private var sent = 0
        /**
         * The layer's height is that of the highest plastic in it. Most of it is laid there,
         * but a slicer that joins its seams with a scarf starts each wall down at the layer
         * before and climbs.
         */
        private var highest: Float = 0
        /** 0 where nothing has been laid yet, otherwise 1..255: see [Route.Layer.unpacked]. */
        private var reached: [UInt8]
        private var left: Int
        private var top: Int
        private var right = -1
        private var bottom = -1
        private var trailX = [Float](repeating: 0, count: MOMENTS)
        private var trailY = [Float](repeating: 0, count: MOMENTS)
        private var trailKnown = [Bool](repeating: false, count: MOMENTS)

        init(_ bed: HeightMap, _ moves: [Int: Int]) {
            self.moves = moves
            originX = bed.originX
            originY = bed.originY
            cell = bed.cell * Float(ROUTE_CELLS)
            cols = (bed.cols + ROUTE_CELLS - 1) / ROUTE_CELLS
            rows = (bed.rows + ROUTE_CELLS - 1) / ROUTE_CELLS
            reached = [UInt8](repeating: 0, count: cols * rows)
            left = cols
            top = rows
        }

        func begin(_ layer: Int) {
            close()
            number = layer
            total = moves[layer] ?? 0
        }

        func move() { sent += 1 }

        func lay(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ z: Float, _ width: Float, _ from: Float, _ to: Float) {
            if total <= 0 { return }
            // how far through the layer this line starts and ends, as the plugin would put it
            let began = clamp((Float(sent - 1) + from) / Float(total), 0, 1)
            let ended = clamp((Float(sent - 1) + to) / Float(total), 0, 1)
            if z > highest { highest = z }

            let gx0 = (x0 - originX) / cell
            let gy0 = (y0 - originY) / cell
            let gx1 = (x1 - originX) / cell
            let gy1 = (y1 - originY) / cell
            let reach = max(width * 0.55 / cell, 0.5)
            let r = kInt(reach.rounded(.up))
            let reach2 = (reach + 0.35) * (reach + 0.35)
            let steps = max(kInt((khypot(gx1 - gx0, gy1 - gy0) / 0.7).rounded(.up)), 1)
            let cols = self.cols, rows = self.rows
            var left = self.left, right = self.right, top = self.top, bottom = self.bottom
            reached.withUnsafeMutableBufferPointer { reached in
                for s in 0...steps {
                    let t = Float(s) / Float(steps)
                    let cx = gx0 + (gx1 - gx0) * t
                    let cy = gy0 + (gy1 - gy0) * t
                    let moment = 1 + kRound((began + (ended - began) * t) * Float(MOMENTS - 1))
                    let ix = kInt(cx)
                    let iy = kInt(cy)
                    for yy in (iy - r)...(iy + r) {
                        if yy < 0 || yy >= rows { continue }
                        let dy = Float(yy) + 0.5 - cy
                        let row = yy * cols
                        for xx in (ix - r)...(ix + r) {
                            if xx < 0 || xx >= cols { continue }
                            let dx = Float(xx) + 0.5 - cx
                            if dx * dx + dy * dy > reach2 { continue }
                            let had = Int(reached[row + xx])
                            if had == 0 || moment < had { reached[row + xx] = UInt8(truncatingIfNeeded: moment) }
                            if xx < left { left = xx }
                            if xx > right { right = xx }
                            if yy < top { top = yy }
                            if yy > bottom { bottom = yy }
                        }
                    }
                }
            }
            self.left = left; self.right = right; self.top = top; self.bottom = bottom

            // where the nozzle is at each of the moments this line lasts through
            let first = kInt((began * Float(MOMENTS - 1)).rounded(.up))
            let last = min(kInt(ended * Float(MOMENTS - 1)), MOMENTS - 1)
            if first <= last {
                for m in first...last {
                    let t: Float = ended > began ? clamp((Float(m) / Float(MOMENTS - 1) - began) / (ended - began), 0, 1) : 1
                    trailX[m] = gx0 + (gx1 - gx0) * t
                    trailY[m] = gy0 + (gy1 - gy0) * t
                    trailKnown[m] = true
                }
            }
            // A moment that passed while nothing of the part was being laid finds the nozzle
            // at the start of the next line that is.
            if first > last && !trailKnown[last] {
                trailX[last] = gx0
                trailY[last] = gy0
                trailKnown[last] = true
            }
        }

        func route() -> Route {
            close()
            return Route(originX: originX, originY: originY, cell: cell, cols: cols, rows: rows, layers: layers)
        }

        /** Puts away the layer that was being read, if anything of the part was laid in it. */
        private func close() {
            if right >= 0 && kept < ROUTE_MAX_BYTES {
                // a square of room all round, for the spreading below to go into
                let l = max(left - 1, 0)
                let t = max(top - 1, 0)
                let w = min(right + 1, cols - 1) - l + 1
                let h = min(bottom + 1, rows - 1) - t + 1
                var out = [UInt8](repeating: 0, count: w * h + 2 * MOMENTS)
                for y in 0..<h { for x in 0..<w { out[y * w + x] = earliestAround(l + x, t + y) } }

                // The nozzle is somewhere at every moment: between lines of the part, it is
                // taken to be where it last laid one, and before the first, where that starts.
                var known = trailKnown.firstIndex(of: true) ?? -1
                for m in 0..<MOMENTS {
                    if trailKnown[m] { known = m }
                    if known < 0 { continue }
                    out[w * h + 2 * m] = UInt8(clamp(kRound((trailX[known] - Float(l)) / Float(w) * 255), 0, 255))
                    out[w * h + 2 * m + 1] = UInt8(clamp(kRound((trailY[known] - Float(t)) / Float(h) * 255), 0, 255))
                }

                // Neighbours are laid at nearly the same moment, so what packs small is how
                // each square differs from the one before it.
                for y in 0..<h {
                    var x = w - 1
                    while x >= 1 { out[y * w + x] = out[y * w + x] &- out[y * w + x - 1]; x -= 1 }
                }
                let packed = Zlib.deflate(out)
                kept += packed.count
                layers.append(Route.Layer(number: number, z: highest, left: l, top: t, width: w, height: h, packed: packed))
            }
            if right >= 0 {
                for y in top...bottom { for x in left...right { reached[y * cols + x] = 0 } }
            }
            left = cols; top = rows; right = -1; bottom = -1
            for m in 0..<MOMENTS { trailKnown[m] = false }
            highest = 0
            sent = 0
            total = 0
        }

        /**
         * When a square is reached, or failing that the earliest of the squares around
         * it: the height map closes hairline gaps between lines, and what is plastic
         * there has to count as laid here.
         */
        private func earliestAround(_ x: Int, _ y: Int) -> UInt8 {
            let own = reached[y * cols + x]
            if own != 0 { return own }
            var best = 0
            for yy in max(0, y - 1)...min(rows - 1, y + 1) {
                for xx in max(0, x - 1)...min(cols - 1, x + 1) {
                    let v = Int(reached[yy * cols + xx])
                    if v != 0 && (best == 0 || v < best) { best = v }
                }
            }
            return UInt8(truncatingIfNeeded: best)
        }
    }

    /* ---------------- looking at it ---------------- */

    /** Draws the picture, a pixel at a time. See [Eye] for how each one is worked out. */
    fileprivate static func render(
        _ bed: HeightMap, _ view: Viewpoint, _ width: Int, _ height: Int, _ keepGoing: () -> Bool,
        _ route: Route? = nil, _ layer: Route.Layer? = nil, _ order: [UInt8]? = nil
    ) -> Picture? {
        let eye = Eye(bed, view, width, height, route, layer, order)
        let count = width * height
        var light = [UInt8](repeating: 0, count: count)
        var alpha = [UInt8](repeating: 0, count: count)
        var fresh = [UInt8](repeating: 0, count: count)
        var shadow = [UInt8](repeating: 0, count: count)
        var reached: [UInt8]? = eye.order != nil ? [UInt8](repeating: 0, count: count) : nil
        let all = SUPERSAMPLE * SUPERSAMPLE
        for py in 0..<height {
            if py & 15 == 0 && !keepGoing() { return nil }
            for px in 0..<width {
                eye.look(px, py)
                let i = py * width + px
                if eye.hits > 0 {
                    light[i] = UInt8(clamp(kInt(eye.sum / Float(eye.hits) * 255), 0, 255))
                    alpha[i] = UInt8(truncatingIfNeeded: eye.hits * 255 / all)
                    fresh[i] = UInt8(truncatingIfNeeded: eye.laying * 255 / eye.hits)
                    reached?[i] = UInt8(truncatingIfNeeded: eye.earliest)
                }
                shadow[i] = UInt8(clamp(kInt(eye.dark / Float(all) * GROUND_SHADE * 255), 0, 255))
            }
        }
        return Picture(width: width, height: height, light: light, alpha: alpha, fresh: fresh, shadow: shadow,
                       reached: reached, trail: eye.trail())
    }

    /**
     * Looks at the part from in front and above, from the side [view] says, with no
     * perspective. For every pixel a line of sight is followed down through the space
     * above the bed, square by square, until it meets plastic. Going by squares, each
     * one is known to be passed through from where to where, so no layer is ever stepped
     * over, and whether the line came in from above or through the side says which face
     * of the plastic it met.
     *
     * Given the [layer] of a [route] that is being laid, the picture also says
     * when the nozzle gets to each bit of it.
     */
    fileprivate final class Eye {
        private let bed: HeightMap
        private let view: Viewpoint
        private let route: Route?
        private let layer: Route.Layer?

        private let sinA: Float
        private let cosA: Float
        private let sinE: Float
        private let cosE: Float

        // the direction of sight, and the picture's own right and up, in bed coordinates
        private let dx: Float
        private let dy: Float
        private let dz: Float
        private let rx: Float
        private let ry: Float
        private let ux: Float
        private let uy: Float
        private let uz: Float

        // light from over the viewer's left shoulder
        private let lx: Float
        private let ly: Float
        private let lz: Float

        // the way to the lamp along the bed, and how much it rises for each millimetre of that
        private let lampX: Float
        private let lampY: Float
        private let lampRise: Float
        private let shadowStep: Float
        // how far up the light climbs from one step to the next, in slices, halved: a floor it crosses between steps still counts
        private let shadowSlices: Int

        private let mapWidth: Float
        private let mapDepth: Float
        private let samplesWide: Int
        private let samplesTall: Int
        private let mmPerSample: Float
        // the point the middle of the picture looks through
        private let cx: Float
        private let cy: Float
        private let cz: Float

        private let stack: Stack
        private let partSlices: [Plane?]
        private let extraSlices: [Plane?]
        private let words: Int
        private let cols: Int
        private let rows: Int
        private let cell: Float
        private let originX: Float
        private let originY: Float
        private let tops: [Float]
        private let smooth: [Float]
        private let hollow: [Float]
        private let ground: [Float]
        private let thick: Float
        private let last: Int
        private let cut: Bool
        private let bedTop: Float
        private let reachCells: Int
        private let coverSlices: Int
        // nothing stands higher than where printing has got to
        private let ceiling: Float

        /** When each square of the layer being laid is reached, and where the nozzle is at each moment. */
        let order: [UInt8]?

        // what one pixel comes to, from [look]
        var hits = 0
        var laying = 0
        var earliest = 0
        var sum: Float = 0
        var dark: Float = 0

        // what one line of sight met, from [follow]
        private var found = false
        private var meetsAt: Float = 0
        private var side = false
        private var sliceHit = 0
        private var isExtra = false
        private var foundX = 0
        private var foundY = 0
        // where it first went under plastic, if it has, and how closely, 0..1
        private var covered = false
        private var underFrom: Float = -1
        private var closeness: Float = 0

        // which way what it met faces, from [face]
        private var nx: Float = 0
        private var ny: Float = 0
        private var nz: Float = 0

        init(_ bed: HeightMap, _ view: Viewpoint, _ width: Int, _ height: Int, _ route: Route?, _ layer: Route.Layer?, _ order: [UInt8]?) {
            self.bed = bed
            self.view = view
            self.route = route
            self.layer = layer
            sinA = Float(sin(view.azimuth))
            cosA = Float(cos(view.azimuth))
            sinE = Float(sin(ELEVATION))
            cosE = Float(cos(ELEVATION))
            dx = sinA * cosE
            dy = cosA * cosE
            dz = -sinE
            rx = cosA
            ry = -sinA
            ux = sinA * sinE
            uy = cosA * sinE
            uz = cosE

            let lightScale = 1 / (LIGHT_LEFT * LIGHT_LEFT + LIGHT_UP * LIGHT_UP + LIGHT_NEAR * LIGHT_NEAR).squareRoot()
            lx = (-LIGHT_LEFT * rx + LIGHT_UP * ux - LIGHT_NEAR * dx) * lightScale
            ly = (-LIGHT_LEFT * ry + LIGHT_UP * uy - LIGHT_NEAR * dy) * lightScale
            lz = (LIGHT_UP * uz - LIGHT_NEAR * dz) * lightScale

            let lampway = max(khypot(lx, ly), 0.01)
            lampX = lx / lampway
            lampY = ly / lampway
            lampRise = lz / lampway
            shadowStep = max(SHADOW_REACH_MM / Float(SHADOW_STEPS), bed.cell * 1.5)
            shadowSlices = kInt(shadowStep * lampRise / bed.stack.slice / 2)

            mapWidth = Float(bed.cols) * bed.cell
            mapDepth = Float(bed.rows) * bed.cell
            samplesWide = width * SUPERSAMPLE
            samplesTall = height * SUPERSAMPLE
            mmPerSample = max(view.wide / Float(width), view.tall / Float(height)) / (1 - 2 * PICTURE_MARGIN) / Float(SUPERSAMPLE)
            cx = view.right * rx + view.up * ux
            cy = view.right * ry + view.up * uy
            cz = view.up * uz

            stack = bed.stack
            partSlices = stack.part
            extraSlices = stack.extra
            words = stack.words
            cols = bed.cols
            rows = bed.rows
            cell = bed.cell
            originX = bed.originX
            originY = bed.originY
            tops = bed.height
            smooth = bed.smooth
            hollow = bed.hollow
            ground = bed.ground
            thick = stack.slice
            last = bed.last
            cut = bed.cut
            bedTop = bed.top
            reachCells = max(kInt(WALL_REACH_MM / bed.cell), 2)
            coverSlices = kInt(COVERED_MM / stack.slice)
            ceiling = Float(bed.last + 1) * stack.slice
            self.order = route != nil ? order : nil
        }

        @inline(__always) private func at(_ map: [Float], _ gx: Int, _ gy: Int) -> Float {
            (gx < 0 || gy < 0 || gx >= cols || gy >= rows) ? 0 : map[gy * cols + gx]
        }

        @inline(__always) private func solid(_ gx: Int, _ gy: Int, _ s: Int) -> Bool {
            if gx < 0 || gy < 0 || gx >= cols || gy >= rows || s < 0 || s >= partSlices.count { return false }
            let at = gy * words + (gx >> 5)
            let shift = UInt32(gx & 31)
            if let p = partSlices[s], (p.bits[at] &>> shift) & 1 != 0 { return true }
            if let x = extraSlices[s] { return (x.bits[at] &>> shift) & 1 != 0 }
            return false
        }

        /** Works out the pixel at ([px], [py]), from several lines of sight through it. */
        func look(_ px: Int, _ py: Int) {
            hits = 0
            laying = 0
            earliest = 0
            sum = 0
            dark = 0
            let sub = SUPERSAMPLE
            for sy in 0..<sub {
                for sx in 0..<sub {
                    let s = (Float(px * sub + sx) + 0.5 - Float(samplesWide) / 2) * mmPerSample
                    let t = (Float(samplesTall) / 2 - (Float(py * sub + sy) + 0.5)) * mmPerSample
                    let qx = cx + s * rx + t * ux
                    let qy = cy + s * ry + t * uy
                    let qz = cz + t * uz
                    follow(qx, qy, qz)
                    if found { see(qx, qy, qz) } else { dark += groundAt(qx, qy, qz) }
                }
            }
        }

        /** Follows the line of sight through (qx, qy, qz) down to the first plastic it meets. */
        private func follow(_ qx: Float, _ qy: Float, _ qz: Float) {
            found = false
            covered = false
            // the stretch of this line of sight that lies between the top of the part and the bed
            var from = (qz - ceiling) / sinE
            var to = qz / sinE
            // ...and over the map at all
            if dx != 0 {
                let a = (originX - qx) / dx
                let b = (originX + mapWidth - qx) / dx
                from = max(from, min(a, b)); to = min(to, max(a, b))
            }
            if dy != 0 {
                let a = (originY - qy) / dy
                let b = (originY + mapDepth - qy) / dy
                from = max(from, min(a, b)); to = min(to, max(a, b))
            }
            if from >= to { return }

            var tau = from
            let startX = (qx + tau * dx - originX) / cell
            let startY = (qy + tau * dy - originY) / cell
            var gx = clamp(kInt(startX), 0, cols - 1)
            var gy = clamp(kInt(startY), 0, rows - 1)
            let stepX = dx > 0 ? 1 : -1
            let stepY = dy > 0 ? 1 : -1
            let eachX = dx != 0 ? cell / abs(dx) : Float.greatestFiniteMagnitude
            let eachY = dy != 0 ? cell / abs(dy) : Float.greatestFiniteMagnitude
            var nextX = dx != 0 ? from + (Float(dx > 0 ? gx + 1 : gx) - startX) * cell / dx : Float.greatestFiniteMagnitude
            var nextY = dy != 0 ? from + (Float(dy > 0 ? gy + 1 : gy) - startY) * cell / dy : Float.greatestFiniteMagnitude

            while tau < to {
                if inSquare(gx, gy, tau, min(min(nextX, nextY), to), tau == from, qz) { return }
                if nextX < nextY {
                    tau = nextX
                    nextX += eachX
                    gx += stepX
                    if gx < 0 || gx >= cols { return }
                } else {
                    tau = nextY
                    nextY += eachY
                    gy += stepY
                    if gy < 0 || gy >= rows { return }
                }
            }
        }

        /** Looks for plastic where the line of sight crosses one square, from [tau] to [leaves]. */
        private func inSquare(_ gx: Int, _ gy: Int, _ tau: Float, _ leaves: Float, _ first: Bool, _ qz: Float) -> Bool {
            let top = tops[gy * cols + gx]
            if top <= 0 { return false }
            let zIn = qz + tau * dz
            let zOut = qz + leaves * dz
            if zOut >= top { return false }
            // the slice it came into this square at; the very first square is always from above
            let cameIn = first ? Int(Int32.max) : stack.of(zIn)
            var slice = min(stack.of(min(zIn, top)), last)
            let lowest = max(stack.of(zOut), 0)
            let at = gy * words + (gx >> 5)
            let shift = UInt32(gx & 31)
            while slice >= lowest {
                let inPart: Bool
                if let part = partSlices[slice] { inPart = (part.bits[at] &>> shift) & 1 != 0 } else { inPart = false }
                var extra = false
                if !inPart, let e = extraSlices[slice] { extra = (e.bits[at] &>> shift) & 1 != 0 }
                if inPart || extra {
                    found = true
                    side = slice >= cameIn
                    meetsAt = side ? tau : (qz - Float(slice + 1) * thick) / sinE
                    sliceHit = slice
                    isExtra = extra
                    foundX = gx
                    foundY = gy
                    return true
                }
                slice -= 1
            }
            // Through this square and out, below what stands in it. Only plastic close
            // overhead shuts a surface in: a wall with a gear 15 mm above it is in the open.
            if !covered { lookOverhead(gx, gy, min(zIn, top), tau) }
            return false
        }

        private func lookOverhead(_ gx: Int, _ gy: Int, _ z: Float, _ tau: Float) {
            let above = stack.of(z) + 1
            let highest = min(above + coverSlices, last)
            var over = above
            while over <= highest {
                if solid(gx, gy, over) {
                    covered = true
                    underFrom = tau
                    closeness = 1 - Float(over - above) / Float(coverSlices + 1)
                    return
                }
                over += 1
            }
        }

        /** The bed, which is dark where the part stands between it and the lamp. */
        private func groundAt(_ qx: Float, _ qy: Float, _ qz: Float) -> Float {
            let atBed = qz / sinE
            let gx = kInt(((qx + atBed * dx + lampX * GROUND_OFFSET_MM - originX) / cell).rounded(.down))
            let gy = kInt(((qy + atBed * dy + lampY * GROUND_OFFSET_MM - originY) / cell).rounded(.down))
            return at(ground, gx, gy)
        }

        /** Adds what the line of sight through (qx, qy, qz) met to the pixel. */
        private func see(_ qx: Float, _ qy: Float, _ qz: Float) {
            let hx = qx + meetsAt * dx
            let hy = qy + meetsAt * dy
            let hz = qz + meetsAt * dz
            sum += brightness(hx, hy, hz)
            hits += 1
            if cut && !side && sliceHit == last {
                laying += 1
                if order != nil { noteWhen(hx, hy) }
            }
        }

        /** Keeps the earliest moment any of the pixel's lines of sight says the nozzle gets there. */
        private func noteWhen(_ hx: Float, _ hy: Float) {
            guard let route = route, let layer = layer, let order = order else { return }
            let col = kInt((hx - route.originX) / route.cell) - layer.left
            let row = kInt((hy - route.originY) / route.cell) - layer.top
            if col < 0 || row < 0 || col >= layer.width || row >= layer.height { return }
            let moment = Int(order[row * layer.width + col])
            if moment != 0 && (earliest == 0 || moment < earliest) { earliest = moment }
        }

        private func brightness(_ hx: Float, _ hy: Float, _ hz: Float) -> Float {
            let surface = at(tops, foundX, foundY)
            let below = surface - hz > WALL_MM
            face(surface, below)
            let facing = max(0, nx * lx + ny * ly + nz * lz)
            var bright = AMBIENT + (1 - AMBIENT) * facing * (lit(hx, hy, hz) ? 1 : SHADOWED)
            if !below { bright *= 1 - HOLLOW_SHADE * at(hollow, foundX, foundY) }
            // walls get a touch darker towards the bed, which grounds the part
            else if side { bright *= 0.86 + 0.14 * clamp(hz / bedTop, 0, 1) }
            // and what is only seen from underneath something darker still
            if covered { bright *= 1 - COVERED_SHADE * closeness * clamp((meetsAt - underFrom) / COVERED_MM, 0, 1) }
            if isExtra { bright *= EXTRA_LIGHT }
            return bright
        }

        /** Works out which way the surface faces here, from how the height changes around it. */
        private func face(_ surface: Float, _ below: Bool) {
            let gx = foundX
            let gy = foundY
            // A sheer wall is simply a very steep slope, so one rule covers flat tops,
            // ramps and walls alike, and a sloped surface made of layer steps shades smoothly.
            nx = -(at(smooth, gx + 1, gy) - at(smooth, gx - 1, gy)) / (2 * cell)
            ny = -(at(smooth, gx, gy + 1) - at(smooth, gx, gy - 1)) / (2 * cell)
            nz = 1
            if below && !side {
                nx = 0
                ny = 0
            } else if below && wallStandsAlone(surface) {
                faceOfSlice()
            }
            let len = (nx * nx + ny * ny + nz * nz).squareRoot()
            nx /= len
            ny /= len
            nz /= len
        }

        /**
         * That is only so of a wall that goes all the way up to the top of its square. The
         * height of one with air above it belongs to whatever overhangs it, which says nothing
         * of which way the wall faces.
         */
        private func wallStandsAlone(_ surface: Float) -> Bool {
            if abs(nx) + abs(ny) < 0.5 { return true }
            let high = stack.of(surface)
            return !solid(foundX, foundY, (3 * sliceHit + high) / 4) ||
                !solid(foundX, foundY, (sliceHit + high) / 2) ||
                !solid(foundX, foundY, (sliceHit + 3 * high) / 4)
        }

        /** Which way a wall faces going by the plastic of its own slice: away from it. */
        private func faceOfSlice() {
            var awayX: Float = 0
            var awayY: Float = 0
            for k in 0..<AROUND {
                let ox = AROUND_X[k]
                let oy = AROUND_Y[k]
                if !solid(foundX + ox * reachCells, foundY + oy * reachCells, sliceHit) { awayX += Float(ox); awayY += Float(oy) }
                if !solid(foundX + ox * reachCells / 2, foundY + oy * reachCells / 2, sliceHit) { awayX += Float(ox); awayY += Float(oy) }
            }
            if awayX != 0 || awayY != 0 {
                nx = awayX
                ny = awayY
                nz = 0.05 * khypot(awayX, awayY)
            } else {
                nx = -dx * 4
                ny = -dy * 4
            }
        }

        /** Whether nothing stands between this spot and the lamp. */
        private func lit(_ hx: Float, _ hy: Float, _ hz: Float) -> Bool {
            var k = 1
            while k <= SHADOW_STEPS {
                let along = Float(k) * shadowStep
                let ox = kInt((hx + lampX * along - originX) / cell)
                let oy = kInt((hy + lampY * along - originY) / cell)
                if ox < 0 || oy < 0 || ox >= cols || oy >= rows { return true }
                // the light goes under what is too high up to be in its way
                let lightAt = hz + 0.05 + along * lampRise
                if tops[oy * cols + ox] > lightAt {
                    let at = stack.of(lightAt)
                    if solid(ox, oy, at) || solid(ox, oy, at - shadowSlices) { return false }
                }
                k += 1
            }
            return true
        }

        /**
         * The nozzle's place at each moment is a point on the layer's own surface. Sight
         * lines are parallel, so where it shows is that point measured along the picture's
         * right and up.
         */
        func trail() -> [Float]? {
            guard let steps = order, let route = route, let layer = layer else { return nil }
            let wide = mmPerSample * Float(samplesWide)
            let tall = mmPerSample * Float(samplesTall)
            let squares = layer.width * layer.height
            var out = [Float](repeating: 0, count: 2 * MOMENTS)
            for i in 0..<(2 * MOMENTS) {
                let m = i / 2
                let bx = route.originX + (Float(layer.left) + Float(steps[squares + 2 * m]) / 255 * Float(layer.width)) * route.cell
                let by = route.originY + (Float(layer.top) + Float(steps[squares + 2 * m + 1]) / 255 * Float(layer.height)) * route.cell
                out[i] = i % 2 == 0
                    ? 0.5 + (bx * rx + by * ry - view.right) / wide
                    : 0.5 - (bx * ux + by * uy + ceiling * uz - view.up) / tall
            }
            return out
        }
    }

    /** The eight directions around a square, as steps along the bed. */
    private static let AROUND = 8
    private static let AROUND_X = [1, 1, 0, -1, -1, -1, 0, 1]
    private static let AROUND_Y = [0, 1, 1, 1, 0, -1, -1, -1]
    /** How far around a square its slice is looked at, for which way a wall faces. */
    private static let WALL_REACH_MM: Float = 1.2

    fileprivate static let NEWLINE: UInt8 = 0x0A
    fileprivate static let RETURN: UInt8 = 0x0D
    fileprivate static let SPACE: UInt8 = 0x20
    fileprivate static let TAB: UInt8 = 0x09
    fileprivate static let SEMICOLON: UInt8 = 0x3B
    fileprivate static let DOT: UInt8 = 0x2E
    fileprivate static let MINUS: UInt8 = 0x2D
    fileprivate static let PLUS: UInt8 = 0x2B
    fileprivate static let ZERO: UInt8 = 0x30
    fileprivate static let NINE: UInt8 = 0x39
    fileprivate static let G: UInt8 = 0x47
    fileprivate static let G_LOWER: UInt8 = 0x67
    fileprivate static let M: UInt8 = 0x4D
    fileprivate static let M_LOWER: UInt8 = 0x6D

    /** Line width assumed until the file says otherwise: a 0.4 mm nozzle's usual. */
    private static let DEFAULT_WIDTH: Float = 0.45
    private static let ARC_PIECE_MM = 0.6
    /** The size a picture is drawn at. Its shape is that of the panel it is shown in. */
    public static let WIDTH = 400
    public static let HEIGHT = 300
    /** How many sides a part can be looked at from, a quarter turn apart. */
    public static let TURNS = 4
    /** What a section of a file is: the model itself, something printed to help it, or neither. */
    fileprivate static let PART = 0
    fileprivate static let EXTRA = 1
    fileprivate static let LEFT_OUT = 2
    /** Squares along the longer side of the part. Finer than the picture needs, so edges stay clean. */
    private static let GRID: Float = 448
    private static let MIN_CELL_MM: Float = 0.06
    /** Room around the part: enough for the shadow it stands in not to be cut off. */
    private static let MARGIN_MM: Float = 4
    /** At most this many slices, whatever the height: a tall part's are thicker than its layers, and nobody can tell. */
    private static let MAX_SLICES: Float = 240
    /** Heights a file is told apart at, in hundredths of a millimetre: 65 cm of them. */
    fileprivate static let MAX_LEVELS = 1 << 16
    /** Thicker than any layer, in the same hundredths. A step this big is a gap in the part, not a layer. */
    fileprivate static let MAX_LAYER_STEP = 100
    private static let HOLLOW_REACH_CELLS = 7
    private static let HOLLOW_DEPTH_MM: Float = 4
    private static let HOLLOW_SHADE: Float = 0.4
    /** How much dimmer what is printed to help the part is than the part. */
    private static let EXTRA_LIGHT: Float = 0.68
    /** How far a line of sight goes under plastic before what it finds there is as dark as it gets. */
    private static let COVERED_MM: Float = 3
    private static let COVERED_SHADE: Float = 0.72
    /** What is left of the light where something stands between a surface and the lamp. */
    private static let SHADOWED: Float = 0.3
    /** How far along the way to the lamp a surface looks for anything in the way, and in how many steps. */
    private static let SHADOW_REACH_MM: Float = 24
    private static let SHADOW_STEPS = 12
    private static let GROUND_BLUR_MM: Float = 3.5
    private static let GROUND_SHADE: Float = 0.5
    /** How far from the lamp the shadow on the bed lies from under the part. */
    private static let GROUND_OFFSET_MM: Float = 2
    /** A part at least this many times as long as it is broad is turned to lie across the picture. */
    private static let LONG_PART = 1.6
    /** How far from lying flat across the picture such a part is turned, so that its end shows too. */
    private static let SKEW = radians(20.0)
    /** A hit this far below the top of its square is the side of something, not its top. */
    private static let WALL_MM: Float = 0.5
    /** Closer to the top than this and the part is as good as finished: no layer is shown as being laid. */
    private static let UNFINISHED_MM: Float = 0.05

    /** What the Dashboard plugin writes into a file it stores, after each of the slicer's own layer changes. */
    private static let LAYER_MARK = "M117 DASHBOARD_LAYER_INDICATOR "
    /**
     * More layers than any part has. A count is kept for every layer a file marks, and a
     * file is not to be trusted to mark only as many as it has.
     */
    private static let MAX_LAYERS = 100_000
    /** How many of the height map's squares, each way, share one square of a [Route]. */
    public static let ROUTE_CELLS = 3
    /** How finely a layer's time is divided. The plugin reports in hundredths, so this is plenty. */
    public static let MOMENTS = 255
    /** A file whose route has grown past this is left with what it has: the rest of it fills the plain way. */
    private static let ROUTE_MAX_BYTES = 12 * 1024 * 1024
    /**
     * Heights are stored to a hundredth of a millimetre, so a layer's own plastic can
     * come back a hair lower than the layer is. Cutting the part off this far under a
     * layer's height keeps all of that layer in view as the one being laid.
     */
    private static let KEPT_TO_MM: Float = 0.006

    private static let AZIMUTH = radians(32.0)
    private static let ELEVATION = radians(36.0)
    private static let LIGHT_LEFT: Float = 0.55
    private static let LIGHT_UP: Float = 0.75
    private static let LIGHT_NEAR: Float = 0.5
    private static let AMBIENT: Float = 0.36
    private static let SUPERSAMPLE = 2
    private static let PICTURE_MARGIN: Float = 0.03
}
