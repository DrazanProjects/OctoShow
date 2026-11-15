import SwiftUI

@main
struct OctoShowApp: App {
    @State private var model = AppModel()
    @State private var leftAt: Date?
    @Environment(\.scenePhase) private var phase

    init() {
        // Titles in the display's own type, wide for the big ones.
        let bar = UINavigationBar.appearance()
        bar.largeTitleTextAttributes = [.font: Archivo.uiFont(32, weight: 700, width: 112, relativeTo: .largeTitle)]
        bar.titleTextAttributes = [.font: Archivo.uiFont(17, weight: 600, relativeTo: .headline)]
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(model.prefs)
                .tint(model.prefs.accent)
                .preferredColorScheme(.dark)
                .onOpenURL { model.handle(url: $0) }
        }
        .onChange(of: phase) { _, now in
            // iOS suspends the app soon after it leaves the screen, and its socket with it, often
            // without either end noticing. Back on screen after more than a moment, start afresh
            // rather than show what was true when the phone went into a pocket.
            switch now {
            case .background:
                leftAt = Date()
            case .active:
                let away = leftAt.map { Date().timeIntervalSince($0) } ?? 0
                if let s = model.session, s.link != .online || away > 5 { s.reconnectNow() }
                leftAt = nil
            default:
                break
            }
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    /// Read so the whole app is laid out again, with fonts to match, when the text size changes.
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        Group {
            if let session = model.session {
                MainView(session: session)
                    .id(ObjectIdentifier(session))
            } else {
                SetupView()
            }
        }
        .background(Ink.black.ignoresSafeArea())
        .id(textSize)
    }
}

enum AppTab { case status, files, controls, settings }

struct MainView: View {
    let session: PrinterSession
    @Environment(AppModel.self) private var model

    var body: some View {
        // The tab lives in the model, so changing the away address, which starts a new
        // session, doesn't throw you back to the status screen.
        @Bindable var model = model
        let tab = $model.tab
        TabView(selection: tab) {
            Tab("Status", systemImage: "square.stack.3d.up.fill", value: AppTab.status) {
                StatusView(session: session, goToFiles: { model.tab = .files })
            }
            Tab("Files", systemImage: "folder.fill", value: AppTab.files) {
                FilesView(session: session, printed: { model.tab = .status })
            }
            Tab("Controls", systemImage: "slider.horizontal.3", value: AppTab.controls) {
                ControlsView(session: session)
            }
            Tab("Settings", systemImage: "gearshape.fill", value: AppTab.settings) {
                SettingsView(session: session)
            }
        }
        .modifier(MinimizingTabBar())
        .sensoryFeedback(.selection, trigger: model.tab)
        .overlay(alignment: .bottom) { NoticeToast(text: session.notice).padding(.bottom, 64) }
        .sheet(isPresented: Binding(get: { session.prompt != nil }, set: { _ in })) {
            if let prompt = session.prompt { PromptSheet(prompt: prompt, answer: session.answerPrompt) }
        }
    }
}

/// One line of feedback after something didn't work, that goes away by itself.
struct NoticeToast: View {
    let text: String?
    var body: some View {
        if let text {
            Text(text)
                .font(.label)
                .foregroundStyle(Ink.bone)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Ink.panelHi, in: Capsule())
                .overlay(Capsule().stroke(Ink.line))
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .animation(.snappy, value: text)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }
}

/// The printer asking something, such as after a filament runout: its question and answers.
struct PromptSheet: View {
    let prompt: Prompt
    let answer: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Caption("The printer asks")
            Text(prompt.text)
                .font(.title)
                .foregroundStyle(Ink.bone)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 10) {
                // The printer's first answer is usually the one it expects; the rest are quieter.
                ForEach(Array(prompt.choices.enumerated()), id: \.offset) { i, choice in
                    if i == 0 {
                        Button { answer(i) } label: { Text(choice) }
                            .buttonStyle(PrimaryButtonStyle(height: 50))
                    } else {
                        QuietButton(title: choice, height: 50) { answer(i) }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(24)
        .presentationDetents([.medium])
        .presentationBackground(Ink.panel)
        .interactiveDismissDisabled()
    }
}

/// On iOS 26 and later the tab bar shrinks out of the way while a page scrolls down.
private struct MinimizingTabBar: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            content
        }
    }
}
