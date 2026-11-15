import SwiftUI

/// Alerts that reach the phone when OctoShow isn't open. iOS stops an app's connection soon
/// after it leaves the screen, and push notifications of its own need a paid Apple developer
/// account, so OctoPrint sends them itself: its built-in event commands call ntfy, whose free
/// app shows them. Tapping one opens OctoShow.
struct AlertsView: View {
    let session: PrinterSession
    @Environment(Prefs.self) private var prefs
    @State private var copied: String?
    @State private var testing = false
    @State private var tested: String?

    var body: some View {
        @Bindable var prefs = prefs
        Form {
            Section {
                Text("Once set up, OctoPrint tells you when a print finishes, fails or pauses (a filament runout pauses it), and when the printer reports an error, whether or not this app is open.")
                    .font(.footnote).foregroundStyle(Ink.soft)
            }

            Section {
                step(1, "Install ntfy", "It's free, on the App Store.")
                SwiftUI.Link("Open ntfy on the App Store", destination: URL(string: "https://apps.apple.com/app/ntfy/id1625396347")!)
            }

            Section {
                step(2, "Subscribe to your topic", "In ntfy, tap + and enter this topic. It's made up at random, so nobody else will guess it.")
                HStack {
                    Text(prefs.alertTopic).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                    Spacer()
                    copyButton("topic", prefs.alertTopic)
                }
                DisclosureGroup("Using your own ntfy server") {
                    TextField("https://ntfy.sh", text: $prefs.alertServer)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                }
            }

            Section {
                step(3, "Tell OctoPrint", "Add this to OctoPrint's config.yaml (on OctoPi: ~/.octoprint/config.yaml, over SSH), then restart OctoPrint. If config.yaml already has an events: section, add these lines under its subscriptions: instead.")
                Text(snippet)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Ink.bone)
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Ink.black, in: RoundedRectangle(cornerRadius: 8))
                copyButton("setup", snippet, label: "Copy the setup")
            }

            Section {
                step(4, "Try it", "Sends a test alert to your topic from this phone. It should show up in ntfy within a few seconds.")
                Button {
                    Task { await sendTest() }
                } label: {
                    HStack { Text("Send a test alert"); if testing { Spacer(); ProgressView() } }
                }
                .disabled(testing)
                if let tested { Text(tested).font(.footnote).foregroundStyle(Ink.soft) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Ink.black)
        .navigationTitle("Alerts")
    }

    private var topicURL: String {
        var s = prefs.alertServer.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        return "\(s.isEmpty ? "https://ntfy.sh" : s)/\(prefs.alertTopic)"
    }

    private var snippet: String { alertsConfig(topicURL: topicURL) }

    private func step(_ n: Int, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(n)")
                .font(.heading).foregroundStyle(Ink.black)
                .frame(width: 26, height: 26)
                .background(.tint, in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.heading)
                Text(detail).font(.footnote).foregroundStyle(Ink.soft)
            }
        }
        .padding(.vertical, 2)
    }

    private func copyButton(_ what: String, _ text: String, label: String = "Copy") -> some View {
        Button(copied == what ? "Copied" : label) {
            UIPasteboard.general.string = text
            copied = what
        }
    }

    private func sendTest() async {
        testing = true
        defer { testing = false }
        guard let url = URL(string: topicURL) else {
            tested = "That server address doesn't look right."
            return
        }
        var r = URLRequest(url: url, timeoutInterval: 15)
        r.httpMethod = "POST"
        r.setValue("OctoShow test", forHTTPHeaderField: "Title")
        r.setValue("octoshow://status", forHTTPHeaderField: "Click")
        r.httpBody = Data("If you can read this in ntfy, alerts will reach you.".utf8)
        do {
            let (_, response) = try await URLSession.shared.data(for: r)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            tested = (200..<300).contains(code) ? "Sent. Check ntfy." : "ntfy answered with an error (\(code))."
        } catch {
            tested = "Couldn't reach \(url.host ?? "ntfy")."
        }
    }
}

/// The lines for OctoPrint's config.yaml. OctoPrint runs each command when its event happens,
/// filling in the {placeholders} from the event: {name} is the file, {reason} why a print
/// stopped, {error} the printer's error. Each command is single-quoted for YAML: the headers'
/// "Name: value" would otherwise read as a mapping, and OctoPrint wouldn't start.
func alertsConfig(topicURL: String) -> String {
    func hook(_ event: String, _ title: String, _ tags: String, _ message: String, priority: Int = 3) -> String {
        """
          - event: \(event)
            type: system
            command: 'curl -s -H "Title: \(title)" -H "Tags: \(tags)" -H "Priority: \(priority)" -H "Click: octoshow://status" -d "\(message)" \(topicURL)'
        """
    }
    return (["events:", "  enabled: true", "  subscriptions:"] + [
        hook("PrintDone", "Print finished", "white_check_mark", "{name} is done. Clear the bed before the next one."),
        hook("PrintFailed", "Print stopped", "x", "{name} stopped ({reason}).", priority: 4),
        hook("PrintPaused", "Print paused", "pause_button", "{name} is paused. It may need filament, or an answer.", priority: 4),
        hook("Error", "Printer error", "warning", "{error}", priority: 5),
    ]).joined(separator: "\n")
}
