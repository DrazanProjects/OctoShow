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
            PowerStepCard(step: step, link: session.link)
            if step.kind == .failed {
                Button { session.askDisplay("stay") } label: { Text("OK") }
                    .buttonStyle(QuietButtonStyle())
            } else if step.kind == .off {
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

/// A switch-off step in words, with how far along the sequence it is.
struct PowerStepCard: View {
    let step: PowerStepInfo
    let link: Link

    private var order: Int {
        switch step.kind {
        case .confirm, .afterPrint, .failed: 0
        case .cooling: 1
        case .shuttingDown: 2
        case .settling: 3
        case .off: 4
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                if step.kind != .failed && step.kind != .off { ProgressView().tint(.accentColor) }
                Text(title).font(.title).foregroundStyle(step.kind == .failed ? Ink.warn : Ink.bone)
            }
            Text(detail).font(.body).foregroundStyle(Ink.soft).fixedSize(horizontal: false, vertical: true)
            if step.kind != .failed {
                HStack(spacing: 4) {
                    ForEach(1...4, id: \.self) { i in
                        Capsule().fill(i <= order ? AnyShapeStyle(.tint) : AnyShapeStyle(Ink.line)).frame(height: 4)
                    }
                }
                .accessibilityHidden(true)
            }
        }
        .panel(padding: 18)
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        switch step.kind {
        case .confirm: "Waiting on the Echo Show"
        case .afterPrint: "Print done: switching off"
        case .cooling: "Letting the nozzle cool"
        case .shuttingDown: "Shutting down the Pi"
        case .settling: "The Pi has stopped"
        case .off: "Everything is off"
        case .failed: "Left on"
        }
    }

    private var detail: String {
        switch step.kind {
        case .confirm:
            return "Someone pressed its power button, and it's waiting for the hold that confirms it."
        case .afterPrint:
            return "Starting in \(step.seconds ?? 0) s, unless you leave everything on."
        case .cooling:
            return "The heaters are off. The nozzle is at \(step.nozzle.map { "\($0)°" } ?? "–"); the Pi shuts down once it is below 50°."
        case .shuttingDown:
            return link == .online ? "OctoPrint has been told to shut the Pi down."
                : "OctoPrint has gone quiet as the Pi shuts down. The Echo Show cuts the power once it is sure the Pi has stopped."
        case .settling:
            return "Nothing answers at the Pi's address any more. The power goes in \(step.seconds ?? 0) s."
        case .off:
            return "The plug has cut the power to the printer and the Pi."
        case .failed:
            return step.message ?? "Something stopped it, so everything was left on."
        }
    }
}
