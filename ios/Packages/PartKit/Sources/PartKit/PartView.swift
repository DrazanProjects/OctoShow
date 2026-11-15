import Combine
import CoreGraphics
import SwiftUI

/**
 * The status screen's part panel: the job's part drawn as the print stands, inside a ghost
 * of the finished shape, its top layer filling in along the nozzle's path in the order the
 * slicer laid it, with a glow where the nozzle is. Ported from the Android app's ViewPanel,
 * PartSoFar and ModelPreview (ui/screens/StatusScreen.kt, ui/components/Views.kt) and
 * rememberPreview / rememberGrowth (ui/Preview.kt).
 *
 * It asks [library] for whatever it needs: the drawing of the file, made again if it
 * lacks the part's shape, and redrawn at every layer (or step of progress) and turn. Until
 * the drawing is ready, which is usually before the bed has finished heating, only the ghost
 * shows. A file that can't be drawn at all (one over 300 MB, say, or one OctoPrint won't hand
 * over) is reported through `onUndrawable`; given the slicer's `thumbnail`, the panel then
 * shows it with a line rising up it itself.
 *
 * Size it from outside: it fills what it is given, and the picture is 4:3, so a frame of
 * that shape (or a little squarer) suits it best.
 */
public struct PartView: View {
    private let library: PartLibrary
    private let file: PartFile?
    private let fraction: Double?
    private let layer: Int?
    private let layerTotal: Int?
    private let layerProgress: Int?
    private let fixes: AsyncStream<NozzleFix>?
    private let nozzle: NozzleWatch?
    private let fromCard: Bool
    private let printing: Bool
    private let tint: Color
    private let hot: Color
    @Binding private var turn: Int
    private let thumbnail: CGImage?
    private let onUndrawable: ((Bool) -> Void)?

    @State private var ownWatch = NozzleWatch()
    @State private var growth: Growth?
    @State private var waiting = true
    @State private var still: CGImage?
    @State private var note = ""
    @State private var drawn = 0
    @State private var glide = LayerGlide()
    @State private var glideEnds: TimeInterval = 0
    @State private var memo = BoundsMemo()
    @State private var shownFile: PartFile?
    @Environment(\.self) private var environment

    /**
     * - Parameters:
     *   - library: where the drawings come from.
     *   - file: what the job prints from (for a job from the printer's SD card, OctoPrint's own
     *     copy of the file, which is the one that can be read), or nil for none.
     *   - fraction: how far through the job the print is, 0...1; nil when no job is running,
     *     and then the finished part is shown whole.
     *   - layer, layerTotal, layerProgress: the Dashboard plugin's figures (layer from 1,
     *     progress 0...100 through it), nil when it isn't installed.
     *   - fixes: REALPOS readings, read for as long as the view is up. Or pass `nozzle`
     *     instead, a [NozzleWatch] the app feeds and can read itself.
     *   - fromCard: the printer is printing from its own SD card, where the plugin's figures
     *     are left over from some other job.
     *   - printing: plastic is going down: false while paused, pausing or cancelling.
     *   - tint: the filament's color. hot: the layer being laid; OctoShow's Ember by default.
     *   - turn: which side the part is seen from, in quarter turns: the button in the corner
     *     goes round them.
     *   - thumbnail: the slicer's picture of the file, if it has one: shown when the part isn't
     *     printing, and in place of a drawing that can't be made.
     *   - onUndrawable: told true when the part being printed can't be drawn, so the app can
     *     show something else, and false once it can.
     */
    public init(
        library: PartLibrary, file: PartFile?, fraction: Double?, layer: Int?, layerTotal: Int?, layerProgress: Int?,
        fixes: AsyncStream<NozzleFix>? = nil, nozzle: NozzleWatch? = nil, fromCard: Bool = false,
        printing: Bool, tint: Color, hot: Color = PartView.ember, turn: Binding<Int>,
        thumbnail: CGImage? = nil, onUndrawable: ((Bool) -> Void)? = nil
    ) {
        self.library = library
        self.file = file
        self.fraction = fraction
        self.layer = layer
        self.layerTotal = layerTotal
        self.layerProgress = layerProgress
        self.fixes = fixes
        self.nozzle = nozzle
        self.fromCard = fromCard
        self.printing = printing
        self.tint = tint
        self.hot = hot
        _turn = turn
        self.thumbnail = thumbnail
        self.onUndrawable = onUndrawable
    }

    /// OctoShow's Ember: the layer being laid.
    public static let ember = Color(red: 0xFF / 255.0, green: 0x6B / 255.0, blue: 0x2C / 255.0)
    /// The order a part is shown round in, in quarter turns: its far side first, as a long part's ends say little.
    public static let sides = [0, 2, 1, 3]
    /// Drawing the part costs a moment, and finer steps than this can't be told apart anyway.
    static let GROWTH_STEPS = 120
    static let ASK_AGAIN_NS: UInt64 = 15_000_000_000

    private var watch: NozzleWatch? { nozzle ?? (fixes != nil ? ownWatch : nil) }

    /// What the status screen worked out from the job's figures before handing them to the picture.
    private struct Stage: Equatable {
        var reveal: Float?
        var layer: Int?
        var laying: Bool
        var layerDone: Float?
        var liveDone: Float?
    }

    private var stage: Stage {
        let active = fraction != nil
        // where the nozzle really is, if the firmware says, and otherwise what has been sent to it
        let following = active ? watch?.layer : nil
        // The Dashboard plugin counts the layers OctoPrint sends, and of a file printed from the SD
        // card it sends none: there the layers come from the nozzle and OctoPrint's copy, or not at all.
        let current: Int?, total: Int?, progress: Int?
        if fromCard {
            current = following
            total = following != nil ? watch?.layerCount : nil
            progress = nil
        } else if let following {
            current = following; total = layerTotal; progress = layerProgress
        } else {
            current = layer; total = layerTotal; progress = layerProgress
        }
        let known = (current ?? 0) > 0 && (total ?? 0) > 0
        let layerFraction: Float? = known ? clamp(Float(max((current ?? 1) - 1, 0)) / Float(total!), 0, 1) : nil
        return Stage(
            reveal: active ? (layerFraction ?? Float(fraction!)) : nil,
            layer: active && known ? current : nil,
            laying: active && printing,
            layerDone: known ? progress.map { Float($0) / 100 } : nil,
            liveDone: following != nil ? watch?.done : nil
        )
    }

    private struct GrowthKey: Hashable {
        var file: PartFile?
        var step: Int?
        var layer: Int?
        var turn: Int
        var drawn: Int
        var tint: Int
        var hot: Int
    }

    private struct LookKey: Hashable {
        var file: PartFile?
        var tint: Int
        var drawn: Int
        var thumbnail: ObjectIdentifier?
    }

    private struct FollowKey: Hashable {
        var active: Bool
        var file: PartFile?
        var layer: Int?
        var fromCard: Bool
        var watch: ObjectIdentifier?
    }

    public var body: some View {
        let tintCG = tint.resolve(in: environment).cgColor
        let hotCG = hot.resolve(in: environment).cgColor
        let tintKey = RGB(tintCG).key
        let hotKey = RGB(hotCG).key
        let stage = self.stage
        let step = stage.reveal.map { Int(clamp($0, 0, 1) * Float(PartView.GROWTH_STEPS)) }
        let active = fraction != nil

        // A drawing of the layer before, still up while this layer's is being made, is of a layer
        // that is finished: shown complete, not emptied to match how little of the new one is down.
        let drawnLayer = growth?.layer
        let behind = drawnLayer != nil && drawnLayer != stage.layer
        let layerDone: Float? = behind ? 1 : (stage.layerDone ?? (stage.liveDone != nil ? 0 : nil))
        let liveDone = behind ? nil : stage.liveDone
        let undrawable = file != nil && active && growth == nil && !waiting

        ZStack(alignment: .topTrailing) {
            PartPicture(
                thumb: undrawable ? thumbnail : (still ?? thumbnail), reveal: stage.reveal, note: note,
                grown: growth, waiting: waiting, laying: stage.laying, layerDone: layerDone, liveDone: liveDone,
                glide: glide, glideEnds: glideEnds, hot: hot, hotHi: PartView.hotHi(hotCG), memo: memo
            )
            // what stands behind a part while it prints, such as its supports, only shows from the other side
            if stage.reveal != nil {
                Button {
                    let at = PartView.sides.firstIndex(of: ((turn % 4) + 4) % 4) ?? 0
                    turn = PartView.sides[(at + 1) % PartView.sides.count]
                } label: {
                    TurnIcon()
                        .stroke(Ink.soft, style: StrokeStyle(lineWidth: 1.9 * 22 / 24, lineCap: .round, lineJoin: .round))
                        .frame(width: 22, height: 22)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Color.black.opacity(0.6)))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .padding(10)
                .accessibilityLabel("Turn the part")
            }
        }
        .onChange(of: file, initial: true) { _, now in
            // a new file starts from the last drawing made of it, if that was the last made of anything
            if now != shownFile {
                shownFile = now
                growth = file.flatMap { library.lastGrown($0, tint: tintCG, hot: hotCG, turn: turn) }
                waiting = true
                still = nil
                note = ""
            }
        }
        .task(id: GrowthKey(file: file, step: step, layer: stage.layer, turn: turn, drawn: drawn, tint: tintKey, hot: hotKey)) {
            await grow(step: step, layer: stage.layer, tint: tintCG, hot: hotCG, gentle: active)
        }
        .task(id: LookKey(file: file, tint: tintKey, drawn: drawn, thumbnail: thumbnail.map(ObjectIdentifier.init))) {
            await lookUp(tint: tintCG, gentle: active)
        }
        .task(id: FollowKey(active: active, file: file, layer: layer, fromCard: fromCard, watch: watch.map(ObjectIdentifier.init))) {
            guard active, let watch else { return }
            while !Task.isCancelled {
                watch.follow(library: library, file: file, sent: layer, fromCard: fromCard)
                try? await Task.sleep(nanoseconds: watch.reporting ? 16_000_000 : 250_000_000)
            }
        }
        .task {
            guard let fixes, nozzle == nil else { return }
            for await fix in fixes { ownWatch.add(fix) }
        }
        .onReceive(NotificationCenter.default.publisher(for: PartLibrary.didDraw, object: library).receive(on: DispatchQueue.main)) { notice in
            if let file, let key = notice.userInfo?["key"] as? String, key == library.key(file) { drawn += 1 }
        }
        .onChange(of: layerDone, initial: true) { _, done in
            let now = ProcessInfo.processInfo.systemUptime
            glide.set(done, now: now)
            glideEnds = glide.moving(now) ? now + 8.5 : 0
        }
        .task(id: glideEnds) {
            // let the picture stop redrawing once the glide is over, when nothing else moves it
            let now = ProcessInfo.processInfo.systemUptime
            if glideEnds > now {
                try? await Task.sleep(nanoseconds: UInt64((glideEnds - now) * 1e9))
                if !Task.isCancelled { glideEnds = 0 }
            }
        }
        .onChange(of: undrawable, initial: true) { _, cannot in onUndrawable?(cannot) }
    }

    /**
     * The part as far as it has been printed: the file's drawing cut off at the layer the job
     * has reached, with that layer glowing. A new one is drawn each time the job has moved on
     * by a step, or by a layer when OctoPrint says which it is on, or the part is turned. The
     * last stays up meanwhile, so the part grows without ever blinking out.
     */
    private func grow(step: Int?, layer: Int?, tint: CGColor, hot: CGColor, gentle: Bool) async {
        guard let file, let step else {
            growth = nil
            waiting = false
            return
        }
        let grown = await library.grown(file, fraction: Float(step) / Float(PartView.GROWTH_STEPS), layer: layer, tint: tint, hot: hot, turn: turn)
        if Task.isCancelled { return }
        if let grown {
            growth = grown
            waiting = false
        }
        // No drawing to grow, or one from before all of it was kept: have it made again.
        // Asked for more than once, since the fetch can fail while the Pi is busy starting
        // a print. When it does come, the library says so and this starts over.
        while grown == nil || grown!.dated {
            let coming = library.wantShape(file, gentle: gentle)
            if grown == nil { waiting = coming }
            try? await Task.sleep(nanoseconds: PartView.ASK_AGAIN_NS)
            if Task.isCancelled { return }
        }
    }

    /**
     * The picture of the finished part: the slicer's own if there is one, otherwise the one
     * drawn here. Asking for a file that has neither puts it at the front of the queue to be
     * drawn, and the picture appears by itself when it is ready.
     */
    private func lookUp(tint: CGColor, gentle: Bool) async {
        guard let file else {
            still = nil
            note = ""
            return
        }
        if thumbnail != nil {
            still = nil
            note = ""
            return
        }
        let picture = await library.preview(file, tint: tint)
        if Task.isCancelled { return }
        still = picture
        note = picture == nil ? library.note(file) : ""
        if picture == nil {
            let library = self.library
            Task.detached { await library.want(file, gentle: gentle) }
        }
    }

    /// The hot color made paler, for the glow at the nozzle: OctoShow's own pairing for Ember.
    static func hotHi(_ hot: CGColor) -> Color {
        let rgb = RGB(hot)
        if rgb == RGB(r: 0xFF, g: 0x6B, b: 0x2C) { return Color(red: 0xFF / 255.0, green: 0xC0 / 255.0, blue: 0x8A / 255.0) }
        func mix(_ v: Int) -> Double { (Double(v) + (255 - Double(v)) * 0.55) / 255 }
        return Color(red: mix(rgb.r), green: mix(rgb.g), blue: mix(rgb.b))
    }
}

/// The colors the Android app draws the panel in (its "bone on black" palette).
enum Ink {
    static let panel = Color(red: 0x12 / 255.0, green: 0x14 / 255.0, blue: 0x16 / 255.0)
    static let line = Color(red: 0x2A / 255.0, green: 0x2E / 255.0, blue: 0x32 / 255.0)
    static let soft = Color(red: 0xA3 / 255.0, green: 0xA8 / 255.0, blue: 0xAD / 255.0)
    static let faint = Color(red: 0x7B / 255.0, green: 0x81 / 255.0, blue: 0x87 / 255.0)
}

/// Where in a picture its part is, worked out once per picture.
final class BoundsMemo {
    private var image: CGImage?
    private var bounds: CGRect?

    func of(_ picture: CGImage) -> CGRect {
        if picture === image, let bounds { return bounds }
        let found = opaqueBounds(picture)
        image = picture
        bounds = found
        return found
    }
}

/** The box, as fractions of the picture, that holds every visible pixel of the part. */
func opaqueBounds(_ image: CGImage) -> CGRect {
    let whole = CGRect(x: 0, y: 0, width: 1, height: 1)
    let w = image.width, h = image.height
    switch image.alphaInfo {
    case .none, .noneSkipLast, .noneSkipFirst: return whole
    default: break
    }
    var px = [UInt8](repeating: 0, count: w * h * 4)
    let ok: Bool = px.withUnsafeMutableBytes { raw in
        guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return true
    }
    if !ok { return whole }
    var minX = w, maxX = -1, minY = h, maxY = -1
    for y in 0..<h {
        let row = y * w
        for x in 0..<w where px[4 * (row + x) + 3] > 24 {
            if x < minX { minX = x }
            if x > maxX { maxX = x }
            if y < minY { minY = y }
            if y > maxY { maxY = y }
        }
    }
    if maxX < 0 { return whole }
    return CGRect(x: CGFloat(minX) / CGFloat(w), y: CGFloat(minY) / CGFloat(h),
                  width: CGFloat(maxX + 1 - minX) / CGFloat(w), height: CGFloat(maxY + 1 - minY) / CGFloat(h))
}

/**
 * The picture of the part, printing itself. With [reveal] nil the whole part is shown solid.
 *
 * Given [grown], a drawing of the part as it stands so far, that is shown inside a ghost of
 * the finished shape. Its top layer is colored in as far as [layerDone] says the layer has
 * been printed, in the order the nozzle goes over it, with a hot spot where the nozzle is
 * while plastic is actually [laying].
 *
 * When that order isn't known for the layer, or OctoPrint gives no figure for how far
 * through it the print is, the whole layer is shown warm with the spot wandering over it.
 *
 * Without a drawing, only the ghost shows while one is [waiting] to be made. A file that
 * can't be drawn at all is shown solid below the line the nozzle has reached and a ghost
 * above it.
 */
struct PartPicture: View {
    let thumb: CGImage?
    let reveal: Float?
    let note: String
    let grown: Growth?
    let waiting: Bool
    let laying: Bool
    let layerDone: Float?
    let liveDone: Float?
    let glide: LayerGlide
    let glideEnds: TimeInterval
    let hot: Color
    let hotHi: Color
    let memo: BoundsMemo

    /** How strongly the layer being laid shows as warm when all of it is, before the hot spot passes over it. */
    static let WARM: Double = 0.5
    /** The same for the part of it known to be down, which can afford to be plainer about it. */
    static let LAID: Double = 0.62
    /** How far the glow around the nozzle reaches, when where the nozzle is is known. */
    static let NOZZLE_GLOW: CGFloat = 30
    /** How gradually plastic appears behind the nozzle, in steps of an order picture's opacity. */
    static let SOFT: Float = 8
    /** The bead on the layer stack goes back and forth in this long, and this goes with it. */
    static let PACE: TimeInterval = 2.6

    var body: some View {
        let picture = grown?.whole ?? thumb
        let breathing = grown != nil && laying && reveal != nil
        let animating = breathing || glideEnds > 0
        ZStack {
            Ink.panel
            Canvas { ctx, size in plateDots(ctx, size) }
            if let picture {
                TimelineView(.animation(minimumInterval: nil, paused: !animating)) { timeline in
                    Canvas { ctx, size in
                        draw(ctx, size, picture, timeline.date, breathing: breathing)
                    }
                }
                .padding(8)
                .accessibilityElement()
                .accessibilityLabel(reveal == nil ? "The part" : "The part as printed so far")
                // a picture that fills itself with whatever it shows says nothing of how big that is
                if let view = grown?.view, view.long > 0 {
                    Text(partSize(view.long, view.broad, view.high))
                        .font(.system(size: 13, weight: .medium).width(.condensed).monospacedDigit())
                        .foregroundStyle(Ink.faint)
                        .padding(12)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                }
            } else {
                VStack(spacing: 10) {
                    CubeIcon()
                        .stroke(Ink.line, style: StrokeStyle(lineWidth: 1.9 * 72 / 24, lineCap: .round, lineJoin: .round))
                        .frame(width: 72, height: 72)
                    if !note.isEmpty {
                        Text(note)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Ink.faint)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    /** The nozzle's to and fro, 0.04..0.96, the same pace as the bead on the layer stack. */
    private func breath(_ date: Date) -> CGFloat {
        let t = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2 * PartPicture.PACE)
        let p = t < PartPicture.PACE ? t / PartPicture.PACE : 1 - (t - PartPicture.PACE) / PartPicture.PACE
        return 0.04 + 0.92 * CGFloat(fastOutSlowIn(p))
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize, _ picture: CGImage, _ date: Date, breathing: Bool) {
        let iw = CGFloat(picture.width), ih = CGFloat(picture.height)
        let scale = min(size.width / iw, size.height / ih)
        let w = iw * scale
        let h = ih * scale
        let left = (size.width - w) / 2
        let top = (size.height - h) / 2
        let rect = CGRect(x: left, y: top, width: w, height: h)
        let image = ctx.resolve(Image(decorative: picture, scale: 1).interpolation(.high))

        guard let reveal else {
            ctx.draw(image, in: rect)
            return
        }
        let bounds = memo.of(picture)

        // map progress onto the rows the part actually occupies, not the empty margin around it
        let partTop = top + h * bounds.minY
        let partBottom = top + h * bounds.maxY
        let line = partBottom - (partBottom - partTop) * CGFloat(clamp(reveal, 0, 1))
        let overhang: CGFloat = 10
        let lineLeft = max(left + w * bounds.minX - overhang, 0)
        let lineRight = min(left + w * bounds.maxX + overhang, size.width)

        var ghost = ctx
        ghost.opacity = 0.22
        ghost.addFilter(.grayscale(1))
        ghost.draw(image, in: rect)

        if let grown {
            // drawn from the same spot as the finished picture, so it sits exactly inside the ghost
            ctx.draw(ctx.resolve(Image(decorative: grown.part, scale: 1).interpolation(.high)), in: rect)
            let fresh = ctx.resolve(Image(decorative: grown.laying, scale: 1).interpolation(.high))
            let along: CGFloat? = breathing ? breath(date) : nil
            let now = ProcessInfo.processInfo.systemUptime
            // the real thing needs no smoothing: it comes in as fast as the screen can show it
            let down = liveDone ?? glide.value(now)
            var spot: CGPoint?
            var reach: CGFloat = 0
            if layerDone != nil, let order = grown.order, let trail = grown.trail {
                // The layer is painted whole, then kept only where the nozzle has been.
                var laid = ctx
                laid.opacity = PartPicture.LAID
                let orderImage = ctx.resolve(Image(decorative: order, scale: 1).interpolation(.low))
                laid.drawLayer { layer in
                    layer.draw(fresh, in: rect)
                    var keep = layer
                    keep.blendMode = .destinationIn
                    keep.addFilter(.colorMatrix(PartPicture.laidBy(down)))
                    keep.draw(orderImage, in: rect)
                }
                if let along {
                    let moment = clamp(down * Float(GcodePreview.MOMENTS - 1), 0, Float(GcodePreview.MOMENTS) - 1.001)
                    let m = Int(moment)
                    let on = moment - Float(m)
                    spot = CGPoint(
                        x: left + w * CGFloat(trail[2 * m] + (trail[2 * m + 2] - trail[2 * m]) * on),
                        y: top + h * CGFloat(trail[2 * m + 1] + (trail[2 * m + 3] - trail[2 * m + 1]) * on)
                    )
                    // it has a place to be, so what keeps it alive is a slow breath
                    reach = PartPicture.NOZZLE_GLOW * (0.85 + 0.3 * along)
                }
            } else {
                var warm = ctx
                warm.opacity = PartPicture.WARM
                warm.draw(fresh, in: rect)
                if let box = grown.box, let along {
                    spot = CGPoint(x: left + w * (box.minX + box.width * along), y: top + h * box.midY)
                    reach = max(w * box.width, h * box.height) * 0.34
                }
            }
            if let spot, reach > 0 {
                // the spot is painted whole, then kept only where the top layer is
                ctx.drawLayer { layer in
                    let disc = Path(ellipseIn: CGRect(x: spot.x - reach, y: spot.y - reach, width: reach * 2, height: reach * 2))
                    layer.fill(disc, with: .radialGradient(
                        Gradient(colors: [hotHi, hotHi.opacity(0)]), center: spot, startRadius: 0, endRadius: reach
                    ))
                    var keep = layer
                    keep.blendMode = .destinationIn
                    keep.draw(fresh, in: rect)
                }
            }
            return
        }
        // the drawing to grow it from is on its way: nothing yet that it would have to take back
        if waiting { return }
        var below = ctx
        below.clip(to: Path(CGRect(x: 0, y: line, width: size.width, height: max(size.height - line, 0))))
        below.draw(image, in: rect)
        if reveal > 0 && reveal < 1 {
            let glow: CGFloat = 14
            ctx.fill(
                Path(CGRect(x: lineLeft, y: line - glow, width: lineRight - lineLeft, height: glow)),
                with: .linearGradient(Gradient(colors: [hot.opacity(0), hot.opacity(0.28)]),
                                      startPoint: CGPoint(x: 0, y: line - glow), endPoint: CGPoint(x: 0, y: line))
            )
            var stroke = Path()
            stroke.move(to: CGPoint(x: lineLeft, y: line))
            stroke.addLine(to: CGPoint(x: lineRight, y: line))
            ctx.stroke(stroke, with: .color(hotHi), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
    }

    /**
     * For an order picture, see [Growth.order]: keeps what the nozzle has been over by the
     * time the layer is [done] this far, 0..1, and none of the rest.
     */
    static func laidBy(_ done: Float) -> ColorMatrix {
        let first = Float(Growth.FIRST)
        let last = Float(Growth.LAST)
        // anything at least this opaque is down; SOFT further and it is fully so
        let from = first - done * (first - last + SOFT)
        let steep = 255 / SOFT
        var m = ColorMatrix()
        m.a4 = steep
        m.a5 = -steep * from / 255
        return m
    }

    /** A dot grid, like the texture of a build plate. */
    private func plateDots(_ ctx: GraphicsContext, _ size: CGSize) {
        let step: CGFloat = 22
        let r: CGFloat = 1.1
        var dots = Path()
        var y = step / 2 + size.height.truncatingRemainder(dividingBy: step) / 2
        while y < size.height {
            var x = step / 2 + size.width.truncatingRemainder(dividingBy: step) / 2
            while x < size.width {
                dots.addEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
                x += step
            }
            y += step
        }
        ctx.fill(dots, with: .color(Ink.line))
    }
}

/** "120 × 35 × 18 mm": a part's size, a decimal place for anything under a centimetre. */
public func partSize(_ long: Float, _ broad: Float, _ high: Float) -> String {
    [long, broad, high].map { halfUp($0, places: $0 < 10 ? 1 : 0) }.joined(separator: " × ") + " mm"
}

/// As Java's "%.Nf" has it: the shortest decimal for the number, rounded half up. (C's rounds 8.25 down.)
func halfUp(_ v: Float, places: Int) -> String {
    guard v.isFinite, var exact = Decimal(string: "\(Double(v))", locale: Locale(identifier: "en_US_POSIX")) else {
        return String(format: "%.\(places)f", Double(v))
    }
    var rounded = Decimal()
    NSDecimalRound(&rounded, &exact, places, .plain)
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.minimumFractionDigits = places
    formatter.maximumFractionDigits = places
    formatter.minimumIntegerDigits = 1
    formatter.usesGroupingSeparator = false
    return formatter.string(from: rounded as NSDecimalNumber) ?? String(format: "%.\(places)f", Double(v))
}

/// OctoShow's cube, drawn on a 24-unit square.
struct CubeIcon: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * s, y: rect.minY + y * s) }
        var path = Path()
        path.move(to: p(12, 3)); path.addLine(to: p(20, 7.5)); path.addLine(to: p(20, 16.5)); path.addLine(to: p(12, 21))
        path.addLine(to: p(4, 16.5)); path.addLine(to: p(4, 7.5)); path.closeSubpath()
        path.move(to: p(4, 7.5)); path.addLine(to: p(12, 12)); path.addLine(to: p(20, 7.5))
        path.move(to: p(12, 12)); path.addLine(to: p(12, 21))
        return path
    }
}

/// OctoShow's turn arrow, drawn on a 24-unit square.
struct TurnIcon: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * s, y: rect.minY + y * s) }
        var path = Path()
        path.move(to: p(3, 12))
        path.addCurve(to: p(12, 7.5), control1: p(3, 9.5), control2: p(7, 7.5))
        path.addCurve(to: p(21, 12), control1: p(17, 7.5), control2: p(21, 9.5))
        path.addCurve(to: p(12, 16.5), control1: p(21, 14.5), control2: p(17, 16.5))
        path.move(to: p(15, 13.5)); path.addLine(to: p(12, 16.5)); path.addLine(to: p(15, 19.5))
        return path
    }
}
