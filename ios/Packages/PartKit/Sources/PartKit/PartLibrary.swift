import CoreGraphics
import CryptoKit
import Foundation

/**
 * Drawings this app makes for itself from a file's G-code: the finished part, for files
 * whose G-code carries no slicer thumbnail, and for a file being printed, the part as it
 * stands. They live in [directory] only: the file is read from OctoPrint, drawn here, and
 * the picture kept. Nothing is ever written back to the server.
 *
 * Ported from the Android app's octo/Previews.kt, with the drawing queue and the "draw it
 * again" bookkeeping that the Android app kept in its view model.
 */
public final class PartLibrary: @unchecked Sendable {

    public enum Outcome: Sendable, Equatable {
        case drawn
        /** The file has nothing in it to draw (or is too big to try); remembered so it isn't fetched again. */
        case nothing
        /** Couldn't be fetched just now. */
        case failed
        /** Stopped part-way because the caller said so. */
        case abandoned
    }

    /// Thrown by a fetch for a file larger than [maxBytes]: it is then never tried again.
    public struct TooBig: Error { public init() {} }

    /// Larger than any sensible print; guards the phone's storage against something odd.
    public static let maxBytes: Int64 = 300 * 1024 * 1024
    /// About what OctoPrint's own G-code viewer pulls; slow enough not to trouble a print.
    public static let gentleBytesPerSecond: Int64 = 1_200_000

    /// Posted on the default NotificationCenter when a file queued by [want] is drawn or found to have nothing to draw.
    /// `object` is the library; `userInfo["key"]` is the file's [key].
    public static let didDraw = Notification.Name("PartLibrary.didDraw")

    public typealias Fetch = @Sendable (_ file: PartFile, _ to: URL, _ gentle: Bool) async throws -> Void

    private let dir: URL
    private let scratch: URL
    private let fetch: Fetch
    /// The size pictures are drawn at. 400 x 300 is what the Android app draws.
    public let pictureWidth: Int
    public let pictureHeight: Int

    private let lock = NSLock()
    private let queue: DrawQueue

    /**
     * - Parameter directory: where drawings are kept, such as a folder in Application Support.
     * - Parameter fetch: downloads the file's G-code to `to`, a file URL. `gentle` is true while
     *   a print runs: hold the transfer to about [gentleBytesPerSecond] then. Throw [TooBig] for
     *   a file over [maxBytes], CancellationError when stopped, anything else when it failed.
     */
    public init(
        directory: URL,
        pictureWidth: Int = GcodePreview.WIDTH, pictureHeight: Int = GcodePreview.HEIGHT,
        fetch: @escaping Fetch
    ) {
        dir = directory
        scratch = FileManager.default.temporaryDirectory.appendingPathComponent("PartKit-\(UUID().uuidString)", isDirectory: true)
        self.fetch = fetch
        self.pictureWidth = pictureWidth
        self.pictureHeight = pictureHeight
        queue = DrawQueue()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    /** Changes when the file is replaced by a new upload, so a stale picture is never shown for it. */
    public func key(_ file: PartFile) -> String {
        let digest = Insecure.SHA1.hash(data: Data("\(file.origin):\(file.path):\(file.date):\(file.size)".utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func picture(_ file: PartFile) -> URL { dir.appendingPathComponent(key(file) + ".png") }
    private func blank(_ file: PartFile) -> URL { dir.appendingPathComponent(key(file) + ".none") }
    private func shapeFile(_ file: PartFile) -> URL { dir.appendingPathComponent(key(file) + ".shape") }
    private func routeFile(_ file: PartFile) -> URL { dir.appendingPathComponent(key(file) + ".route") }

    private func isFile(_ url: URL) -> Bool {
        var folder: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &folder) && !folder.boolValue
    }

    private func touch(_ url: URL) { FileManager.default.createFile(atPath: url.path, contents: Data()) }

    /**
     * Whether everything for showing the part being printed was kept with its picture: its
     * shape as it is drawn now, and the order it is laid in. Drawings from before have less.
     */
    public func hasShape(_ file: PartFile) -> Bool {
        let from = shapeFile(file)
        guard isFile(from), isFile(routeFile(file)), let data = try? Data(contentsOf: from),
              let bytes = try? Zlib.gunzip([UInt8](data)), bytes.count >= 4 else { return false }
        return Reader(bytes).int() == PartLibrary.SHAPE_FORMAT
    }

    /** Drawn, or known to have nothing to draw: either way there is no reason to fetch it again. */
    public func settled(_ file: PartFile) -> Bool { isFile(picture(file)) || isFile(blank(file)) }

    /** Drawn and kept, as opposed to known to have nothing in it to draw. */
    public func isDrawn(_ file: PartFile) -> Bool { isFile(picture(file)) }

    /**
     * The stored picture of the finished part in the given color, or nil if it isn't drawn
     * (yet). What is stored is only how bright each bit of surface is; the color comes from
     * the filament setting.
     */
    public func preview(_ file: PartFile, tint: CGColor) async -> CGImage? {
        let rgb = RGB(tint)
        let cacheKey = "\(key(file)):\(rgb.key)"
        if let hit = lock.withLock({ previews[cacheKey] }) { return hit }
        guard let stored = Images.loadPNG(picture(file)) else { return nil }
        guard let image = Images.tinted(stored.shown, width: stored.width, height: stored.height, tint: rgb) else { return nil }
        lock.withLock {
            if previews.count > 24 { previews.removeAll() }
            previews[cacheKey] = image
        }
        return image
    }

    private var previews = [String: CGImage]()

    /* ---------------- the part as it stands ---------------- */

    /**
     * The part as it stands while [layer] is being printed, in the given color, with that
     * layer in [hot], from [turn] quarter turns round. Failing a layer, or any record of the
     * order this file's layers are laid in, it is the part with [fraction] of its height
     * printed. Nil when there is no shape to draw it from.
     */
    public func grown(_ file: PartFile, fraction: Float, layer: Int?, tint: CGColor, hot: CGColor, turn: Int = 0) async -> Growth? {
        let tintRGB = RGB(tint)
        let hotRGB = RGB(hot)
        guard let shape = shapeOf(file) else { return nil }
        guard let whole = wholeOf(file, shape, tintRGB, turn) else { return nil }
        let dated = !isFile(routeFile(file))
        let route = layer.flatMap { layingOf(file, $0) }
        let width = pictureWidth, height = pictureHeight
        // An order that was kept but doesn't read back whole is no reason to show nothing:
        // the part is then drawn by how much of its height is done, like one with no order.
        let laid: GcodePreview.Picture?? = route.map { r in
            try? GcodePreview.render(shape, route: r, layer: r.layers[0], width: width, height: height, turn: turn)
        }
        // one layer more than is finished: the one on top is the one being printed
        guard let p = (laid ?? nil) ?? GcodePreview.render(
            shape, width: width, height: height,
            upTo: min(shape.top * clamp(fraction, 0, 1) + PartLibrary.LAYER_MM, shape.top), turn: turn
        ) else { return nil }

        let made = PartLibrary.growth(p, whole: whole, tint: tintRGB, hot: hotRGB, layer: route?.layers.first?.number,
                                      dated: dated, view: shape.view(turn))
        if let made { lock.withLock { lastGrowth = (growthKey(file, tintRGB, hotRGB, turn), made) } }
        return made
    }

    /** The finished part, the part as it stands, and its top layer, from one drawing of it. */
    static func growth(
        _ p: GcodePreview.Picture, whole: CGImage, tint: RGB, hot: RGB, layer: Int?, dated: Bool, view: GcodePreview.Viewpoint
    ) -> Growth? {
        let reached = p.reached
        let w = p.width
        let h = p.height
        var part = [UInt32](repeating: 0, count: w * h)
        var laying = [UInt32](repeating: 0, count: w * h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        let hotBits = UInt32(hot.key)
        for i in 0..<(w * h) {
            let shown = p.shown(i)
            if shown == 0 { continue }
            let light = Int(shown & 0xFF)
            part[i] = (shown & 0xFF00_0000) | UInt32(tint.r * light / 255) << 16 | UInt32(tint.g * light / 255) << 8 | UInt32(tint.b * light / 255)
            let cover = Int(p.alpha[i])
            let fresh = Int(p.fresh[i])
            // with the order known, a spot the nozzle never visits is not part of the layer
            if cover == 0 || fresh == 0 || (reached != nil && reached![i] == 0) { continue }
            laying[i] = UInt32(truncatingIfNeeded: cover * fresh / 255) << 24 | hotBits
            let x = i % w
            let y = i / w
            if x < left { left = x }
            if x > right { right = x }
            if y < top { top = y }
            if y > bottom { bottom = y }
        }
        var order: CGImage? = nil
        if let reached, right >= 0 {
            var moments = [Int](repeating: 0, count: w * h)
            for i in moments.indices where laying[i] != 0 { moments[i] = Int(reached[i]) }
            // The screen blends neighbouring pixels, and blending with nothing at the layer's
            // edge would nibble at it: so each time is carried a few pixels past the edge.
            for _ in 0..<3 { moments = spread(moments, w, h) }
            let steps = Growth.FIRST - Growth.LAST
            var bytes = [UInt8](repeating: 0, count: w * h * 4)
            for i in moments.indices {
                let m = moments[i]
                if m == 0 { continue }
                let a = UInt8(clamp(Growth.FIRST - (m - 1) * steps / (GcodePreview.MOMENTS - 1), 0, 255))
                // white, premultiplied
                bytes[4 * i] = a; bytes[4 * i + 1] = a; bytes[4 * i + 2] = a; bytes[4 * i + 3] = a
            }
            order = Images.rgba(bytes, width: w, height: h)
        }
        guard let partImage = Images.image(argb: part, width: w, height: h),
              let layingImage = Images.image(argb: laying, width: w, height: h) else { return nil }
        return Growth(
            whole: whole,
            part: partImage,
            laying: layingImage,
            box: right < 0 ? nil : CGRect(
                x: CGFloat(left) / CGFloat(w), y: CGFloat(top) / CGFloat(h),
                width: CGFloat(right + 1 - left) / CGFloat(w), height: CGFloat(bottom + 1 - top) / CGFloat(h)
            ),
            layer: reached != nil ? layer : nil,
            order: order,
            trail: order != nil ? p.trail : nil,
            dated: dated,
            view: view,
            pictureWidth: w, pictureHeight: h
        )
    }

    /**
     * The last [grown] made of [file] in these colors, if it was the last made of anything.
     * A screen coming back puts this up at once while it works out a newer one, instead of
     * starting from no drawing at all.
     */
    public func lastGrown(_ file: PartFile, tint: CGColor, hot: CGColor, turn: Int = 0) -> Growth? {
        let key = growthKey(file, RGB(tint), RGB(hot), turn)
        return lock.withLock { lastGrowth?.0 == key ? lastGrowth?.1 : nil }
    }

    private var lastGrowth: (String, Growth)?

    private func growthKey(_ file: PartFile, _ tint: RGB, _ hot: RGB, _ turn: Int) -> String {
        "\(key(file)):\(tint.key):\(hot.key):\(turn)"
    }

    /** Gives every pixel that has no time the earliest of those next to it. */
    private static func spread(_ src: [Int], _ w: Int, _ h: Int) -> [Int] {
        var out = src
        for y in 0..<h {
            for x in 0..<w {
                if src[y * w + x] != 0 { continue }
                var best = 0
                for yy in max(0, y - 1)...min(h - 1, y + 1) {
                    for xx in max(0, x - 1)...min(w - 1, x + 1) {
                        let v = src[yy * w + xx]
                        if v != 0 && (best == 0 || v < best) { best = v }
                    }
                }
                out[y * w + x] = best
            }
        }
        return out
    }

    /** The last shape read, since the same job asks for it again at every layer. */
    private var held: (String, GcodePreview.Shape)?
    /** And the last finished picture, in the color it was wanted in, for the same reason. */
    private var heldWhole: (String, Int, CGImage)?

    private func wholeOf(_ file: PartFile, _ shape: GcodePreview.Shape, _ tint: RGB, _ turn: Int) -> CGImage? {
        let key = "\(key(file)):\(turn)"
        if let hit = lock.withLock({ heldWhole }), hit.0 == key, hit.1 == tint.key { return hit.2 }
        // only the side first shown is stored; the others are looked at from time to time, and quick to draw
        let whole: CGImage?
        if turn == 0 {
            whole = Images.loadPNG(picture(file)).flatMap { Images.tinted($0.shown, width: $0.width, height: $0.height, tint: tint) }
        } else {
            whole = GcodePreview.render(shape, width: pictureWidth, height: pictureHeight, turn: turn).flatMap { p in
                Images.tinted((0..<(p.width * p.height)).map { p.shown($0) }, width: p.width, height: p.height, tint: tint)
            }
        }
        if let whole { lock.withLock { heldWhole = (key, tint.key, whole) } }
        return whole
    }

    /// The part as stored for [file], or nil when it hasn't been drawn with its shape.
    public func shapeOf(_ file: PartFile) -> GcodePreview.Shape? {
        let key = key(file)
        if let hit = lock.withLock({ held }), hit.0 == key { return hit.1 }
        let from = shapeFile(file)
        guard isFile(from), let data = try? Data(contentsOf: from), let bytes = try? Zlib.gunzip([UInt8](data)) else { return nil }
        let src = Reader(bytes)
        guard src.int() == PartLibrary.SHAPE_FORMAT else { return nil }
        let originX = src.float()
        let originY = src.float()
        let cell = src.float()
        let cols = src.int()
        let rows = src.int()
        let top = src.float()
        let slices = src.int()
        let slice = src.float()
        guard !src.failed, (1...PartLibrary.MAX_SIDE).contains(cols), (1...PartLibrary.MAX_SIDE).contains(rows),
              (1...PartLibrary.MAX_SLICES).contains(slices) else { return nil }
        var height = [Float](repeating: 0, count: cols * rows)
        for i in height.indices { height[i] = Float(src.unsignedShort()) / 100 }
        let stack = GcodePreview.Stack(cols: cols, rows: rows, slices: slices, slice: slice)
        let count = stack.words * rows
        func bits() -> GcodePreview.Plane? {
            if !src.boolean() { return nil }
            var words = [UInt32](repeating: 0, count: count)
            for i in 0..<count { words[i] = UInt32(bitPattern: Int32(truncatingIfNeeded: src.int())) }
            return GcodePreview.Plane(words: words)
        }
        for s in 0..<slices {
            stack.part[s] = bits()
            stack.extra[s] = bits()
        }
        if src.failed { return nil }
        let shape = GcodePreview.Shape(originX: originX, originY: originY, cell: cell, cols: cols, rows: rows, top: top, height: height, stack: stack)
        lock.withLock { held = (key, shape) }
        return shape
    }

    private func saveShape(_ shape: GcodePreview.Shape, to: URL) throws {
        let out = Writer()
        out.int(PartLibrary.SHAPE_FORMAT)
        out.float(shape.originX)
        out.float(shape.originY)
        out.float(shape.cell)
        out.int(shape.cols)
        out.int(shape.rows)
        out.float(shape.top)
        let stack = shape.stack
        out.int(stack.slices)
        out.float(stack.slice)
        // hundredths of a millimetre: finer than any layer, and half the size of the floats
        for h in shape.height { out.short(clamp(kRound(h * 100), 0, 0xFFFF)) }
        func write(_ bits: GcodePreview.Plane?) {
            out.boolean(bits != nil)
            guard let bits else { return }
            for i in 0..<bits.count { out.int(Int(Int32(bitPattern: bits.bits[i]))) }
        }
        for s in 0..<stack.slices {
            write(stack.part[s])
            write(stack.extra[s])
        }
        let part = to.appendingPathExtension("part")
        try Data(Zlib.deflate(out.bytes, gzip: true)).write(to: part)
        try Images.moveOver(part, to: to)
    }

    /**
     * The layers [file] has an order for: their numbers, and how high each is, from the
     * bottom up. Nil when no order was kept for it. This is only the list at the front of
     * what is kept, so it costs little to read.
     */
    public func layersOf(_ file: PartFile) -> (numbers: [Int], heights: [Float])? {
        guard let src = try? FileHandle(forReadingFrom: routeFile(file)) else { return nil }
        defer { try? src.close() }
        guard let head = try? src.read(upToCount: PartLibrary.ROUTE_HEADER_BYTES + 4), head.count == PartLibrary.ROUTE_HEADER_BYTES + 4 else { return nil }
        let header = Reader([UInt8](head))
        guard header.int() == PartLibrary.ROUTE_FORMAT else { return nil }
        for _ in 0..<5 { _ = header.int() }
        let count = header.int()
        guard (1...PartLibrary.MAX_LAYERS).contains(count),
              let raw = try? src.read(upToCount: count * PartLibrary.ROUTE_ENTRY_BYTES),
              raw.count == count * PartLibrary.ROUTE_ENTRY_BYTES else { return nil }
        let index = Reader([UInt8](raw))
        var numbers = [Int](repeating: 0, count: count)
        var heights = [Float](repeating: 0, count: count)
        for i in 0..<count {
            index.at = i * PartLibrary.ROUTE_ENTRY_BYTES
            numbers[i] = index.int()
            heights[i] = index.float()
        }
        return (numbers, heights)
    }

    /** Layer [number]'s map from a spot on the bed to when it is laid. Nil as for [layingOf]. */
    public func mapOf(_ file: PartFile, number: Int) -> LayerMap? {
        guard let route = layingOf(file, number) else { return nil }
        return try? LayerMap(route, route.layers[0])
    }

    /**
     * Layer [number] of the order [file] is laid in, with the map it is drawn on. Nil
     * when that layer lays nothing of the part, or no order was kept for the file.
     *
     * Only the one layer is read. A tall part has a great many, and each of them is wanted once.
     */
    func layingOf(_ file: PartFile, _ number: Int) -> GcodePreview.Route? {
        guard let src = try? FileHandle(forReadingFrom: routeFile(file)) else { return nil }
        defer { try? src.close() }
        guard let head = try? src.read(upToCount: PartLibrary.ROUTE_HEADER_BYTES + 4), head.count == PartLibrary.ROUTE_HEADER_BYTES + 4 else { return nil }
        let header = Reader([UInt8](head))
        guard header.int() == PartLibrary.ROUTE_FORMAT else { return nil }
        let originX = header.float()
        let originY = header.float()
        let cell = header.float()
        let cols = header.int()
        let rows = header.int()
        let count = header.int()
        guard (0...PartLibrary.MAX_LAYERS).contains(count), (1...PartLibrary.MAX_SIDE).contains(cols),
              (1...PartLibrary.MAX_SIDE).contains(rows) else { return nil }
        // every layer's particulars come first, then every layer's squares in the same order
        guard let raw = try? src.read(upToCount: count * PartLibrary.ROUTE_ENTRY_BYTES),
              raw.count == count * PartLibrary.ROUTE_ENTRY_BYTES else { return nil }
        let index = Reader([UInt8](raw))
        var at = UInt64(PartLibrary.ROUTE_HEADER_BYTES + 4 + count * PartLibrary.ROUTE_ENTRY_BYTES)
        for _ in 0..<count {
            let layer = index.int()
            let z = index.float()
            let left = index.short()
            let top = index.short()
            let width = index.short()
            let height = index.short()
            let length = index.int()
            guard (0...PartLibrary.MAX_LAYER_BYTES).contains(length) else { return nil }
            if layer == number {
                if left < 0 || top < 0 || width < 1 || height < 1 || left + width > cols || top + height > rows { return nil }
                guard (try? src.seek(toOffset: at)) != nil, let packed = try? src.read(upToCount: length), packed.count == length else { return nil }
                let one = GcodePreview.Route.Layer(number: layer, z: z, left: left, top: top, width: width, height: height, packed: [UInt8](packed))
                return GcodePreview.Route(originX: originX, originY: originY, cell: cell, cols: cols, rows: rows, layers: [one])
            }
            at += UInt64(length)
        }
        return nil
    }

    private func saveRoute(_ route: GcodePreview.Route, to: URL) throws {
        let out = Writer()
        out.int(PartLibrary.ROUTE_FORMAT)
        out.float(route.originX)
        out.float(route.originY)
        out.float(route.cell)
        out.int(route.cols)
        out.int(route.rows)
        out.int(route.layers.count)
        for layer in route.layers {
            out.int(layer.number)
            out.float(layer.z)
            out.short(layer.left)
            out.short(layer.top)
            out.short(layer.width)
            out.short(layer.height)
            out.int(layer.packed.count)
        }
        for layer in route.layers { out.bytes.append(contentsOf: layer.packed) }
        let part = to.appendingPathExtension("part")
        try Data(out.bytes).write(to: part)
        try Images.moveOver(part, to: to)
    }

    /* ---------------- drawing ---------------- */

    /**
     * Fetches the file's G-code and draws it, now, whatever else is going on. [want] is
     * the way to ask for one, which queues it.
     *
     * - Parameter gentle: passed on to the fetch: hold the transfer to a trickle, because the Pi
     *   serving it is busy running a print.
     * - Parameter keepGoing: asked throughout: when it says no, the work is dropped.
     * - Parameter again: draw it even though there is a picture already, if that picture came
     *   without the part's shape or the order it is laid in.
     */
    public func draw(_ file: PartFile, gentle: Bool, again: Bool = false, keepGoing: @escaping @Sendable () -> Bool = { true }) async -> Outcome {
        if settled(file) && !(again && isFile(picture(file)) && !hasShape(file)) {
            // (The Android app said DRAWN here either way; saying which lets a panel give up on
            // a file with nothing in it at once.)
            return isFile(picture(file)) ? .drawn : .nothing
        }
        let alive: @Sendable () -> Bool = { !Task.isCancelled && keepGoing() }
        let source = scratch.appendingPathComponent("preview-source-\(UUID().uuidString).gcode")
        defer { try? FileManager.default.removeItem(at: source) }
        if file.size > PartLibrary.maxBytes {
            touch(blank(file))
            return .nothing
        }
        do {
            try await fetch(file, source, gentle)
        } catch is TooBig {
            touch(blank(file))
            return .nothing
        } catch is CancellationError {
            return .abandoned
        } catch {
            return alive() ? .failed : .abandoned
        }
        if !alive() { return .abandoned }
        let got = (try? FileManager.default.attributesOfItem(atPath: source.path)[.size] as? NSNumber)?.int64Value ?? -1
        if got < 0 { return .failed }
        if got > PartLibrary.maxBytes {
            touch(blank(file))
            return .nothing
        }

        let width = pictureWidth, height = pictureHeight
        let read: GcodePreview.Reading?
        do {
            read = try GcodePreview.read({ InputStream(url: source) ?? InputStream(data: Data()) }, keepGoing: alive)
        } catch {
            return .failed
        }
        let shape = read?.shape
        let drawn = shape.flatMap { GcodePreview.render($0, width: width, height: height, keepGoing: alive) }
        guard let read, let shape, let drawn else {
            if !alive() { return .abandoned }
            // a picture that is already there stays; there was just no shape to add to it
            if !isFile(picture(file)) { touch(blank(file)) }
            return .nothing
        }
        do {
            try saveShape(shape, to: shapeFile(file))
            try saveRoute(read.route, to: routeFile(file))
            try Images.savePNG(drawn, to: picture(file))
        } catch {
            return .failed
        }
        lock.withLock {
            if held?.0 == key(file) { held = nil }
            heldWhole = nil
            previews = previews.filter { !$0.key.hasPrefix(key(file)) }
        }
        return .drawn
    }

    /**
     * Asks for [file] to be drawn: queued, one at a time, the newest request first. Returns
     * when it is drawn, or known to have nothing to draw, or failed. A file asked for again
     * while it waits or is being drawn is drawn once, and every asker hears the outcome.
     *
     * Call it for the job's file as soon as a job starts, with [gentle] true, whether or not
     * the part is on screen, so the drawing is ready by the time anyone looks. A file that
     * failed is not tried again until [forgetFailures], unless it is one being printed
     * ([wantShape] asks for those).
     *
     * - Parameter keepGoing: asked while the file is fetched and read; when it says no the work is
     *   dropped (outcome [Outcome.abandoned]): say no for a library file once a print starts.
     */
    @discardableResult
    public func want(_ file: PartFile, gentle: Bool, keepGoing: @escaping @Sendable () -> Bool = { true }) async -> Outcome {
        await queue.ask(file: file, key: key(file), gentle: gentle, keepGoing: keepGoing, library: self)
    }

    /**
     * [file] is being printed, or something on screen wants to show it part-printed, and its
     * drawing can't show that, or not well: it has the picture but not the shape behind it, or
     * not the order the shape is laid in, or it was never drawn here. It is drawn once more.
     * This can be called as often as liked: it is asked for at most once a minute, and given
     * up on after a few tries.
     *
     * - Returns: whether such a drawing may still come, as opposed to having been given up on.
     */
    @discardableResult
    public func wantShape(_ file: PartFile, gentle: Bool) -> Bool {
        let key = key(file)
        let now = ProcessInfo.processInfo.systemUptime
        let ask: Bool
        let answer: Bool
        lock.lock()
        let redraw = shapesWanted[key] ?? {
            let r = Redraw(askedAt: now - PartLibrary.REDRAW_ASK_AGAIN_S)
            shapesWanted[key] = r
            return r
        }()
        // a file known to have nothing in it to draw needn't be tried at all
        if isFile(blank(file)) && !isFile(picture(file)) && !redraw.drawing { redraw.tries = PartLibrary.REDRAW_TRIES }
        if redraw.tries >= PartLibrary.REDRAW_TRIES {
            ask = false
            answer = redraw.drawing
        } else {
            ask = now - redraw.askedAt >= PartLibrary.REDRAW_ASK_AGAIN_S
            if ask { redraw.askedAt = now }
            answer = true
        }
        lock.unlock()
        if ask { Task { await self.want(file, gentle: gentle) } }
        return answer
    }

    /// A fetch that failed while the server was away deserves another go: call when it is back.
    public func forgetFailures() {
        lock.withLock {
            failed.removeAll()
            shapesWanted.removeAll()
        }
    }

    /// Whether the last try at fetching [file] failed.
    public func fetchFailed(_ file: PartFile) -> Bool { lock.withLock { failed.contains(key(file)) } }

    /** What to say in place of a picture that isn't there. */
    public func note(_ file: PartFile) -> String {
        if isFile(blank(file)) && !isFile(picture(file)) { return "There is nothing in this file to draw" }
        if fetchFailed(file) { return "This file couldn't be fetched to draw a preview" }
        return "Drawing a preview from the G-code"
    }

    private final class Redraw {
        var askedAt: TimeInterval
        var tries = 0
        var drawing = false
        init(askedAt: TimeInterval) { self.askedAt = askedAt }
    }

    private var shapesWanted = [String: Redraw]()
    private var failed = Set<String>()

    /// One file off the queue: the Android app's worker loop, for one file.
    fileprivate func work(_ file: PartFile, key: String, gentle: Bool, keepGoing: @escaping @Sendable () -> Bool) async -> Outcome {
        // Made again only if it is still worth it: not once it has its shape, nor when the file
        // is known to have nothing in it, nor once the tries are used up. (The Android app tried
        // again in those last two cases too, which could go round and round.)
        let nothingInIt = isFile(blank(file)) && !isFile(picture(file))
        let redraw: Redraw? = lock.withLock { shapesWanted[key] }.flatMap { r in
            hasShape(file) || nothingInIt || lock.withLock({ r.tries }) >= PartLibrary.REDRAW_TRIES ? nil : r
        }
        let again = redraw != nil
        // a fetch that failed while going through the library is no reason to leave a job undrawn
        if lock.withLock({ failed.contains(key) }) && !again { return .failed }
        if !again && settled(file) { return isFile(picture(file)) ? .drawn : .nothing }
        if let redraw {
            lock.withLock {
                redraw.tries += 1
                redraw.drawing = true
            }
        }
        let outcome = await draw(file, gentle: gentle, again: again, keepGoing: keepGoing)
        lock.withLock {
            redraw?.drawing = false
            switch outcome {
            case .drawn: break
            // a drawing being made again still has its picture, and will be asked for again
            case .failed: if !again { failed.insert(key) }
            // nothing there to grow, and trying again won't change that
            case .nothing: redraw?.tries = PartLibrary.REDRAW_TRIES
            // dropped because the connection or the job went away, which is no fault of the file's
            case .abandoned: redraw?.tries -= 1
            }
        }
        // A picture waiting on this looks again. Not after a failure: one waiting on a file that
        // can't be fetched would only ask for it again at once.
        if outcome == .drawn || outcome == .nothing {
            NotificationCenter.default.post(name: PartLibrary.didDraw, object: self, userInfo: ["key": key])
        }
        return outcome
    }

    /* ---------------- fetching ---------------- */

    /**
     * Downloads [request] to [to] with [session], for a [Fetch] to use if it likes. While
     * [gentle] says yes the transfer is held to [gentleBytesPerSecond], because the Pi serving
     * it is busy running a print. Throws [TooBig] past [maxBytes].
     */
    public static func download(_ request: URLRequest, session: URLSession = .shared, to: URL,
                                gentle: @escaping @Sendable () -> Bool) async throws {
        let (bytes, response) = try await session.bytes(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        if response.expectedContentLength > maxBytes { throw TooBig() }
        FileManager.default.createFile(atPath: to.path, contents: nil)
        let out = try FileHandle(forWritingTo: to)
        defer { try? out.close() }
        var chunk = [UInt8]()
        chunk.reserveCapacity(32 * 1024)
        var total: Int64 = 0
        var paced: Int64 = 0
        var pacedSince = DispatchTime.now().uptimeNanoseconds
        func flush() async throws {
            if chunk.isEmpty { return }
            try out.write(contentsOf: chunk)
            let n = Int64(chunk.count)
            chunk.removeAll(keepingCapacity: true)
            total += n
            if total > maxBytes { throw TooBig() }
            try Task.checkCancellation()
            if gentle() {
                // sleep off whatever arrived faster than the trickle allows
                paced += n
                let earliest = pacedSince + UInt64(paced) * 1_000_000_000 / UInt64(gentleBytesPerSecond)
                let now = DispatchTime.now().uptimeNanoseconds
                if earliest > now { try await Task.sleep(nanoseconds: min(earliest - now, 400_000_000)) }
            } else {
                paced = 0
                pacedSince = DispatchTime.now().uptimeNanoseconds
            }
        }
        for try await byte in bytes {
            chunk.append(byte)
            if chunk.count == 32 * 1024 { try await flush() }
        }
        try await flush()
    }

    /** Raised when what is stored changes, or a drawing already stored would look wrong: each is made again when next printed. */
    static let SHAPE_FORMAT = 4
    static let ROUTE_FORMAT = 1
    /** What comes before a route's count of layers: its format, origin, cell, columns and rows. */
    static let ROUTE_HEADER_BYTES = 4 + 4 + 4 + 4 + 4 + 4
    /** What a route's list of layers says of each: number, height, where its squares sit, how many bytes they pack to. */
    static let ROUTE_ENTRY_BYTES = 4 + 4 + 4 * 2 + 4
    /** More than any part has; a list claiming more is a damaged file. */
    static let MAX_LAYERS = 100_000
    /** Likewise for how many squares a kept map has along a side, and how many bytes one layer packs to. */
    static let MAX_SIDE = 4096
    static let MAX_SLICES = 4096
    static let MAX_LAYER_BYTES = 4 * 1024 * 1024
    /** A usual layer. Only decides how thick the glowing top of a part-printed picture is. */
    static let LAYER_MM: Float = 0.2
    /** How soon a drawing that is wanted again and hasn't come may be asked for once more, and how often it is tried. */
    static let REDRAW_ASK_AGAIN_S: TimeInterval = 60
    static let REDRAW_TRIES = 5
}

/**
 * The part as it stands part-way through being printed: the [part] itself, and separately
 * the layer being [laying] on top of it, so that layer can be made to glow and move while the
 * rest holds still. [box] is where in the picture that layer is, as fractions of its size;
 * nil when there is none. [whole] is the finished part from the same spot, for showing what
 * is still to come.
 *
 * When the drawing is of one particular [layer] and the order that layer is laid in is
 * known, [laying] is only what the layer really lays, and [order] says when: a picture that
 * is most opaque, [FIRST], where the nozzle starts and least, [LAST], where it finishes.
 * [trail] is where the nozzle is from the start of the layer to the end, as in
 * [GcodePreview.Picture.trail].
 *
 * [dated] marks a drawing made before any order was kept: it can be shown, but is worth
 * making again. [view] says how big the part really is, which a picture that fills itself
 * with whatever it shows no longer can.
 */
public final class Growth: @unchecked Sendable {
    public let whole: CGImage
    public let part: CGImage
    public let laying: CGImage
    public let box: CGRect?
    public let layer: Int?
    public let order: CGImage?
    public let trail: [Float]?
    public let dated: Bool
    public let view: GcodePreview.Viewpoint?
    public let pictureWidth: Int
    public let pictureHeight: Int

    init(whole: CGImage, part: CGImage, laying: CGImage, box: CGRect?, layer: Int? = nil, order: CGImage? = nil,
         trail: [Float]? = nil, dated: Bool = false, view: GcodePreview.Viewpoint? = nil, pictureWidth: Int, pictureHeight: Int) {
        self.whole = whole; self.part = part; self.laying = laying; self.box = box
        self.layer = layer; self.order = order; self.trail = trail; self.dated = dated; self.view = view
        self.pictureWidth = pictureWidth; self.pictureHeight = pictureHeight
    }

    public static let FIRST = 255
    /** Well clear of nothing at all, so that blurring towards the layer's edge never reads as "laid last". */
    public static let LAST = 16
}

/// One at a time, the newest request first.
private actor DrawQueue {
    private final class Request {
        let file: PartFile
        let key: String
        var gentle: Bool
        var keepGoing: @Sendable () -> Bool
        var waiting: [CheckedContinuation<PartLibrary.Outcome, Never>] = []
        init(file: PartFile, key: String, gentle: Bool, keepGoing: @escaping @Sendable () -> Bool) {
            self.file = file; self.key = key; self.gentle = gentle; self.keepGoing = keepGoing
        }
    }

    private var pending = [Request]()
    private var current: Request?
    private var running = false

    func ask(file: PartFile, key: String, gentle: Bool, keepGoing: @escaping @Sendable () -> Bool, library: PartLibrary) async -> PartLibrary.Outcome {
        await withCheckedContinuation { (done: CheckedContinuation<PartLibrary.Outcome, Never>) in
            if let current, current.key == key {
                current.waiting.append(done)
                return
            }
            let request: Request
            if let at = pending.firstIndex(where: { $0.key == key }) {
                request = pending.remove(at: at)
                request.gentle = request.gentle || gentle
                let before = request.keepGoing
                request.keepGoing = { before() || keepGoing() }
            } else {
                request = Request(file: file, key: key, gentle: gentle, keepGoing: keepGoing)
            }
            request.waiting.append(done)
            pending.append(request)
            if !running {
                running = true
                Task { await self.run(library) }
            }
        }
    }

    private func run(_ library: PartLibrary) async {
        while let next = pending.popLast() {
            current = next
            let outcome = await library.work(next.file, key: next.key, gentle: next.gentle, keepGoing: next.keepGoing)
            current = nil
            for done in next.waiting { done.resume(returning: outcome) }
        }
        running = false
    }
}

/* ---------------- big-endian, as java.io.DataOutputStream ---------------- */

private final class Writer {
    var bytes = [UInt8]()

    func int(_ v: Int) {
        let u = UInt32(bitPattern: Int32(truncatingIfNeeded: v))
        bytes.append(UInt8(u >> 24)); bytes.append(UInt8(u >> 16 & 0xFF)); bytes.append(UInt8(u >> 8 & 0xFF)); bytes.append(UInt8(u & 0xFF))
    }

    func float(_ v: Float) { int(Int(Int32(bitPattern: v.bitPattern))) }

    func short(_ v: Int) {
        let u = UInt16(truncatingIfNeeded: v)
        bytes.append(UInt8(u >> 8)); bytes.append(UInt8(u & 0xFF))
    }

    func boolean(_ v: Bool) { bytes.append(v ? 1 : 0) }
}

private final class Reader {
    private let bytes: [UInt8]
    var at = 0
    /// Ran off the end: everything read since is 0.
    private(set) var failed = false

    init(_ bytes: [UInt8]) { self.bytes = bytes }

    private func take(_ n: Int) -> Int? {
        if at + n > bytes.count { failed = true; return nil }
        defer { at += n }
        return at
    }

    func int() -> Int {
        guard let i = take(4) else { return 0 }
        let u = UInt32(bytes[i]) << 24 | UInt32(bytes[i + 1]) << 16 | UInt32(bytes[i + 2]) << 8 | UInt32(bytes[i + 3])
        return Int(Int32(bitPattern: u))
    }

    func float() -> Float { Float(bitPattern: UInt32(bitPattern: Int32(truncatingIfNeeded: int()))) }

    /// Signed, as DataInputStream.readShort.
    func short() -> Int {
        guard let i = take(2) else { return 0 }
        return Int(Int16(bitPattern: UInt16(bytes[i]) << 8 | UInt16(bytes[i + 1])))
    }

    func unsignedShort() -> Int {
        guard let i = take(2) else { return 0 }
        return Int(UInt16(bytes[i]) << 8 | UInt16(bytes[i + 1]))
    }

    func boolean() -> Bool {
        guard let i = take(1) else { return false }
        return bytes[i] != 0
    }
}
