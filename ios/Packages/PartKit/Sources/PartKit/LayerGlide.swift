import Foundation

/**
 * Says how long the next figure is likely to take. Ten moves can take a tenth as long in
 * one part of a layer as in the next, so the gap just gone says little about the one to
 * come. But one layer is mostly much like the last, so the same point of that one does.
 */
public final class FigurePace {
    /** How long the print spent after each percent of the layer, the last time it was there. */
    private var after = [Int64](repeating: 0, count: 101)
    private var percent = -1
    private var at: Int64 = 0

    public init() {}

    /** The figure [next] came in at [now]: how long, in milliseconds, until the one after it. */
    public func expect(_ next: Int, _ now: Int64) -> Int64 {
        let since = now - at
        let began = percent < 0
        if !began && next > percent { after[percent] = since }
        percent = next
        at = now
        for near in [next, next - 1, next + 1] where near >= 0 && near <= 100 && after[near] > 0 { return after[near] }
        return began ? 0 : since
    }
}

/** How long to take over a figure, given how long the next one is expected to take to come. */
public func glideMillis(_ expected: Int64) -> Int {
    Int(clamp(Float(expected) * LayerGlide.GLIDE_STRETCH, Float(LayerGlide.GLIDE_SHORTEST_MS), Float(LayerGlide.GLIDE_LONGEST_MS)))
}

/**
 * How far through its layer the print has got, 0..1, moving steadily. The figures come
 * in bursts: the Dashboard plugin gives one every ten moves, which can be a fraction of a
 * second on a curve and several seconds along straight lines. So each is moved towards
 * for a little longer than the next is expected to take, see [FigurePace], which runs on
 * until that one arrives, at the cost of being that far behind the nozzle.
 *
 * A new layer starts from nothing at once: gliding backwards across the part would look
 * like the print undoing itself. A layer that is done runs out quickly, as what is drawn
 * next starts from nothing anyway.
 *
 * (rememberLayerDone in the Android app.) Not observable: read [value] at the time a frame
 * is drawn.
 */
public final class LayerGlide {
    private var from: Float = 0
    private var to: Float = 0
    private var start: TimeInterval = 0
    private var duration: TimeInterval = 0
    private let pace = FigurePace()
    private var started = false

    public init() {}

    /** A new figure, [done] 0..1 or nil for none, at [now] seconds on the app's steady clock. */
    public func set(_ done: Float?, now: TimeInterval) {
        let target = clamp(done ?? 0, 0, 1)
        if !started {
            started = true
            from = target
            to = target
        }
        let expected = pace.expect(kRound(target * 100), Int64(now * 1000))
        let current = value(now)
        if target < current {
            from = target
            to = target
            duration = 0
        } else {
            from = current
            to = target
            start = now
            duration = Double(target >= 1 ? LayerGlide.GLIDE_SHORTEST_MS : glideMillis(expected)) / 1000
        }
    }

    /** Where it has got to at [now]. */
    public func value(_ now: TimeInterval) -> Float {
        if duration <= 0 || now >= start + duration { return to }
        if now <= start { return from }
        return from + (to - from) * Float((now - start) / duration)
    }

    /** Still on its way at [now]. */
    public func moving(_ now: TimeInterval) -> Bool { duration > 0 && now < start + duration }

    /** A little over the gap between figures, so that the fill is still moving when the next one comes. */
    static let GLIDE_STRETCH: Float = 1.2
    static let GLIDE_SHORTEST_MS = 900
    /** A figure that took longer than this means the print stopped, not that the next will be as slow. */
    static let GLIDE_LONGEST_MS = 8000
}

/// Compose's FastOutSlowInEasing: cubic-bezier(0.4, 0, 0.2, 1).
func fastOutSlowIn(_ t: Double) -> Double {
    let x1 = 0.4, y1 = 0.0, x2 = 0.2, y2 = 1.0
    if t <= 0 { return 0 }
    if t >= 1 { return 1 }
    func bez(_ a: Double, _ b: Double, _ s: Double) -> Double {
        let u = 1 - s
        return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
    }
    // the curve's s for x = t, by bisection: steady, and cheap enough for a frame
    var lo = 0.0, hi = 1.0
    for _ in 0..<24 {
        let mid = (lo + hi) / 2
        if bez(x1, x2, mid) < t { lo = mid } else { hi = mid }
    }
    return bez(y1, y2, (lo + hi) / 2)
}
