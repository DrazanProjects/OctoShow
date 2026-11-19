import SwiftUI

/// Switching everything off, asked of the Echo Show display through the OctoShow plugin. The
/// display runs the same careful sequence as from its own power button, and decides everything;
/// this asks, and follows along.
struct PowerSheet: View {
    let session: PrinterSession
    @Environment(\.dismiss) private var dismiss
    @Environment(Prefs.self) private var prefs

    private var display: DisplayStatus? { session.display }
    private var printing: Bool { session.printer?.phase.busy == true }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Switch off").font(.screenTitle).foregroundStyle(Ink.bone)
                    Spacer()
                    if let name = display?.name {
                        Text("through \(name)").font(.meta).foregroundStyle(Ink.faint)
                    }
                }
                if let step = display?.step {
                    progress(step)
                } else if session.cardCopy != nil {
                    // the Pi is copying and the printer is taking it: the Echo Show would refuse
                    Text("A file is going onto the printer’s SD card, so the power stays on until it’s there.")
                        .font(.body).foregroundStyle(Ink.soft)
                } else if printing {
                    whenDone
                } else {
                    now
                }
            }
            .padding(20)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(Ink.panel)
        .presentationDragIndicator(.visible)
        .animation(.smooth, value: display)
    }

    /* ---------------- idle: switch off now ---------------- */

    private var now: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 10) {
                stepLine("flame", "The heaters go off, and the Echo Show waits for the nozzle to cool below 50°.")
                stepLine("cpu", "OctoPrint shuts the Pi down.")
                stepLine("powerplug", "Once the Pi is quiet, the plug cuts the power to the printer and the Pi.")
                if display?.canGoOff == true {
                    stepLine("display", "Then the Echo Show switches itself off too.")
                }
            }
            .panel()
            HoldButton(title: "Hold to switch off", systemImage: "power", color: Ink.bad) {
                session.askDisplay("off")
            }
            Text("To switch back on, start the Echo Show: it powers the printer and the Pi up with it.")
                .font(.label).foregroundStyle(Ink.faint)
        }
    }

    /* ---------------- printing: switch off when done ---------------- */

    private var whenDone: some View {
        VStack(alignment: .leading, spacing: 16) {
            if display?.offWhenDone == true {
                Label("Everything switches off when this print finishes.", systemImage: "checkmark.circle.fill")
                    .font(.heading).foregroundStyle(Ink.ok)
                    .panel()
                Text("A print that is cancelled or fails leaves everything on, so you can look at it.")
                    .font(.label).foregroundStyle(Ink.soft)
                Button { session.askDisplay("stay") } label: { Label("Leave everything on", systemImage: "xmark") }
                    .buttonStyle(QuietButtonStyle())
            } else {
                Text("A print is running, so the power stays on for now. The Echo Show can switch everything off once it finishes, after a 30-second pause in which it can still be stopped.")
                    .font(.body).foregroundStyle(Ink.soft)
                Button { session.askDisplay("offWhenDone") } label: { Label("Switch off when done", systemImage: "power") }
                    .buttonStyle(PrimaryButtonStyle())
                Text("Only a print that finishes switches things off; one that is cancelled or fails leaves everything on.")
                    .font(.label).foregroundStyle(Ink.faint)
            }
        }
    }

    /* ---------------- under way ---------------- */

    private func progress(_ step: PowerStepInfo) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            PowerStepCard(step: step, heard: display?.heard ?? Date(), link: session.link, canGoOff: display?.canGoOff ?? true)
            if step.kind == .failed {
                Button { session.askDisplay("stay") } label: { Text("OK") }
                    .buttonStyle(QuietButtonStyle())
            } else if step.committed || step.kind == .off || session.link != .online {
                // past stopping, or out of touch: nothing to ask the display now
                Text("To switch back on, start the Echo Show: it powers the printer and the Pi up with it.")
                    .font(.label).foregroundStyle(Ink.faint)
            } else if step.canBackOut {
                Button { session.askDisplay("stay") } label: { Label("Leave everything on", systemImage: "xmark") }
                    .buttonStyle(QuietButtonStyle())
            } else {
                Text("The Pi has been told to shut down, so this can't be stopped now.")
                    .font(.label).foregroundStyle(Ink.faint)
            }
        }
    }

    private func stepLine(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.label).foregroundStyle(.tint).frame(width: 22)
            Text(text).font(.body).foregroundStyle(Ink.bone).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// A switch-off in words, with how far along it is. Looked at again every few seconds, since
/// once OctoPrint has gone quiet it is time that tells how it ended.
struct PowerStepCard: View {
    let step: PowerStepInfo
    let heard: Date
    let link: Link
    /// The Echo Show goes off too at the end.
    var canGoOff = true

    var body: some View {
        TimelineView(.periodic(from: .now, by: 5)) { context in
            let story = SwitchOffStory(step: step, heard: heard, online: link == .online, now: context.date)
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    Image(systemName: story.phase.symbol)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(story.phase.tint)
                        .contentTransition(.symbolEffect(.replace))
                    Text(story.title).font(.title)
                        .foregroundStyle(story.phase == .failed || story.phase == .lostTouch ? Ink.warn : Ink.bone)
                        .contentTransition(.opacity)
                }
                if story.phase != .failed {
                    SwitchOffTrack(story: story, step: step, canGoOff: canGoOff)
                        .padding(.vertical, 6)
                }
                Text(story.detail).font(.body).foregroundStyle(Ink.soft).fixedSize(horizontal: false, vertical: true)
            }
            .panel(padding: 18)
            .accessibilityElement(children: .combine)
            .animation(.smooth, value: story)
        }
    }
}

private extension SwitchOffStory.Phase {
    var symbol: String {
        switch self {
        case .running: "power"
        case .done: "checkmark.circle.fill"
        case .lostTouch, .failed: "exclamationmark.triangle.fill"
        }
    }

    var tint: AnyShapeStyle {
        switch self {
        case .running: AnyShapeStyle(.tint)
        case .done: AnyShapeStyle(Ink.ok)
        case .lostTouch, .failed: AnyShapeStyle(Ink.warn)
        }
    }
}

/* ---------------- the switch-off, drawn ---------------- */

/// The switch-off as the things it puts out, going dark in turn: the heaters, the Pi, the power,
/// and the Echo Show itself when it goes off too. The one going dark now breathes, with a ring
/// swelling out from it and a pulse of light running on to the next; the heaters show the nozzle
/// cooling. When several go dark at once (the end, or a phone opened after it's all over), they go
/// out one after another.
struct SwitchOffTrack: View {
    let story: SwitchOffStory
    let step: PowerStepInfo
    let canGoOff: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(Prefs.self) private var prefs
    /// How many are drawn dark. It catches up with `dark` one at a time.
    @State private var shown: Int

    init(story: SwitchOffStory, step: PowerStepInfo, canGoOff: Bool) {
        self.story = story
        self.step = step
        self.canGoOff = canGoOff
        _shown = State(initialValue: Self.dark(story, of: canGoOff ? 4 : 3))
    }

    private struct Stage: Identifiable {
        let id: Int
        let label: String
        let lit: String
        let dark: String
        let color: Color
    }

    private var stages: [Stage] {
        var all = [
            Stage(id: 0, label: "Heaters", lit: "flame.fill", dark: "flame", color: prefs.accent),
            Stage(id: 1, label: "Pi", lit: "cpu.fill", dark: "cpu", color: Ink.ok),
            Stage(id: 2, label: "Power", lit: "bolt.fill", dark: "bolt.slash", color: Ink.warn),
        ]
        if canGoOff { all.append(Stage(id: 3, label: "Echo Show", lit: "display", dark: "display", color: Ink.bed)) }
        return all
    }

    /// How many have gone dark: none until the heaters are off, then one a step, all at the end.
    private static func dark(_ story: SwitchOffStory, of count: Int) -> Int {
        switch story.phase {
        case .done: count
        case .failed: 0
        case .running, .lostTouch: min(max(story.progress - 1, 0), count)
        }
    }

    private var dark: Int { Self.dark(story, of: stages.count) }

    /// The one going dark now; the heaters while it is about to start. None once it's out of touch.
    private var going: Int? {
        story.phase == .running ? max(story.progress - 1, 0) : nil
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(stages) { stage in
                node(stage)
                if stage.id != stages.last?.id {
                    Connector(lit: stage.id >= shown, flowing: going == stage.id && !reduceMotion, color: stage.color)
                        .frame(height: 3)
                        .padding(.top, 21)
                        .padding(.horizontal, -4)
                }
            }
        }
        .task(id: dark) { await catchUp() }
        .sensoryFeedback(.impact(weight: .light), trigger: shown) { old, new in new > old }
        .sensoryFeedback(.success, trigger: story.phase == .done) { _, done in done }
        .accessibilityHidden(true)
    }

    /// Goes dark one at a time to where the switch-off has got.
    private func catchUp() async {
        var first = true
        while shown < dark {
            if !first {
                try? await Task.sleep(for: .milliseconds(reduceMotion ? 120 : 260))
                if Task.isCancelled { return }
            }
            first = false
            withAnimation(reduceMotion ? .easeInOut(duration: 0.25) : .spring(duration: 0.55, bounce: 0.35)) { shown += 1 }
        }
        if shown > dark { withAnimation(.smooth) { shown = dark } }
    }

    private func node(_ stage: Stage) -> some View {
        let isDark = stage.id < shown
        let isGoing = going == stage.id && !isDark
        return VStack(spacing: 6) {
            ZStack {
                Circle()
                    .fill(isDark ? AnyShapeStyle(Ink.panelHi) : AnyShapeStyle(stage.color.opacity(0.16)))
                Circle()
                    .strokeBorder(isDark ? Ink.line : stage.color.opacity(isGoing ? 0.7 : 0.35), lineWidth: 1.5)
                if isGoing && !reduceMotion {
                    GlowRing(color: stage.color)
                }
                Image(systemName: isDark ? stage.dark : stage.lit)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(isDark ? Ink.faint : stage.color)
                    .contentTransition(.symbolEffect(.replace.downUp))
                    .symbolEffect(.breathe.pulse, options: .repeat(.continuous), isActive: isGoing && !reduceMotion)
            }
            .frame(width: 44, height: 44)
            .shadow(color: isDark ? .clear : stage.color.opacity(isGoing ? 0.6 : 0.25), radius: isGoing ? 12 : 6)
            .scaleEffect(isDark ? 0.86 : 1)

            Group {
                if stage.id == 0, step.kind == .cooling, !isDark, let nozzle = step.nozzle {
                    Text("\(nozzle)°")
                        .foregroundStyle(stage.color)
                        .contentTransition(.numericText(countsDown: true))
                        .animation(.smooth, value: nozzle)
                } else {
                    Text(stage.label).foregroundStyle(isDark ? Ink.faint : Ink.soft)
                }
            }
            .font(.meta)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
        }
        .frame(width: 62)
    }
}

/// A soft ring that swells out from a stage as it goes dark.
private struct GlowRing: View {
    let color: Color
    @State private var out = false

    var body: some View {
        Circle()
            .stroke(color.opacity(out ? 0 : 0.6), lineWidth: 2)
            .scaleEffect(out ? 1.55 : 1)
            .onAppear {
                withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) { out = true }
            }
    }
}

/// The line from one stage to the next: lit while the stage before it is, and carrying a pulse of
/// light along it while that stage goes dark.
private struct Connector: View {
    let lit: Bool
    let flowing: Bool
    let color: Color

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Ink.line)
                Capsule().fill(color.opacity(lit ? 0.5 : 0))
                if flowing {
                    TimelineView(.animation) { context in
                        let t = context.date.timeIntervalSinceReferenceDate
                        let x = CGFloat(t.truncatingRemainder(dividingBy: 1.4) / 1.4)
                        Capsule()
                            .fill(LinearGradient(colors: [color.opacity(0), color, .white.opacity(0.9)], startPoint: .leading, endPoint: .trailing))
                            .frame(width: 26)
                            .offset(x: x * (g.size.width + 26) - 26)
                    }
                    .transition(.opacity)
                }
            }
            .clipShape(Capsule())
        }
    }
}
