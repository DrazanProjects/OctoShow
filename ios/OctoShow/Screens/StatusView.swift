import SwiftUI

/// The screen the app lives on: the part (or the camera), how long is left, the heat, and
/// pause and cancel. Cancel has to be held, so a bumped phone can't end an eight-hour print.
struct StatusView: View {
    let session: PrinterSession
    let goToFiles: () -> Void
    @Environment(Prefs.self) private var prefs

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    LinkBanner(session: session)
                    picture
                    summary
                    heat
                    actions
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .refreshable { session.reconnectNow() }
            .background(Ink.black)
            .navigationTitle(session.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { ViaBadge(session: session) }
            }
        }
    }

    private var phase: Phase? { session.printer?.phase }

    /* ---------------- the part or the camera ---------------- */

    @ViewBuilder
    private var picture: some View {
        let cam = session.info.webcam
        let showCamera = prefs.showCamera && cam != nil
        // top-left: the part drawing keeps the top-right corner for its turn button
        ZStack(alignment: .topLeading) {
            Group {
                if showCamera, let cam {
                    CameraView(session: session, webcam: cam)
                } else {
                    PartPanel(session: session)
                }
            }
            .aspectRatio(4 / 3, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            if cam != nil {
                HStack(spacing: 6) {
                    if showCamera, let light = session.info.lights.first {
                        PanelToggle(systemImage: light.percent > 0 ? "lightbulb.fill" : "lightbulb", on: light.percent > 0,
                                    label: light.percent > 0 ? "Light off" : "Light on") {
                            session.setLight(light, percent: light.percent > 0 ? 0 : 100)
                        }
                    }
                    PanelToggle(systemImage: showCamera ? "cube.fill" : "video.fill", on: false,
                                label: showCamera ? "Show the part" : "Show the camera") {
                        prefs.showCamera.toggle()
                    }
                }
                .padding(10)
            }
        }
    }

    /* ---------------- how it's going ---------------- */

    @ViewBuilder
    private var summary: some View {
        if let o = session.outcome, !(phase?.busy ?? false) {
            finished(o)
        } else if let p = session.printer, p.phase.busy {
            printing(p)
        } else if let p = session.printer, p.phase == .ready {
            ready
        } else if let p = session.printer, p.phase == .offline || p.phase == .error {
            offline(p)
        } else if session.link == .online {
            HStack { ProgressView(); Text("Connecting the printer…").font(.body).foregroundStyle(Ink.soft) }.panel()
        }
    }

    private func printing(_ p: PrinterSnapshot) -> some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Caption(stateWord(p.phase))
                if let left = p.printTimeLeft, p.phase != .starting {
                    HStack(alignment: .firstTextBaseline, spacing: 2) {
                        ForEach(Array(durationParts(left).enumerated()), id: \.offset) { _, part in
                            Text(part.0).font(.hero).foregroundStyle(Ink.bone).monospacedDigit()
                            Text(part.1).font(.heroUnit).foregroundStyle(Ink.soft).padding(.trailing, 6)
                        }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(duration(left)) left")
                    Text("left · done \(whenIs(Date().addingTimeInterval(TimeInterval(left))))")
                        .font(.label).foregroundStyle(Ink.soft)
                } else {
                    Text(p.phase == .starting ? "Heating up" : "Working it out")
                        .font(.heroWord).foregroundStyle(Ink.bone)
                }
                if let f = p.file {
                    Text(prettyName(f.display)).font(.heading).foregroundStyle(Ink.bone).lineLimit(2).padding(.top, 6)
                }
                HStack(spacing: 10) {
                    Text("\(Int((p.fraction * 100).rounded()))%")
                    if session.layers.known, let l = session.layers.current, let t = session.layers.total {
                        Text("layer \(l) of \(t)")
                    }
                    if let z = p.currentZ, z > 0 { Text(String(format: "Z %.2f", z)) }
                }
                .font(.meta).foregroundStyle(Ink.soft).monospacedDigit()
            }
            Spacer(minLength: 0)
            LayerStack(fraction: p.fraction, layer: session.layers.known ? session.layers.current : nil,
                       total: session.layers.known ? session.layers.total : nil,
                       layerProgress: session.layers.layerProgress, filament: prefs.filament, hot: prefs.accent)
                .frame(width: 56)
                .padding(.top, 6)
        }
        .panel(padding: 18)
    }

    private var ready: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Ready").font(.heroWord).foregroundStyle(Ink.bone)
            Text("Nothing printing.").font(.body).foregroundStyle(Ink.soft)
            if let newest = session.files.newestFirst.first {
                Button(action: goToFiles) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Caption("Newest upload")
                            Text(prettyName(newest.display)).font(.heading).foregroundStyle(Ink.bone).lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Ink.faint)
                    }
                    .padding(14)
                    .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(PressStyle())
            }
        }
        .panel(padding: 18)
        .onAppear { session.wantFiles() }
    }

    private func finished(_ o: Outcome) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(o.kind == .done ? "Finished" : o.kind == .failed ? "Failed" : "Cancelled")
                .font(.heroWord)
                .foregroundStyle(o.kind == .done ? Ink.ok : o.kind == .failed ? Ink.bad : Ink.bone)
            if !o.fileName.isEmpty {
                Text(prettyName(o.fileName)).font(.heading).foregroundStyle(Ink.bone)
            }
            Text(o.kind == .done
                 ? (o.seconds.map { "Took \(duration($0)). Clear the bed before the next one." } ?? "Clear the bed before the next one.")
                 : "Check the printer before trying again.")
                .font(.body).foregroundStyle(Ink.soft)
            QuietButton(title: o.kind == .done ? "Bed is clear" : "Done", systemImage: "checkmark", color: Ink.bone) {
                session.dismissOutcome()
            }
            .padding(.top, 4)
        }
        .panel(padding: 18)
    }

    private func offline(_ p: PrinterSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(p.phase == .error ? "Printer error" : "Printer offline").font(.heroWord).foregroundStyle(Ink.bone)
            Text(p.errorText ?? (p.phase == .error ? p.stateText : "OctoPrint isn't connected to the printer."))
                .font(.body).foregroundStyle(Ink.soft)
            QuietButton(title: "Connect printer", systemImage: "cable.connector", color: Ink.bone) { session.connectPrinter() }
                .padding(.top, 4)
        }
        .panel(padding: 18)
    }

    /* ---------------- heat ---------------- */

    @ViewBuilder
    private var heat: some View {
        if let p = session.printer, p.tool != nil || p.bed != nil {
            HStack(spacing: 12) {
                HeatReading(label: "Nozzle", temp: p.tool, trace: session.temps.suffix(90).map(\.tool), color: prefs.accent)
                if p.hasBed {
                    HeatReading(label: "Bed", temp: p.bed, trace: session.temps.suffix(90).map(\.bed), color: Ink.bed)
                }
            }
        }
    }

    /* ---------------- pause and cancel ---------------- */

    @ViewBuilder
    private var actions: some View {
        if let p = session.printer, p.phase.busy {
            HStack(spacing: 12) {
                if p.phase == .paused {
                    QuietButton(title: "Resume", systemImage: "play.fill", color: prefs.accent) { session.resume() }
                } else {
                    QuietButton(title: "Pause", systemImage: "pause.fill", enabled: p.phase.canPause) { session.pause() }
                }
                HoldButton(title: "Hold to cancel", systemImage: "xmark", enabled: p.phase.canCancel) { session.cancel() }
            }
        }
    }

    private func stateWord(_ phase: Phase) -> String {
        switch phase {
        case .starting: "Starting"
        case .printing: "Printing"
        case .pausing: "Pausing"
        case .paused: "Paused"
        case .resuming: "Resuming"
        case .cancelling: "Cancelling"
        case .finishing: "Finishing"
        default: ""
        }
    }
}

/// A round button laid over the picture.
struct PanelToggle: View {
    let systemImage: String
    let on: Bool
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(on ? Ink.black : Ink.bone)
                .frame(width: 38, height: 38)
                .background(on ? AnyShapeStyle(Ink.warn) : AnyShapeStyle(.black.opacity(0.55)), in: Circle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }
}

/// Home, away, or not reaching it at all.
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
            Text(text).font(.meta).foregroundStyle(Ink.soft)
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Ink.panelHi, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(session.link == .online ? "Connected \(session.via == .home ? "at home" : "from away")" : text)
    }
}

/// Why OctoPrint isn't answering, when it isn't.
struct LinkBanner: View {
    let session: PrinterSession
    @Environment(AppModel.self) private var model

    var body: some View {
        switch session.link {
        case .unreachable:
            banner("Can’t reach OctoPrint", session.server.away == nil
                   ? "Check the Pi is on. Away from home, set up an away address in Settings."
                   : "Neither the home nor the away address is answering.", Ink.warn)
        case .unauthorized:
            VStack(alignment: .leading, spacing: 10) {
                banner("OctoPrint turned down this phone’s key", "It may have been revoked in OctoPrint's settings. Set up again to get a new one.", Ink.bad)
                QuietButton(title: "Set up again", height: 46) { model.forgetServer() }
            }
        case .untrusted:
            VStack(alignment: .leading, spacing: 10) {
                banner("The Pi’s certificate changed", "If you reinstalled the Pi, set up again. If not, something else may be answering in its place.", Ink.bad)
                QuietButton(title: "Set up again", height: 46) { model.forgetServer() }
            }
        default:
            EmptyView()
        }
    }

    private func banner(_ title: String, _ detail: String, _ color: Color) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(color)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.heading).foregroundStyle(Ink.bone)
                Text(detail).font(.label).foregroundStyle(Ink.soft)
            }
        }
        .panel()
    }
}
