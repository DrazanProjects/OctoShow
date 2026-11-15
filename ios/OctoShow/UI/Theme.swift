import SwiftUI
import UIKit

/// The Echo Show display's look, bone ink on a black ground, with color kept for heat and
/// state. The camera or the part supplies the only other color on screen.
enum Ink {
    static let black = Color(hex: 0x000000)
    static let panel = Color(hex: 0x121416)
    static let panelHi = Color(hex: 0x1C1F22)
    static let line = Color(hex: 0x2A2E32)
    static let bone = Color(hex: 0xF3F1EC)
    static let soft = Color(hex: 0xA3A8AD)
    static let faint = Color(hex: 0x7B8187)
    static let bed = Color(hex: 0x5AA9FF)
    static let ok = Color(hex: 0x5BD6A0)
    static let warn = Color(hex: 0xFFC857)
    static let bad = Color(hex: 0xFF5A5F)
    /// Behind a file's picture: lighter at the middle, like a lamp over the bed, so a part the
    /// slicer drew in dark filament still stands out.
    static let well = EllipticalGradient(colors: [Color(hex: 0x474D55), Color(hex: 0x24272B)],
                                         center: .center, startRadiusFraction: 0, endRadiusFraction: 0.75)
}

/// A color on offer, and what to call it.
struct Swatch: Identifiable {
    let id: String
    let label: String
    let color: Color
}

/// The color of whatever is hot or under way. The first is the one it was designed with.
let accents: [Swatch] = [
    Swatch(id: "ember", label: "Ember", color: Color(hex: 0xFF6B2C)),
    Swatch(id: "amber", label: "Amber", color: Color(hex: 0xFFAE1F)),
    Swatch(id: "lime", label: "Lime", color: Color(hex: 0x7ADB4B)),
    Swatch(id: "teal", label: "Teal", color: Color(hex: 0x2FD5C0)),
    Swatch(id: "sky", label: "Sky", color: Color(hex: 0x4AA8FF)),
    Swatch(id: "violet", label: "Violet", color: Color(hex: 0x9D7DFF)),
    Swatch(id: "pink", label: "Pink", color: Color(hex: 0xFF5FA2)),
    Swatch(id: "red", label: "Red", color: Color(hex: 0xFF4D4F)),
]

/// What the part is drawn in: the color of the spool.
let filaments: [Swatch] = [
    Swatch(id: "bone", label: "Bone", color: Color(hex: 0xE9E4D8)),
    Swatch(id: "orange", label: "Orange", color: Color(hex: 0xFF8A3D)),
    Swatch(id: "yellow", label: "Yellow", color: Color(hex: 0xFFD25A)),
    Swatch(id: "mint", label: "Mint", color: Color(hex: 0x63D9A6)),
    Swatch(id: "sky", label: "Sky", color: Color(hex: 0x6CB8FF)),
    Swatch(id: "lilac", label: "Lilac", color: Color(hex: 0xB79CFF)),
    Swatch(id: "rose", label: "Rose", color: Color(hex: 0xFF8FA3)),
    Swatch(id: "grey", label: "Grey", color: Color(hex: 0x8C939A)),
]

extension Prefs {
    var accent: Color { (accents.first { $0.id == accentId } ?? accents[0]).color }
    var filament: Color { (filaments.first { $0.id == filamentId } ?? filaments[0]).color }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/* ---------------- type ---------------- */

/// One family, Archivo, a variable font. Its width axis does the work, as on the display:
/// live values run wide, text sits at normal width, dense metadata narrows.
///
/// Every style follows the reader's text size setting. The big display styles stop growing at
/// a ceiling, so a time left still fits across the screen at the largest sizes.
enum Archivo {
    @MainActor
    static func font(_ size: CGFloat, weight: CGFloat = 400, width: CGFloat = 100,
                     relativeTo style: UIFont.TextStyle = .body, upTo ceiling: CGFloat? = nil) -> Font {
        let category = UIApplication.shared.preferredContentSizeCategory
        let key = "\(size)-\(weight)-\(width)-\(style.rawValue)-\(category.rawValue)"
        if let hit = cache[key] { return hit }
        let f = Font(uiFont(size, weight: weight, width: width, relativeTo: style, upTo: ceiling))
        cache[key] = f
        return f
    }

    /// The same, for UIKit: scaled with the reader's text size setting.
    static func uiFont(_ size: CGFloat, weight: CGFloat = 400, width: CGFloat = 100,
                       relativeTo style: UIFont.TextStyle = .body, upTo ceiling: CGFloat? = nil) -> UIFont {
        let descriptor = UIFontDescriptor(fontAttributes: [
            // the variable font's default instance; the axes below move it anywhere
            .name: "ArchivoRoman-Regular",
            kCTFontVariationAttribute as UIFontDescriptor.AttributeName: [
                0x7767_6874: weight, // 'wght'
                0x7764_7468: width, // 'wdth'
            ],
        ])
        let base = UIFont(descriptor: descriptor, size: size)
        let metrics = UIFontMetrics(forTextStyle: style)
        return ceiling.map { metrics.scaledFont(for: base, maximumPointSize: $0) } ?? metrics.scaledFont(for: base)
    }

    nonisolated(unsafe) private static var cache: [String: Font] = [:]
}

@MainActor
extension Font {
    /// The one number you read from across the room.
    static var hero: Font { Archivo.font(56, weight: 700, width: 125, relativeTo: .largeTitle, upTo: 72) }
    static var heroUnit: Font { Archivo.font(22, weight: 500, width: 125, relativeTo: .title2, upTo: 30) }
    /// Words standing where the hero number would be: Ready, Done, Offline.
    static var heroWord: Font { Archivo.font(36, weight: 700, width: 125, relativeTo: .largeTitle, upTo: 48) }
    static var reading: Font { Archivo.font(24, weight: 600, width: 125, relativeTo: .title2, upTo: 34) }
    /// A screen's name, in place of a navigation bar's.
    static var screenTitle: Font { Archivo.font(30, weight: 700, width: 112, relativeTo: .largeTitle, upTo: 42) }
    static var title: Font { Archivo.font(19, weight: 600, relativeTo: .title3) }
    static var heading: Font { Archivo.font(16, weight: 600, relativeTo: .headline) }
    static var body: Font { Archivo.font(15, relativeTo: .body) }
    static var label: Font { Archivo.font(13, weight: 500, relativeTo: .subheadline) }
    static var meta: Font { Archivo.font(13, weight: 500, width: 84, relativeTo: .footnote) }
    static var button: Font { Archivo.font(16, weight: 600, relativeTo: .body, upTo: 28) }
}

/* ---------------- surfaces ---------------- */

struct PanelBackground: ViewModifier {
    var padding: CGFloat = 16
    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Ink.panel, in: .rect(cornerRadius: 22, style: .continuous))
            // a hairline, so panels keep their edge on a pure black ground
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Ink.line.opacity(0.55), lineWidth: 0.5))
    }
}

extension View {
    func panel(padding: CGFloat = 16) -> some View { modifier(PanelBackground(padding: padding)) }
}

/// A label set small above what it names.
struct Caption: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.meta)
            .tracking(0.8)
            .foregroundStyle(Ink.faint)
    }
}
