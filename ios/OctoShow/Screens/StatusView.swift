import SwiftUI

/// The screen the app lives on: the part (or the camera), how long is left, the heat, and pause
/// and cancel, which stay pinned above the tabs while a job runs. Cancel has to be held, so a
/// bumped phone can't end an eight-hour print.
struct StatusView: View {
    let session: PrinterSession
    let goToFiles: () -> Void
    @Environment(Prefs.self) private var prefs
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var fullScreen = false
    @State private var powerSheet = false

    private var phase: Phase? { session.printer?.phase }
    private var busy: Bool { phase?.busy == true }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ScreenHeader(title: session.displayName, subtitle: subtitle, session: session,
                             power: session.canSwitchOff ? { powerSheet = true } : nil)
                LinkBanner(session: session)
                switchingOff
                PicturePanel(session: session, expand: { fullScreen = true })
                summary
                    .animation(.smooth, value: phase)
                heat
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .refreshable { session.reconnectNow() }
        .background(Ink.black)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let p = session.printer, p.phase.busy {
                JobActions(session: session, phase: p.phase)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.smooth, value: busy)
        .fullScreenCover(isPresented: $fullScreen) {
            FullScreenPicture(session: session)
        }
        .sheet(isPresented: $powerSheet) { PowerSheet(session: session) }
    }

    /// The Echo Show switching everything off, or set to once the print is done.
    @ViewBuilder
    private var switchingOff: some View {
        if let step = session.display?.step {
            Button { powerSheet = true } label: { PowerStepCard(step: step, link: session.link) }
                .buttonStyle(PressStyle())
                .transition(.move(edge: .top).combined(with: .opacity))
        } else if session.display?.offWhenDone == true, busy {
            Button { powerSheet = true } label: {
                Label("Everything switches off when this print is done", systemImage: "power")
                    .font(.label).foregroundStyle(Ink.bone)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Ink.panel, in: .rect(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Ink.line.opacity(0.55), lineWidth: 0.5))
            }
            .buttonStyle(PressStyle())
        }
    }

    /// What the printer is doing, in a few words, under the printer's name.
    private var subtitle: String? {
        if let step = session.display?.step, step.kind != .failed {
            return step.kind == .off ? "Switched off" : "Switching off"
        }
        guard session.link == .online, let p = session.printer else { return nil }
        switch p.phase {
        case .printing: return "Printing · \(Int((p.fraction * 100).rounded()))%"
        case .starting: return "Starting a print"
        case .paused, .pausing: return "Paused"
        case .resuming: return "Resuming"
        case .cancelling: return "Cancelling"
        case .finishing: return "Finishing"
        case .ready: return session.outcome == nil ? "Ready" : "Finished"
        case .offline: return "Printer offline"
        case .error: return "Printer error"
        case .connecting: return "Connecting the printer"
        }
    }

    /* ---------------- how it's going ---------------- */

    @ViewBuilder
    private var summary: some View {
        if let o = session.outcome, !busy {
            FinishedCard(outcome: o, dismiss: session.dismissOutcome)
        } else if let p = session.printer, p.phase.busy {
            JobCard(session: session, printer: p)
        } else if let p = session.printer, p.phase == .ready {
            ReadyCard(newest: session.files.newestFirst.first, goToFiles: goToFiles)
                .onAppear { session.wantFiles() }
        } else if let p = session.printer, p.phase == .offline || p.phase == .error {
            OfflineCard(printer: p, connect: session.connectPrinter)
        } else if session.link == .online {
            HStack(spacing: 12) {
                ProgressView()
                Text("Connecting the printer…").font(.body).foregroundStyle(Ink.soft)
            }
            .panel()
        }
    }

    /* ---------------- heat ---------------- */

    @ViewBuilder
    private var heat: some View {
        if let p = session.printer, p.tool != nil || p.bed != nil {
            let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                HeatCard(label: "Nozzle", temp: p.tool, trace: session.temps.suffix(90).map(\.tool), color: prefs.accent)
                if p.hasBed {
                    HeatCard(label: "Bed", temp: p.bed, trace: session.temps.suffix(90).map(\.bed), color: Ink.bed)
                }
            }
        }
    }
}

/* ---------------- the job ---------------- */

/// Time left set large, when it'll be done, the file, and how far through.
private struct JobCard: View {
    let session: PrinterSession
    let printer: PrinterSnapshot
    @Environment(Prefs.self) private var prefs

    var body: some View {
        let layers = session.layers
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    if let left = printer.printTimeLeft, printer.phase != .starting {
                        HStack(alignment: .firstTextBaseline, spacing: 2) {
                            ForEach(Array(durationParts(left).enumerated()), id: \.offset) { _, part in
                                Text(part.0).font(.hero).foregroundStyle(Ink.bone).monospacedDigit()
                                    .contentTransition(.numericText(countsDown: true))
                                Text(part.1).font(.heroUnit).foregroundStyle(Ink.soft).padding(.trailing, 6)
                            }
                        }
                        .animation(.smooth, value: left / 60)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(duration(left)) left")
                        Text("left · done \(whenIs(Date().addingTimeInterval(TimeInterval(left))))")
                            .font(.label).foregroundStyle(Ink.soft)
                    } else {
                        Text(printer.phase == .starting ? "Heating up" : "Working it out")
                            .font(.heroWord).foregroundStyle(Ink.bone)
                            .lineLimit(1).minimumScaleFactor(0.6)
                    }
                    if let f = printer.file {
                        Text(prettyName(f.display)).font(.title).foregroundStyle(Ink.bone)
                            .lineLimit(3)
                            .padding(.top, 12)
                    }
                }
                Spacer(minLength: 0)
                LayerStack(fraction: printer.fraction, layer: layers.known ? layers.current : nil,
                           total: layers.known ? layers.total : nil,
                           layerProgress: layers.layerProgress, filament: prefs.filament, hot: prefs.accent)
                    .frame(width: 48)
                    .padding(.top, 10)
            }
            VStack(alignment: .leading, spacing: 8) {
                ProgressTrack(fraction: printer.fraction, color: printer.phase == .paused ? Ink.warn : prefs.accent)
                HStack(spacing: 12) {
                    Text("\(Int((printer.fraction * 100).rounded()))%")
                        .contentTransition(.numericText())
                    if layers.known, let l = layers.current, let t = layers.total { Text("Layer \(l) of \(t)") }
                    Spacer()
                    if let z = printer.currentZ, z > 0 { Text(String(format: "Z %.2f mm", z)) }
                }
                .font(.meta).foregroundStyle(Ink.soft).monospacedDigit()
                .animation(.smooth, value: printer.fraction)
            }
        }
        .panel(padding: 18)
    }
}

/// Pause or resume, and hold to cancel: pinned above the tabs for as long as a job runs.
private struct JobActions: View {
    let session: PrinterSession
    let phase: Phase
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        // side by side, or stacked when the text is too big for two across
        let layout = textSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 10)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            if phase == .paused {
                Button(action: session.resume) { Label("Resume", systemImage: "play.fill") }
                    .buttonStyle(PrimaryButtonStyle())
            } else {
                Button(action: session.pause) { Label("Pause", systemImage: "pause.fill") }
                    .buttonStyle(QuietButtonStyle())
                    .disabled(!phase.canPause)
            }
            HoldButton(title: "Hold to cancel", systemImage: "xmark", enabled: phase.canCancel, action: session.cancel)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background {
            // solid behind the buttons, and fading out just above them, so the page slips
            // under the bar rather than being cut off by it
            VStack(spacing: 0) {
                LinearGradient(colors: [Ink.black.opacity(0), Ink.black], startPoint: .top, endPoint: .bottom)
                    .frame(height: 24)
                Ink.black
            }
            .padding(.top, -24)
            .ignoresSafeArea(edges: .bottom)
        }
    }
}

private struct ReadyCard: View {
    let newest: PrintFile?
    let goToFiles: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Ready").font(.heroWord).foregroundStyle(Ink.bone)
            Text("Nothing printing.").font(.body).foregroundStyle(Ink.soft)
            Button(action: goToFiles) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Caption(newest == nil ? "Files" : "Newest upload")
                        Text(newest.map { prettyName($0.display) } ?? "Choose something to print")
                            .font(.heading).foregroundStyle(Ink.bone).lineLimit(1)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Ink.faint)
                }
                .padding(14)
                .background(Ink.panelHi, in: .rect(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(PressStyle())
        }
        .panel(padding: 18)
    }
}

private struct FinishedCard: View {
    let outcome: Outcome
    let dismiss: () -> Void

    var body: some View {
        let done = outcome.kind == .done
        VStack(alignment: .leading, spacing: 10) {
            Text(done ? "Finished" : outcome.kind == .failed ? "Failed" : "Cancelled")
                .font(.heroWord)
                .foregroundStyle(done ? Ink.ok : outcome.kind == .failed ? Ink.bad : Ink.bone)
            if !outcome.fileName.isEmpty {
                Text(prettyName(outcome.fileName)).font(.title).foregroundStyle(Ink.bone).lineLimit(2)
            }
            Text(done
                 ? (outcome.seconds.map { "Took \(duration($0)). Clear the bed before the next one." } ?? "Clear the bed before the next one.")
                 : "Check the printer before trying again.")
                .font(.body).foregroundStyle(Ink.soft)
            Button(action: dismiss) { Label(done ? "Bed is clear" : "Done", systemImage: "checkmark") }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 6)
        }
        .panel(padding: 18)
        .sensoryFeedback(.success, trigger: outcome)
    }
}

private struct OfflineCard: View {
    let printer: PrinterSnapshot
    let connect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(printer.phase == .error ? "Printer error" : "Printer offline")
                .font(.heroWord).foregroundStyle(printer.phase == .error ? Ink.bad : Ink.bone)
            Text(printer.errorText ?? (printer.phase == .error ? printer.stateText : "OctoPrint isn't connected to the printer."))
                .font(.body).foregroundStyle(Ink.soft)
            Button(action: connect) { Label("Connect printer", systemImage: "cable.connector") }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 6)
        }
        .panel(padding: 18)
    }
}

/* ---------------- the picture ---------------- */

/// The part or the camera, with its buttons laid over the top-left corner (the part drawing
/// keeps the top-right one for its turn button).
private struct PicturePanel: View {
    let session: PrinterSession
    let expand: () -> Void
    @Environment(Prefs.self) private var prefs

    var body: some View {
        let cam = session.info.webcam
        let showCamera = prefs.showCamera && cam != nil
        ZStack(alignment: .topLeading) {
            Group {
                if showCamera, let cam {
                    CameraView(session: session, webcam: cam)
                } else {
                    PartPanel(session: session)
                }
            }
            .aspectRatio(4 / 3, contentMode: .fit)
            .clipShape(.rect(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Ink.line.opacity(0.55), lineWidth: 0.5))

            HStack(spacing: 6) {
                if cam != nil {
                    PanelToggle(systemImage: showCamera ? "cube.fill" : "video.fill",
                                label: showCamera ? "Show the part" : "Show the camera") {
                        prefs.showCamera.toggle()
                    }
                }
                PanelToggle(systemImage: "arrow.up.left.and.arrow.down.right", label: "Full screen", action: expand)
                if showCamera, let light = session.info.lights.first {
                    PanelToggle(systemImage: light.percent > 0 ? "lightbulb.fill" : "lightbulb", on: light.percent > 0,
                                label: light.percent > 0 ? "Light off" : "Light on") {
                        session.setLight(light, percent: light.percent > 0 ? 0 : 100)
                    }
                }
            }
            .padding(10)
        }
    }
}

/// The part or the camera, as big as the phone allows. The camera can be pinched and dragged.
private struct FullScreenPicture: View {
    let session: PrinterSession
    @Environment(Prefs.self) private var prefs
    @Environment(\.dismiss) private var dismiss
    @State private var zoom: CGFloat = 1
    @State private var settledZoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var settledPan: CGSize = .zero

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Ink.black.ignoresSafeArea()
            Group {
                if prefs.showCamera, let cam = session.info.webcam {
                    CameraView(session: session, webcam: cam, backdrop: Ink.black)
                        .scaleEffect(zoom)
                        .offset(pan)
                        .gesture(MagnifyGesture()
                            .onChanged { zoom = min(max(settledZoom * $0.magnification, 1), 5) }
                            .onEnded { _ in
                                settledZoom = zoom
                                if zoom == 1 { withAnimation(.smooth) { pan = .zero; settledPan = .zero } }
                            })
                        .simultaneousGesture(DragGesture()
                            .onChanged { v in
                                guard zoom > 1 else { return }
                                pan = CGSize(width: settledPan.width + v.translation.width, height: settledPan.height + v.translation.height)
                            }
                            .onEnded { _ in settledPan = pan })
                        .onTapGesture(count: 2) {
                            withAnimation(.smooth) {
                                zoom = zoom > 1 ? 1 : 2.5
                                settledZoom = zoom
                                pan = .zero
                                settledPan = .zero
                            }
                        }
                        .accessibilityHint("Pinch to zoom, double-tap to zoom in or out")
                } else {
                    PartPanel(session: session)
                        .aspectRatio(4 / 3, contentMode: .fit)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()

            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Ink.bone)
                    .frame(width: 44, height: 44)
                    .background(.black.opacity(0.6), in: Circle())
            }
            .padding(16)
            .accessibilityLabel("Close")
        }
        .statusBarHidden()
    }
}

/// A round button laid over the picture.
struct PanelToggle: View {
    let systemImage: String
    var on = false
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(on ? Ink.black : Ink.bone)
                .frame(width: 40, height: 40)
                .background(on ? AnyShapeStyle(Ink.warn) : AnyShapeStyle(.black.opacity(0.55)), in: Circle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }
}

/// Why OctoPrint isn't answering, when it isn't.
struct LinkBanner: View {
    let session: PrinterSession
    @Environment(AppModel.self) private var model

    var body: some View {
        switch session.link {
        case .unreachable where session.display?.step?.committed == true:
            // gone quiet because the Echo Show is switching everything off: its card says so
            EmptyView()
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
