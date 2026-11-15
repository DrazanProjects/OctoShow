import SwiftUI

struct SettingsView: View {
    let session: PrinterSession
    @Environment(AppModel.self) private var model
    @Environment(Prefs.self) private var prefs
    @State private var forgetting = false

    var body: some View {
        @Bindable var prefs = prefs
        NavigationStack {
            Form {
                Group {
                Section("Printer") {
                    row("OctoPrint", session.info.version.isEmpty ? "–" : session.info.version)
                    row("At home", session.server.home.base.absoluteString)
                    NavigationLink {
                        AwayView(session: session)
                    } label: {
                        row("Away from home", session.server.away?.base.absoluteString ?? "Not set up")
                    }
                    row("Using now", session.link == .online ? (session.via == .home ? "Home address" : "Away address") : "Not connected")
                }

                Section {
                    NavigationLink("Alerts when the app is closed") { AlertsView(session: session) }
                } footer: {
                    Text("Print finished, failed or paused, sent by OctoPrint through the free ntfy app.")
                }

                Section("Colors") {
                    SwatchPicker(title: "Filament", swatches: filaments, selected: $prefs.filamentId)
                    SwatchPicker(title: "Accent", swatches: accents, selected: $prefs.accentId)
                }

                Section("What it found") {
                    found("Camera", session.info.webcam != nil, "Set one up in OctoPrint's settings as an MJPEG stream.")
                    found("Temperature presets", !session.info.presets.isEmpty, "Add them in OctoPrint's settings, under Temperatures.")
                    found("Lights", !session.info.lights.isEmpty, "The LightControls plugin adds the light switch.")
                    found("Layers", session.layers.total != nil, "The Dashboard plugin reports layers, and lets the top layer fill in along the nozzle's path.")
                }

                Section {
                    Button("Forget this printer", role: .destructive) { forgetting = true }
                } footer: {
                    Text("This phone's key is listed in OctoPrint under Settings › Application Keys as “\(AppModel.appName)”, where it can be revoked.")
                }
                }
                .listRowBackground(Ink.panel)
            }
            .scrollContentBackground(.hidden)
            .background(Ink.black)
            .navigationTitle("Settings")
            .confirmationDialog("Forget this printer?", isPresented: $forgetting, titleVisibility: .visible) {
                Button("Forget", role: .destructive) { model.forgetServer() }
            } message: {
                Text("You'll need to set it up again, and approve a new key in OctoPrint.")
            }
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        LabeledContent(k) { Text(v).foregroundStyle(Ink.soft).lineLimit(1).truncationMode(.middle) }
    }

    private func found(_ what: String, _ there: Bool, _ how: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(what)
                Spacer()
                Image(systemName: there ? "checkmark.circle.fill" : "minus.circle").foregroundStyle(there ? Ink.ok : Ink.faint)
            }
            if !there { Text(how).font(.footnote).foregroundStyle(Ink.faint) }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct SwatchPicker: View {
    let title: String
    let swatches: [Swatch]
    @Binding var selected: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
            HStack(spacing: 10) {
                ForEach(swatches) { s in
                    Button { selected = s.id } label: {
                        Circle().fill(s.color)
                            .frame(width: 30, height: 30)
                            .overlay(Circle().stroke(Ink.bone, lineWidth: selected == s.id ? 2.5 : 0).padding(-4))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(s.label)
                    .accessibilityAddTraits(selected == s.id ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// Setting up the address to use away from home: a VPN address (Tailscale, ZeroTier,
/// WireGuard), a Cloudflare Tunnel with an Access service token, or a relay's address.
struct AwayView: View {
    let session: PrinterSession
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var kind = Kind.vpn
    @State private var clientId = ""
    @State private var clientSecret = ""
    @State private var user = ""
    @State private var password = ""
    @State private var checking = false
    @State private var problem: String?

    enum Kind: String, CaseIterable, Identifiable {
        case vpn = "VPN"
        case cloudflare = "Cloudflare"
        case relay = "Relay"
        var id: String { rawValue }
    }

    var body: some View {
        Form {
            Group {
            Section {
                Picker("How", selection: $kind) {
                    ForEach(Kind.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                Text(explanation).font(.footnote).foregroundStyle(Ink.soft)
            }
            Section("Address") {
                TextField(placeholder, text: $address)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
            }
            if kind == .cloudflare {
                Section {
                    TextField("CF-Access-Client-Id", text: $clientId).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("CF-Access-Client-Secret", text: $clientSecret)
                } header: {
                    Text("Access service token")
                } footer: {
                    Text("Made in Cloudflare Zero Trust under Access › Service Auth. The Access policy for OctoPrint needs a Service Auth rule that allows it.")
                }
            }
            if kind == .relay {
                Section("Sign-in, if the address needs one") {
                    TextField("User name", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password", text: $password)
                }
            }
            Section {
                Button {
                    Task { await check() }
                } label: {
                    HStack {
                        Text("Check and save")
                        if checking { Spacer(); ProgressView() }
                    }
                }
                .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty || checking)
                if let problem { Text(problem).font(.footnote).foregroundStyle(Ink.warn) }
            } footer: {
                Text("At home the phone uses the home address. When that doesn't answer, it tries this one. The badge on the status screen says which it's using.")
            }
            if session.server.away != nil {
                Section {
                    Button("Remove the away address", role: .destructive) {
                        model.setAway(nil)
                        dismiss()
                    }
                }
            }
            }
            .listRowBackground(Ink.panel)
        }
        .scrollContentBackground(.hidden)
        .background(Ink.black)
        .navigationTitle("Away from home")
        .onAppear(perform: load)
    }

    private var explanation: String {
        switch kind {
        case .vpn: "Tailscale, ZeroTier or your own WireGuard. Use the Pi's address on that network, such as octopi.tail1234.ts.net or 100.101.102.103. The VPN app has to be on when you're away."
        case .cloudflare: "cloudflared on the Pi, with OctoPrint behind Cloudflare Access. Nothing to switch on on the phone."
        case .relay: "A relay's address for apps, such as an OctoEverywhere App Connection, or a reverse proxy with a password."
        }
    }

    private var placeholder: String {
        switch kind {
        case .vpn: "octopi.tail1234.ts.net"
        case .cloudflare: "https://printer.example.com"
        case .relay: "https://…"
        }
    }

    private func load() {
        guard let away = session.server.away else { return }
        address = away.base.absoluteString
        if let id = away.headers["CF-Access-Client-Id"] {
            kind = .cloudflare
            clientId = id
            clientSecret = away.headers["CF-Access-Client-Secret"] ?? ""
        } else if away.user != nil {
            kind = .relay
            user = away.user ?? ""
            password = away.password ?? ""
        }
    }

    private func check() async {
        checking = true
        problem = nil
        defer { checking = false }
        var headers: [String: String] = [:]
        if kind == .cloudflare {
            headers["CF-Access-Client-Id"] = clientId.trimmingCharacters(in: .whitespaces)
            headers["CF-Access-Client-Secret"] = clientSecret.trimmingCharacters(in: .whitespaces)
        }
        let u = kind == .relay && !user.isEmpty ? user : nil
        let p = kind == .relay && !password.isEmpty ? password : nil
        switch await model.checkAway(address: address, headers: headers, user: u, password: p) {
        case .success(let route):
            model.setAway(route)
            dismiss()
        case .failure(.message(let why)):
            problem = why
        }
    }
}
