import Foundation

/// One way of reaching OctoPrint: its address and, for an https Pi with its own
/// certificate, that certificate's fingerprint. A route used from away can carry what
/// whatever stands in front of OctoPrint wants: headers (a Cloudflare Access service
/// token) or a user name and password (a relay's address, a reverse proxy).
struct Route: Codable, Equatable, Hashable {
    var base: URL
    var cert: String?
    var headers: [String: String] = [:]
    var user: String?
    var password: String?

    var host: String { base.host ?? base.absoluteString }
}

/// The OctoPrint this phone was set up with: the key it issued, the address it has at
/// home, and optionally another to use when home doesn't answer.
struct Server: Codable, Equatable {
    var name: String
    var apiKey: String
    var home: Route
    var away: Route?
}

/// "192.168.1.50", "octopi.local:5000/" or a full URL, as a base URL ending in a slash.
func normalizeBase(_ input: String) -> URL? {
    var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.isEmpty { return nil }
    if !s.contains("://") { s = "http://" + s }
    guard var c = URLComponents(string: s), let host = c.host, !host.isEmpty,
          let scheme = c.scheme?.lowercased(), scheme == "http" || scheme == "https"
    else { return nil }
    c.scheme = scheme
    c.query = nil
    c.fragment = nil
    var p = c.percentEncodedPath
    while p.hasSuffix("/") { p.removeLast() }
    c.percentEncodedPath = p + "/"
    return c.url
}

/// The same address over the other scheme: OctoPrint announces port 80 on the network
/// even when only https answers, and someone typing "octopi.local" hasn't said which.
/// https first, so an access key doesn't cross the network in the clear where it needn't,
/// unless "http://" was typed on the front.
func candidates(for address: String, announced: Bool = false) -> [URL] {
    guard let given = normalizeBase(address), var c = URLComponents(url: given, resolvingAgainstBaseURL: false) else { return [] }
    let afterScheme = address.components(separatedBy: "://").last ?? address
    let typedPort = afterScheme.range(of: #":\d+"#, options: .regularExpression) != nil
    func over(_ scheme: String) -> URL? {
        c.scheme = scheme
        if !typedPort { c.port = nil }
        return c.url
    }
    let httpAsked = !announced && address.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("http://")
    return (httpAsked ? ["http", "https"] : ["https", "http"]).compactMap(over)
}

/// Keeps the server, key and any away-route secrets in the Keychain rather than in plain
/// preferences.
enum ServerStore {
    private static let service = "com.drazan.octoshow.server"

    static func load() -> Server? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Server.self, from: data)
    }

    static func save(_ server: Server) {
        guard let data = try? JSONEncoder().encode(server) else { return }
        forget()
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func forget() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }
}
