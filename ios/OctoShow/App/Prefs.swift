import Foundation
import Observation

/// What the phone remembers between runs that isn't a secret. The server and its key live
/// in the Keychain (`ServerStore`).
@MainActor
@Observable
final class Prefs {
    private let d = UserDefaults.standard

    var filamentId: String { didSet { d.set(filamentId, forKey: "filament") } }
    var accentId: String { didSet { d.set(accentId, forKey: "accent") } }
    var jogSpeed: JogSpeed { didSet { d.set(jogSpeed.rawValue, forKey: "jogSpeed") } }
    /// How fast Extrude and Retract feed filament, in mm/min.
    var extrudeSpeed: Int { didSet { d.set(extrudeSpeed, forKey: "extrudeSpeed") } }
    /// What the big panel on the status screen shows: the part or the camera.
    var showCamera: Bool { didSet { d.set(showCamera, forKey: "showCamera") } }
    /// The ntfy topic alerts are sent to; made up once, at random, so nobody can guess it.
    var alertTopic: String { didSet { d.set(alertTopic, forKey: "alertTopic") } }
    /// Where ntfy runs: its public server unless you host your own.
    var alertServer: String { didSet { d.set(alertServer, forKey: "alertServer") } }

    init() {
        filamentId = d.string(forKey: "filament") ?? "bone"
        accentId = d.string(forKey: "accent") ?? "ember"
        jogSpeed = JogSpeed(rawValue: d.string(forKey: "jogSpeed") ?? "") ?? .normal
        extrudeSpeed = d.object(forKey: "extrudeSpeed") as? Int ?? 300
        showCamera = d.bool(forKey: "showCamera")
        alertServer = d.string(forKey: "alertServer") ?? "https://ntfy.sh"
        if let topic = d.string(forKey: "alertTopic") {
            alertTopic = topic
        } else {
            let topic = "octoshow-" + (0..<16).map { _ in String("abcdefghijkmnopqrstuvwxyz23456789".randomElement()!) }.joined()
            alertTopic = topic
            d.set(topic, forKey: "alertTopic")
        }
    }
}
