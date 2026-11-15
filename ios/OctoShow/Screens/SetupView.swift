import SwiftUI

/// First run: find OctoPrint on the network (or type its address), then ask it for a key that
/// someone approves in OctoPrint's web page. "I have a key" skips the approval.
struct SetupView: View {
    @Environment(AppModel.self) private var model
    @State private var address = ""
    @State private var key = ""
    @State private var haveKey = false
    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("OctoShow").font(.heroWord).foregroundStyle(Ink.bone)
                    Text("Your printer, from your phone. Pick your OctoPrint below while you're on the same Wi‑Fi as it.")
                        .font(.body).foregroundStyle(Ink.soft)
                }
                .padding(.top, 24)

                switch model.setup {
                case .waiting(let url):
                    waiting(url)
                case .checking(let host):
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Looking for OctoPrint at \(host)…").font(.body).foregroundStyle(Ink.soft)
                    }
                    .panel()
                default:
                    found
                    manual
                }

                if case .failed(let why) = model.setup {
                    Label(why, systemImage: "exclamationmark.triangle.fill")
                        .font(.label)
                        .foregroundStyle(Ink.warn)
                        .panel()
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 40)
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear { model.startDiscovery() }
        .onDisappear { model.stopDiscovery() }
    }

    private var found: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Caption("On this network")
                Spacer()
                ProgressView().controlSize(.mini)
            }
            if model.found.isEmpty {
                Text("Looking… If nothing shows up, type the address below. iOS may first ask to let OctoShow find devices on your network: allow it.")
                    .font(.label).foregroundStyle(Ink.faint)
                    .panel()
            }
            ForEach(model.found) { f in
                Button {
                    model.requestAccess(f.address, name: f.name, announced: true)
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "printer.fill").font(.title3).foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name).font(.heading).foregroundStyle(Ink.bone)
                            Text(f.address).font(.meta).foregroundStyle(Ink.soft)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Ink.faint)
                    }
                    .panel()
                }
                .buttonStyle(PressStyle())
            }
        }
    }

    private var manual: some View {
        VStack(alignment: .leading, spacing: 12) {
            Caption("Or type its address")
            TextField("octopi.local or 192.168.1.50", text: $address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .focused($focused)
                .font(.body)
                .padding(14)
                .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .submitLabel(.go)
                .onSubmit(go)
            if haveKey {
                SecureField("Application key from OctoPrint's settings", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.body)
                    .padding(14)
                    .background(Ink.panelHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            Button(action: go) {
                Text(haveKey ? "Connect with this key" : "Ask OctoPrint for access")
                    .font(.button)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 14))
            .foregroundStyle(Ink.black)
            .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
            Button(haveKey ? "Ask for a key instead" : "I have a key") { haveKey.toggle() }
                .font(.label)
                .frame(maxWidth: .infinity)
        }
    }

    private func waiting(_ url: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ProgressView()
                Text("Waiting for approval").font(.title).foregroundStyle(Ink.bone)
            }
            Text("Open OctoPrint in a browser (\(url)), signed in as an admin. It shows a request from “\(AppModel.appName)”: choose Allow. This happens once.")
                .font(.body).foregroundStyle(Ink.soft)
            Text("Admin matters: the light switch needs an admin's key, and the key gets the rights of whoever approves it.")
                .font(.label).foregroundStyle(Ink.faint)
            QuietButton(title: "Cancel", color: Ink.soft, height: 46) { model.cancelSetup() }
        }
        .panel(padding: 20)
    }

    private func go() {
        focused = false
        if haveKey { model.useKey(address, key: key) } else { model.requestAccess(address) }
    }
}
