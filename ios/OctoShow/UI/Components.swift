import SwiftUI

/* ---------------- buttons ---------------- */

/// The one thing to do on a screen: accent fill, black text. Disabled, it goes quiet but stays
/// readable rather than turning dark-on-dark.
struct PrimaryButtonStyle: ButtonStyle {
    var height: CGFloat = 54
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.button)
            .foregroundStyle(enabled ? Ink.black : Ink.faint)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(enabled ? AnyShapeStyle(.tint) : AnyShapeStyle(Ink.panelHi), in: .rect(cornerRadius: 16, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

/// Everything else: a quiet surface, its label in `color`.
struct QuietButtonStyle: ButtonStyle {
    var color: Color = Ink.bone
    var height: CGFloat = 54
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.button)
            .foregroundStyle(enabled ? color : Ink.faint)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(Ink.panelHi, in: .rect(cornerRadius: 16, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

/// A big plain button in the panel color.
struct QuietButton: View {
    let title: String
    var systemImage: String?
    var color: Color = Ink.bone
    var height: CGFloat = 54
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).lineLimit(1).minimumScaleFactor(0.8)
            }
        }
        .buttonStyle(QuietButtonStyle(color: color, height: height))
        .disabled(!enabled)
    }
}

/// Shrinks a little under the finger.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

/// A button that has to be held, not tapped, so a bumped phone can't end an eight-hour print.
/// It fills while held and fires once full; letting go early does nothing.
struct HoldButton: View {
    let title: String
    var systemImage: String?
    var color: Color = Ink.bad
    var seconds: Double = 1.2
    var enabled = true
    let action: () -> Void

    @State private var progress: CGFloat = 0
    @State private var holding = false
    @State private var fired = 0

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Ink.panelHi)
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(color.opacity(0.32))
                .scaleEffect(x: progress, y: 1, anchor: .leading)
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(holding ? "Keep holding…" : title).lineLimit(1).minimumScaleFactor(0.8)
            }
            .font(.button)
            .foregroundStyle(enabled ? color : Ink.faint)
            .padding(.horizontal, 8)
        }
        .frame(height: 54)
        .clipShape(.rect(cornerRadius: 16, style: .continuous))
        .contentShape(.rect)
        .opacity(enabled ? 1 : 0.5)
        .onLongPressGesture(minimumDuration: seconds, maximumDistance: 40) {
            guard enabled else { return }
            withAnimation(.easeOut(duration: 0.2)) { progress = 0 }
            holding = false
            fired += 1
            action()
        } onPressingChanged: { pressing in
            guard enabled else { return }
            holding = pressing
            withAnimation(pressing ? .linear(duration: seconds) : .easeOut(duration: 0.25)) { progress = pressing ? 1 : 0 }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: holding) { _, now in now }
        .sensoryFeedback(.warning, trigger: fired)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint("Touch and hold")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { if enabled { action() } }
    }
}

/// One of a few choices, side by side.
struct Choice<T: Hashable>: View {
    let options: [(T, String)]
    let selected: T?
    var enabled = true
    let pick: (T) -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(options, id: \.0) { value, label in
                let on = value == selected
                Button { pick(value) } label: {
                    Text(label)
                        .font(.label)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(on ? Ink.black : (enabled ? Ink.bone : Ink.faint))
                        .frame(maxWidth: .infinity, minHeight: 42)
                        .background(on ? AnyShapeStyle(.tint) : AnyShapeStyle(Ink.panelHi), in: .rect(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(PressStyle())
                .disabled(!enabled)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .sensoryFeedback(.selection, trigger: selected)
    }
}

/* ---------------- heat ---------------- */

/// Nozzle or bed: what it is at, where it's heading, and the last few minutes as a trace with
/// the target drawn across it, so "on target" reads at a glance.
struct HeatCard: View {
    let label: String
    let temp: Temp?
    let trace: [Double?]
    let color: Color

    private var state: String {
        guard let t = temp else { return "–" }
        if !t.on { return "Off" }
        if t.heating { return "Heating" }
        if let a = t.actual, a > t.target + 5 { return "Cooling" }
        return "Holding"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Caption(label)
                Spacer()
                Text(state)
                    .font(.meta)
                    .foregroundStyle(temp?.on == true ? color : Ink.faint)
            }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(degrees(temp?.actual))
                    .font(.reading)
                    .foregroundStyle(Ink.bone)
                    .monospacedDigit()
                    .contentTransition(.numericText(value: temp?.actual ?? 0))
                    .animation(.smooth, value: temp?.actual)
                if let t = temp, t.on {
                    Text("of \(degrees(t.target))")
                        .font(.meta)
                        .foregroundStyle(Ink.soft)
                }
            }
            Sparkline(values: trace, target: temp?.on == true ? temp?.target : nil, color: temp?.on == true ? color : Ink.faint)
                .frame(height: 28)
        }
        .panel(padding: 14)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label), \(degrees(temp?.actual))\(temp?.on == true ? ", heading for \(degrees(temp?.target))" : ", off")")
    }
}

/// A small line of recent readings, with the target as a dashed line and a soft fill beneath.
struct Sparkline: View {
    let values: [Double?]
    var target: Double?
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let points = values.enumerated().compactMap { i, v in v.map { (i, $0) } }
            guard points.count > 1, var lo = points.map(\.1).min(), var hi = points.map(\.1).max() else { return }
            if let target { lo = min(lo, target); hi = max(hi, target) }
            let span = max(hi - lo, 10)
            let mid = (hi + lo) / 2
            func y(_ v: Double) -> CGFloat { size.height * (0.5 - CGFloat((v - mid) / span) * 0.86) }
            func x(_ i: Int) -> CGFloat { size.width * CGFloat(i) / CGFloat(max(values.count - 1, 1)) }

            if let target {
                var t = Path()
                t.move(to: CGPoint(x: 0, y: y(target)))
                t.addLine(to: CGPoint(x: size.width, y: y(target)))
                ctx.stroke(t, with: .color(color.opacity(0.45)), style: StrokeStyle(lineWidth: 1, dash: [3, 4]))
            }
            var line = Path()
            for (n, (i, v)) in points.enumerated() {
                let p = CGPoint(x: x(i), y: y(v))
                if n == 0 { line.move(to: p) } else { line.addLine(to: p) }
            }
            var fill = line
            fill.addLine(to: CGPoint(x: x(points.last!.0), y: size.height))
            fill.addLine(to: CGPoint(x: x(points.first!.0), y: size.height))
            fill.closeSubpath()
            ctx.fill(fill, with: .linearGradient(Gradient(colors: [color.opacity(0.22), color.opacity(0)]),
                                                 startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}

/* ---------------- progress ---------------- */

/// A thin bar of how far the job has got.
struct ProgressTrack: View {
    let fraction: Double
    var color: Color

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Ink.line)
                Capsule().fill(color)
                    .frame(width: max(6, g.size.width * min(max(fraction, 0), 1)))
            }
        }
        .frame(height: 6)
        .animation(.smooth(duration: 0.6), value: fraction)
        .accessibilityHidden(true)
    }
}

/// A stack of layers that grows as the print does. With the Dashboard plugin each bar is a
/// layer (or several, once there are too many to tell apart), and the bead on the top bar is
/// how far through that layer the nozzle has got. Without it the stack fills by percentage.
struct LayerStack: View {
    let fraction: Double
    let layer: Int?
    let total: Int?
    let layerProgress: Int?
    let filament: Color
    let hot: Color
    var bars = 14

    var body: some View {
        let count = min(total ?? bars, bars)
        let perBar = Double(max(total ?? bars, 1)) / Double(max(count, 1))
        let doneBars: Double = {
            if let layer, let total, total > 0 {
                let within = Double(layerProgress ?? 0) / 100
                return (Double(layer - 1) + within) / perBar
            }
            return fraction * Double(count)
        }()
        VStack(spacing: 3) {
            ForEach((0..<count).reversed(), id: \.self) { i in
                let fill = min(max(doneBars - Double(i), 0), 1)
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Ink.line)
                        Capsule().fill(fill >= 1 ? filament.opacity(0.9) : hot)
                            .frame(width: g.size.width * fill)
                        if fill > 0 && fill < 1 {
                            Circle().fill(hot).frame(width: 7, height: 7)
                                .shadow(color: hot.opacity(0.9), radius: 4)
                                .offset(x: g.size.width * fill - 3.5)
                        }
                    }
                }
                .frame(height: 4)
            }
        }
        .animation(.smooth(duration: 0.6), value: doneBars)
        .accessibilityElement()
        .accessibilityLabel(layer.flatMap { l in total.map { "Layer \(l) of \($0)" } } ?? "\(Int(fraction * 100)) percent")
    }
}

/* ---------------- pictures ---------------- */

/// A file's picture on a lit backdrop: the slicer's own, or the one drawn from its G-code, or a
/// quiet placeholder.
struct FilePicture: View {
    let image: UIImage?
    var corner: CGFloat = 14

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: corner, style: .continuous).fill(Ink.well)
            if let image {
                Image(uiImage: image).resizable().scaledToFit().padding(5)
                    .transition(.opacity)
            } else {
                Image(systemName: "cube.transparent").font(.title3).foregroundStyle(Ink.faint)
            }
        }
        .animation(.easeOut(duration: 0.25), value: image != nil)
        .clipShape(.rect(cornerRadius: corner, style: .continuous))
    }
}

/* ---------------- connection ---------------- */

/// Home, away, or not reaching it at all: a dot and a word.
struct ViaBadge: View {
    let session: PrinterSession

    var body: some View {
        let (text, color): (String, Color) = switch session.link {
        case .online: (session.via == .home ? "Home" : "Away", Ink.ok)
        case .connecting: ("Connecting", Ink.faint)
        default: ("Offline", Ink.bad)
        }
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
                .shadow(color: color.opacity(0.7), radius: session.link == .online ? 3 : 0)
            Text(text).font(.meta).foregroundStyle(Ink.soft)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(Ink.panel, in: Capsule())
        .overlay(Capsule().strokeBorder(Ink.line.opacity(0.7), lineWidth: 0.5))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(session.link == .online ? "Connected \(session.via == .home ? "at home" : "from away")" : text)
    }
}

/// A screen's own header, in place of a navigation bar: its name, and the connection.
struct ScreenHeader: View {
    let title: String
    var subtitle: String?
    let session: PrinterSession

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.screenTitle).foregroundStyle(Ink.bone).lineLimit(1).minimumScaleFactor(0.7)
                if let subtitle {
                    Text(subtitle).font(.label).foregroundStyle(Ink.soft).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            ViaBadge(session: session)
        }
        .padding(.top, 4)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}
