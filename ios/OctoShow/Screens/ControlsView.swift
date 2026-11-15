import SwiftUI

/// Heat (with OctoPrint's own presets), moving the head and feeding filament, the part fan
/// and the lights. While a job runs, moving the head and feeding filament are locked, and
/// speed and flow take their place.
struct ControlsView: View {
    let session: PrinterSession
    @Environment(Prefs.self) private var prefs
    @State private var step = 10.0
    @State private var feed = 10

    private var connected: Bool { session.link == .online && (session.printer?.phase ?? .offline) >= .ready }
    private var busy: Bool { session.printer?.phase.busy == true }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    heat
                    if busy { tune } else { move }
                    filament
                    fan
                    if !session.info.lights.isEmpty { lights }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
                .disabled(!connected)
                .opacity(connected ? 1 : 0.55)
            }
            .background(Ink.black)
            .navigationTitle("Controls")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { ViaBadge(session: session) } }
        }
    }

    /* ---------------- heat ---------------- */

    private var heat: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Heat").font(.title).foregroundStyle(Ink.bone)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach(session.info.presets.prefix(4), id: \.self) { p in
                    Button { session.preheat(p) } label: {
                        VStack(spacing: 2) {
                            Text(p.name).font(.label).foregroundStyle(Ink.bone).lineLimit(1)
                            Text(session.printer?.hasBed == false ? "\(p.tool)°" : "\(p.tool)° / \(p.bed)°")
                                .font(.meta).foregroundStyle(Ink.soft).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(PressStyle())
                }
                Button { session.coolDown() } label: {
                    VStack(spacing: 2) {
                        Image(systemName: "snowflake").font(.label)
                        Text("Cool down").font(.label).lineLimit(1)
                    }
                    .foregroundStyle(Ink.bed)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(PressStyle())
            }
            TargetRow(label: "Nozzle", temp: session.printer?.tool, color: prefs.accent, range: 0...300, step: 5,
                      set: session.setTool)
            if session.printer?.hasBed != false {
                TargetRow(label: "Bed", temp: session.printer?.bed, color: Ink.bed, range: 0...110, step: 5, set: session.setBed)
            }
        }
        .panel()
    }

    /* ---------------- moving the head ---------------- */

    private var move: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Move").font(.title).foregroundStyle(Ink.bone)
            HStack(alignment: .center, spacing: 18) {
                // X and Y as a cross; Z to the side
                Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                    GridRow {
                        Color.clear.frame(width: 1, height: 1)
                        Jog(icon: "chevron.up", label: "Back") { session.jog(y: step) }
                        Color.clear.frame(width: 1, height: 1)
                    }
                    GridRow {
                        Jog(icon: "chevron.left", label: "Left") { session.jog(x: -step) }
                        Jog(icon: "house.fill", label: "Home X and Y") { session.home(["x", "y"]) }
                        Jog(icon: "chevron.right", label: "Right") { session.jog(x: step) }
                    }
                    GridRow {
                        Color.clear.frame(width: 1, height: 1)
                        Jog(icon: "chevron.down", label: "Forward") { session.jog(y: -step) }
                        Color.clear.frame(width: 1, height: 1)
                    }
                }
                VStack(spacing: 8) {
                    Jog(icon: "arrow.up", label: "Z up") { session.jog(z: min(step, 10)) }
                    Jog(icon: "house", label: "Home Z") { session.home(["z"]) }
                    Jog(icon: "arrow.down", label: "Z down") { session.jog(z: -min(step, 10)) }
                }
            }
            .frame(maxWidth: .infinity)
            Caption("Distance per tap, mm")
            Choice(options: [(0.1, "0.1"), (1.0, "1"), (10.0, "10"), (50.0, "50")], selected: step) { step = $0 }
            Caption("Speed")
            Choice(options: JogSpeed.allCases.map { ($0, $0.label) }, selected: prefs.jogSpeed) { prefs.jogSpeed = $0 }
            HStack(spacing: 10) {
                QuietButton(title: "Home all", systemImage: "house.fill", height: 46) { session.home(["x", "y", "z"]) }
                QuietButton(title: "Motors off", systemImage: "power", color: Ink.soft, height: 46) { session.motorsOff() }
            }
        }
        .panel()
    }

    /* ---------------- speed and flow, while printing ---------------- */

    private var tune: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("While printing").font(.title).foregroundStyle(Ink.bone)
            Text("Moving the head by hand is locked while a job is running.").font(.label).foregroundStyle(Ink.faint)
            TuneRow(label: "Speed", value: session.feedRate, range: 50...150, set: session.changeFeedRate)
            TuneRow(label: "Flow", value: session.flowRate, range: 75...125, set: session.changeFlowRate)
        }
        .panel()
    }

    /* ---------------- filament ---------------- */

    private var filament: some View {
        let hot = (session.printer?.tool?.actual ?? 0) >= 170
        let canFeed = hot && !busy
        return VStack(alignment: .leading, spacing: 12) {
            Text("Filament").font(.title).foregroundStyle(Ink.bone)
            HStack(spacing: 10) {
                QuietButton(title: "Retract", systemImage: "arrow.up", height: 50, enabled: canFeed) { session.extrude(-feed) }
                QuietButton(title: "Extrude", systemImage: "arrow.down", height: 50, enabled: canFeed) { session.extrude(feed) }
            }
            if !canFeed {
                Text(busy ? "Locked while a job is running." : "Heat the nozzle to 170° or more first.")
                    .font(.label).foregroundStyle(Ink.faint)
            }
            Caption("Length")
            Choice(options: [(5, "5 mm"), (10, "10 mm"), (50, "50 mm")], selected: feed) { feed = $0 }
            Caption("Speed")
            Choice(options: [(60, "1 mm/s"), (120, "2 mm/s"), (300, "5 mm/s")], selected: prefs.extrudeSpeed) { prefs.extrudeSpeed = $0 }
        }
        .panel()
    }

    /* ---------------- fan and lights ---------------- */

    private var fan: some View {
        let f = session.layers.fanPercent
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Part fan").font(.title).foregroundStyle(Ink.bone)
                Spacer()
                if let f { Text("\(f)%").font(.meta).foregroundStyle(Ink.faint) }
            }
            Choice(options: [(0, "Off"), (50, "Half"), (100, "Full")],
                   selected: f.map { $0 == 0 ? 0 : (40...60).contains($0) ? 50 : $0 >= 99 ? 100 : -1 }) { session.setFan($0) }
        }
        .panel()
    }

    private var lights: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Lights").font(.title).foregroundStyle(Ink.bone)
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
        .panel()
    }

    private func nearestLevel(_ p: Int) -> Int {
        [0, 25, 60, 100].min { abs($0 - p) < abs($1 - p) } ?? 0
    }
}

/// One square button of the jog pad.
private struct Jog: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Ink.bone)
                .frame(width: 58, height: 58)
                .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }
}

/// A heater: where it is, where it's heading, and a target to send.
private struct TargetRow: View {
    let label: String
    let temp: Temp?
    let color: Color
    let range: ClosedRange<Int>
    let step: Int
    let set: (Int) -> Void
    @State private var target: Int?

    var body: some View {
        let shown = target ?? Int(temp?.target ?? 0)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Caption(label)
                Spacer()
                Text(degrees(temp?.actual)).font(.reading).foregroundStyle(Ink.bone).monospacedDigit().fixedSize()
                if temp?.on == true {
                    Text("→ \(degrees(temp?.target))").font(.meta).foregroundStyle(temp?.heating == true ? color : Ink.soft).fixedSize()
                }
            }
            HStack(spacing: 12) {
                Text(shown == 0 ? "Off" : "\(shown)°")
                    .font(.heading).foregroundStyle(target == nil ? Ink.soft : Ink.bone).monospacedDigit()
                    .frame(minWidth: 52, alignment: .leading)
                Stepper("Target", value: Binding(get: { shown }, set: { target = $0 }), in: range, step: step)
                    .labelsHidden()
                Spacer()
                Button(target == nil ? "Set" : "Set \(shown == 0 ? "off" : "\(shown)°")") {
                    set(shown)
                    target = nil
                }
                .font(.button)
                .buttonStyle(.borderedProminent)
                .foregroundStyle(Ink.black)
                .disabled(target == nil)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// Speed or flow during a print, as a percentage of what the file says.
private struct TuneRow: View {
    let label: String
    let value: Int
    let range: ClosedRange<Int>
    let set: (Int) -> Void

    var body: some View {
        HStack {
            Caption(label)
            Spacer()
            Button { set(value - 5) } label: { Image(systemName: "minus") }
                .buttonStyle(.bordered).disabled(value <= range.lowerBound)
                .accessibilityLabel("Less \(label.lowercased())")
            Text("\(value)%").font(.reading).foregroundStyle(Ink.bone).monospacedDigit().frame(minWidth: 92)
            Button { set(value + 5) } label: { Image(systemName: "plus") }
                .buttonStyle(.bordered).disabled(value >= range.upperBound)
                .accessibilityLabel("More \(label.lowercased())")
        }
    }
}
