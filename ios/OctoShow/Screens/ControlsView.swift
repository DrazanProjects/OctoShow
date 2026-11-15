import SwiftUI

/// Heat (with OctoPrint's own presets), moving the head and feeding filament, the part fan and
/// the lights, a section at a time. While a job runs, moving the head and feeding filament are
/// locked, and speed and flow take their place.
struct ControlsView: View {
    let session: PrinterSession
    @Environment(Prefs.self) private var prefs
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var page = Page.heat
    @State private var step = 10.0
    @State private var feed = 10
    @State private var editing: Heater?

    enum Page: Hashable { case heat, move, filament, more }

    enum Heater: String, Identifiable {
        case nozzle, bed
        var id: String { rawValue }
    }

    private var connected: Bool { session.link == .online && (session.printer?.phase ?? .offline) >= .ready }
    private var busy: Bool { session.printer?.phase.busy == true }
    private var hasBed: Bool { session.printer?.hasBed != false }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ScreenHeader(title: "Controls", subtitle: subtitle, session: session)
                Choice(options: [(Page.heat, "Heat"), (.move, busy ? "Tune" : "Move"), (.filament, "Filament"), (.more, "Fan & light")],
                       selected: page) { p in withAnimation(.snappy) { page = p } }
                Group {
                    switch page {
                    case .heat: heat
                    case .move: if busy { tune } else { move }
                    case .filament: filament
                    case .more: fanAndLights
                    }
                }
                .disabled(!connected)
                .opacity(connected ? 1 : 0.55)
                .transition(.opacity)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(Ink.black)
        .sheet(item: $editing) { heater in
            TemperatureSheet(
                label: heater == .nozzle ? "Nozzle" : "Bed",
                temp: heater == .nozzle ? session.printer?.tool : session.printer?.bed,
                maximum: heater == .nozzle ? 300 : 110,
                presets: session.info.presets.prefix(4).map { (heater == .nozzle ? $0.tool : $0.bed, $0.name) },
                color: heater == .nozzle ? prefs.accent : Ink.bed,
                set: heater == .nozzle ? session.setTool : session.setBed
            )
        }
    }

    private var subtitle: String {
        if !connected { return "Connect the printer first" }
        return busy ? "A job is running: moving by hand is locked" : "Ready"
    }

    /* ---------------- heat ---------------- */

    private var heat: some View {
        VStack(spacing: 14) {
            let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                HeaterTile(label: "Nozzle", temp: session.printer?.tool, color: prefs.accent) { editing = .nozzle }
                if hasBed {
                    HeaterTile(label: "Bed", temp: session.printer?.bed, color: Ink.bed) { editing = .bed }
                }
            }
            VStack(alignment: .leading, spacing: 10) {
                Caption("Presets")
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                    ForEach(session.info.presets.prefix(4), id: \.self) { p in
                        Button { session.preheat(p) } label: {
                            VStack(spacing: 2) {
                                Text(p.name).font(.heading).foregroundStyle(Ink.bone).lineLimit(1)
                                Text(hasBed ? "\(p.tool)° · \(p.bed)°" : "\(p.tool)°")
                                    .font(.meta).foregroundStyle(Ink.soft).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, minHeight: 58)
                            .background(Ink.panelHi, in: .rect(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(PressStyle())
                    }
                    Button(action: session.coolDown) {
                        VStack(spacing: 2) {
                            Image(systemName: "snowflake").font(.heading)
                            Text("Cool down").font(.meta).lineLimit(1)
                        }
                        .foregroundStyle(Ink.bed)
                        .frame(maxWidth: .infinity, minHeight: 58)
                        .background(Ink.panelHi, in: .rect(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(PressStyle())
                }
                if session.info.presets.isEmpty {
                    Text("Add presets in OctoPrint's settings, under Temperatures, to heat with one tap.")
                        .font(.label).foregroundStyle(Ink.faint)
                }
            }
            .panel()
        }
    }

    /* ---------------- moving the head ---------------- */

    private var move: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .center, spacing: 22) {
                // X and Y as a cross, homing in the middle; Z to the side
                Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                    GridRow {
                        Color.clear.frame(width: 1, height: 1)
                        Jog(icon: "chevron.up", label: "Back") { session.jog(y: step) }
                        Color.clear.frame(width: 1, height: 1)
                    }
                    GridRow {
                        Jog(icon: "chevron.left", label: "Left") { session.jog(x: -step) }
                        Jog(icon: "house.fill", label: "Home X and Y", tint: Ink.soft) { session.home(["x", "y"]) }
                        Jog(icon: "chevron.right", label: "Right") { session.jog(x: step) }
                    }
                    GridRow {
                        Color.clear.frame(width: 1, height: 1)
                        Jog(icon: "chevron.down", label: "Forward") { session.jog(y: -step) }
                        Color.clear.frame(width: 1, height: 1)
                    }
                }
                VStack(spacing: 10) {
                    Jog(icon: "arrow.up", label: "Z up") { session.jog(z: min(step, 10)) }
                    Text("Z").font(.meta).foregroundStyle(Ink.faint)
                    Jog(icon: "arrow.down", label: "Z down") { session.jog(z: -min(step, 10)) }
                }
            }
            .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 8) {
                Caption("Distance per tap, mm")
                Choice(options: [(0.1, "0.1"), (1.0, "1"), (10.0, "10"), (50.0, "50")], selected: step) { step = $0 }
            }
            VStack(alignment: .leading, spacing: 8) {
                Caption("Speed")
                Choice(options: JogSpeed.allCases.map { ($0, $0.label) }, selected: prefs.jogSpeed) { prefs.jogSpeed = $0 }
            }
            HStack(spacing: 10) {
                QuietButton(title: "Home all", systemImage: "house.fill", height: 48) { session.home(["x", "y", "z"]) }
                QuietButton(title: "Motors off", systemImage: "power", color: Ink.soft, height: 48, action: session.motorsOff)
            }
        }
        .panel(padding: 18)
    }

    /* ---------------- speed and flow, while printing ---------------- */

    private var tune: some View {
        VStack(alignment: .leading, spacing: 16) {
            TuneRow(label: "Speed", detail: "How fast the head moves", value: session.feedRate, range: 50...150, set: session.changeFeedRate)
            Divider().overlay(Ink.line)
            TuneRow(label: "Flow", detail: "How much plastic goes down", value: session.flowRate, range: 75...125, set: session.changeFlowRate)
        }
        .panel(padding: 18)
    }

    /* ---------------- filament ---------------- */

    private var filament: some View {
        let nozzle = session.printer?.tool?.actual ?? 0
        let hot = nozzle >= 170
        let canFeed = hot && !busy
        let warmTo = session.info.presets.first?.tool ?? 200
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                QuietButton(title: "Retract", systemImage: "arrow.up", height: 60, enabled: canFeed) { session.extrude(-feed) }
                QuietButton(title: "Extrude", systemImage: "arrow.down", height: 60, enabled: canFeed) { session.extrude(feed) }
            }
            if busy {
                Label("Locked while a job is running.", systemImage: "lock.fill").font(.label).foregroundStyle(Ink.faint)
            } else if !hot {
                HStack(spacing: 12) {
                    Text("The nozzle is at \(degrees(session.printer?.tool?.actual)). It needs 170° or more to feed filament.")
                        .font(.label).foregroundStyle(Ink.soft)
                    Spacer(minLength: 0)
                    Button("Heat to \(warmTo)°") { session.setTool(warmTo) }
                        .font(.label)
                        .buttonStyle(QuietButtonStyle(color: prefs.accent, height: 40))
                        .frame(width: 130)
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                Caption("Length")
                Choice(options: [(5, "5 mm"), (10, "10 mm"), (50, "50 mm")], selected: feed) { feed = $0 }
            }
            VStack(alignment: .leading, spacing: 8) {
                Caption("Speed")
                Choice(options: [(60, "1 mm/s"), (120, "2 mm/s"), (300, "5 mm/s")], selected: prefs.extrudeSpeed) { prefs.extrudeSpeed = $0 }
            }
        }
        .panel(padding: 18)
    }

    /* ---------------- fan and lights ---------------- */

    private var fanAndLights: some View {
        let f = session.layers.fanPercent
        return VStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label("Part fan", systemImage: "fan").font(.title).foregroundStyle(Ink.bone)
                    Spacer()
                    if let f { Text("\(f)%").font(.meta).foregroundStyle(Ink.faint).monospacedDigit() }
                }
                Choice(options: [(0, "Off"), (50, "Half"), (100, "Full")],
                       selected: f.map { $0 == 0 ? 0 : (40...60).contains($0) ? 50 : $0 >= 99 ? 100 : -1 }) { session.setFan($0) }
            }
            .panel(padding: 18)
            if !session.info.lights.isEmpty {
                VStack(alignment: .leading, spacing: 14) {
                    Label("Lights", systemImage: "lightbulb").font(.title).foregroundStyle(Ink.bone)
                    ForEach(session.info.lights) { light in
                        VStack(alignment: .leading, spacing: 8) {
                            Caption(light.name)
                            if light.dimmable {
                                Choice(options: [(0, "Off"), (25, "Low"), (60, "Medium"), (100, "Full")],
                                       selected: nearestLevel(light.percent)) { session.setLight(light, percent: $0) }
                            } else {
                                Choice(options: [(0, "Off"), (100, "On")], selected: light.percent > 0 ? 100 : 0) {
                                    session.setLight(light, percent: $0)
                                }
                            }
                        }
                    }
                }
                .panel(padding: 18)
            }
        }
    }

    private func nearestLevel(_ p: Int) -> Int {
        [0, 25, 60, 100].min { abs($0 - p) < abs($1 - p) } ?? 0
    }
}

/// A heater at a glance; tapping it opens the temperature picker.
private struct HeaterTile: View {
    let label: String
    let temp: Temp?
    let color: Color
    let edit: () -> Void

    var body: some View {
        Button(action: edit) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Caption(label)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down").font(.meta).foregroundStyle(Ink.faint)
                }
                Text(degrees(temp?.actual))
                    .font(.reading).foregroundStyle(Ink.bone).monospacedDigit()
                    .contentTransition(.numericText(value: temp?.actual ?? 0))
                    .animation(.smooth, value: temp?.actual)
                Text(temp?.on == true ? "Target \(degrees(temp?.target))" : "Off")
                    .font(.meta)
                    .foregroundStyle(temp?.heating == true ? color : Ink.soft)
            }
            .panel(padding: 14)
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel("\(label), \(degrees(temp?.actual)), \(temp?.on == true ? "target \(degrees(temp?.target))" : "off")")
        .accessibilityHint("Choose a temperature")
    }
}

/// Choosing a heater's temperature: a wheel, the presets, and one button to send it.
private struct TemperatureSheet: View {
    let label: String
    let temp: Temp?
    let maximum: Int
    let presets: [(Int, String)]
    let color: Color
    let set: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text(label).font(.title).foregroundStyle(Ink.bone)
                Spacer()
                Text("now \(degrees(temp?.actual))").font(.meta).foregroundStyle(Ink.soft)
            }
            Picker("Temperature", selection: $value) {
                ForEach(Array(stride(from: 0, through: maximum, by: 5)), id: \.self) { t in
                    Text(t == 0 ? "Off" : "\(t)°").tag(t)
                }
            }
            .pickerStyle(.wheel)
            .frame(height: 150)
            if !presets.isEmpty {
                HStack(spacing: 8) {
                    ForEach(presets, id: \.1) { t, name in
                        Button { value = t } label: {
                            VStack(spacing: 1) {
                                Text(name).font(.label).lineLimit(1)
                                Text("\(t)°").font(.meta).foregroundStyle(Ink.soft)
                            }
                            .foregroundStyle(value == t ? color : Ink.bone)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(Ink.panelHi, in: .rect(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(PressStyle())
                    }
                }
            }
            Button {
                set(value)
                dismiss()
            } label: {
                Text(value == 0 ? "Turn \(label.lowercased()) off" : "Heat \(label.lowercased()) to \(value)°")
            }
            .buttonStyle(PrimaryButtonStyle())
        }
        .padding(20)
        .presentationDetents([.medium, .large])
        .presentationBackground(Ink.panel)
        .onAppear { value = Int(((temp?.target ?? 0) / 5).rounded()) * 5 }
    }
}

/// One square button of the jog pad.
private struct Jog: View {
    let icon: String
    let label: String
    var tint: Color = Ink.bone
    let action: () -> Void
    @State private var taps = 0

    var body: some View {
        Button {
            taps += 1
            action()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 64, height: 64)
                .background(Ink.panelHi, in: .rect(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(PressStyle())
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        .accessibilityLabel(label)
    }
}

/// Speed or flow during a print, as a percentage of what the file says.
private struct TuneRow: View {
    let label: String
    let detail: String
    let value: Int
    let range: ClosedRange<Int>
    let set: (Int) -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.heading).foregroundStyle(Ink.bone)
                Text(detail).font(.meta).foregroundStyle(Ink.faint)
            }
            Spacer(minLength: 8)
            RoundStep(icon: "minus", enabled: value > range.lowerBound, label: "Less \(label.lowercased())") { set(value - 5) }
            Text("\(value)%")
                .font(.reading).foregroundStyle(Ink.bone).monospacedDigit()
                .contentTransition(.numericText(value: Double(value)))
                .animation(.snappy, value: value)
                .frame(minWidth: 86)
            RoundStep(icon: "plus", enabled: value < range.upperBound, label: "More \(label.lowercased())") { set(value + 5) }
        }
        .accessibilityElement(children: .contain)
    }
}

private struct RoundStep: View {
    let icon: String
    let enabled: Bool
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(enabled ? Ink.bone : Ink.faint)
                .frame(width: 44, height: 44)
                .background(Ink.panelHi, in: Circle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}
