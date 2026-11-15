import SwiftUI

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

    var body: some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Ink.panelHi)
            GeometryReader { g in
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(color.opacity(0.35))
                    .frame(width: g.size.width * progress)
            }
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(holding ? "Keep holding…" : title)
            }
            .font(.button)
            .foregroundStyle(enabled ? color : Ink.faint)
            .frame(maxWidth: .infinity)
        }
        .frame(height: 54)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(Rectangle())
        .opacity(enabled ? 1 : 0.5)
        .onLongPressGesture(minimumDuration: seconds, maximumDistance: 40) {
            guard enabled else { return }
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            withAnimation(.easeOut(duration: 0.2)) { progress = 0 }
            holding = false
            action()
        } onPressingChanged: { pressing in
            guard enabled else { return }
            holding = pressing
            if pressing {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                withAnimation(.linear(duration: seconds)) { progress = 1 }
            } else {
                withAnimation(.easeOut(duration: 0.25)) { progress = 0 }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint("Touch and hold")
        .accessibilityAction { if enabled { action() } }
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
                Text(title)
            }
            .font(.button)
            .foregroundStyle(enabled ? color : Ink.faint)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressStyle())
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
                        .foregroundStyle(on ? Ink.black : (enabled ? Ink.bone : Ink.faint))
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(on ? AnyShapeStyle(.tint) : AnyShapeStyle(Ink.panelHi),
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(PressStyle())
                .disabled(!enabled)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}

/// Nozzle or bed: what it is at, what it is heading for, and the last few minutes as a trace.
struct HeatReading: View {
    let label: String
    let temp: Temp?
    let trace: [Double?]
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Caption(label)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(degrees(temp?.actual))
                    .font(.reading)
                    .foregroundStyle(Ink.bone)
                    .monospacedDigit()
                if let t = temp, t.on {
                    Text("→ \(degrees(t.target))")
                        .font(.meta)
                        .foregroundStyle(t.heating ? color : Ink.soft)
                }
            }
            Sparkline(values: trace, color: temp?.on == true ? color : Ink.faint)
                .frame(height: 22)
        }
        .panel(padding: 14)
        .accessibilityElement(children: .combine)
    }
}

/// A small line of recent readings.
struct Sparkline: View {
    let values: [Double?]
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let points = values.enumerated().compactMap { i, v in v.map { (i, $0) } }
            guard points.count > 1, let lo = points.map(\.1).min(), let hi = points.map(\.1).max() else { return }
            let span = max(hi - lo, 10)
            let mid = (hi + lo) / 2
            var path = Path()
            for (n, (i, v)) in points.enumerated() {
                let x = size.width * CGFloat(i) / CGFloat(max(values.count - 1, 1))
                let y = size.height * (0.5 - CGFloat((v - mid) / span) * 0.9)
                if n == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
        }
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
    var bars = 18

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

/// A file's picture: the slicer's own, or the one drawn from its G-code, or a quiet placeholder.
struct FilePicture: View {
    let image: UIImage?
    var corner: CGFloat = 12

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: corner, style: .continuous).fill(Ink.panelHi)
            if let image {
                Image(uiImage: image).resizable().scaledToFit().padding(4)
            } else {
                Image(systemName: "cube.transparent").font(.title3).foregroundStyle(Ink.faint)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}
