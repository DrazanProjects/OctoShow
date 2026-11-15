import Foundation

/// "CHESSBOARD_top_front_left_PLA_8h8m.gcode" reads better as "CHESSBOARD top front left PLA".
/// Slicers like to append the print time to the file name; it is shown properly elsewhere.
func prettyName(_ display: String) -> String {
    var stem = display
    if let dot = stem.lastIndex(of: "."), dot > stem.startIndex { stem = String(stem[..<dot]) }
    if let m = stem.firstMatch(of: #/[ _-]+(?:\d+h)?(?:\d+m)?(?:\d+s)?$/#), m.0.contains(where: \.isNumber) {
        stem.removeSubrange(m.range)
    }
    let cut = stem.replacingOccurrences(of: "_", with: " ").trimmingCharacters(in: .whitespaces)
    return cut.isEmpty ? display : cut
}

/// "6h 42m", "42m", "under a minute".
func duration(_ seconds: Int) -> String {
    let s = max(seconds, 0)
    let h = s / 3600, m = s % 3600 / 60
    if h > 0 { return "\(h)h \(m)m" }
    if m > 0 { return "\(m)m" }
    return "under a minute"
}

/// The same duration as (number, unit) pairs, for setting the numbers large.
func durationParts(_ seconds: Int) -> [(String, String)] {
    let s = max(seconds, 0)
    let h = s / 3600, m = s % 3600 / 60
    if h > 0 { return [("\(h)", "h"), (String(format: "%02d", m), "m")] }
    if m > 0 { return [("\(m)", "m")] }
    return [("<1", "m")]
}

/// A moment relative to today: "6:31 AM", "tomorrow 6:31 AM", "Thu 6:31 AM", in the phone's
/// own 12- or 24-hour style.
func whenIs(_ date: Date, now: Date = Date()) -> String {
    let cal = Calendar.current
    let time = date.formatted(date: .omitted, time: .shortened)
    if cal.isDate(date, inSameDayAs: now) { return time }
    if let tomorrow = cal.date(byAdding: .day, value: 1, to: now), cal.isDate(date, inSameDayAs: tomorrow) { return "tomorrow \(time)" }
    if let yesterday = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(date, inSameDayAs: yesterday) { return "yesterday \(time)" }
    return "\(date.formatted(.dateTime.weekday(.abbreviated))) \(time)"
}

/// When a file was uploaded: "today 10:22 PM", "yesterday", "Oct 3".
func uploadedAt(_ epochSeconds: Int64) -> String {
    guard epochSeconds > 0 else { return "" }
    let t = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
    let cal = Calendar.current
    if cal.isDateInToday(t) { return "today \(t.formatted(date: .omitted, time: .shortened))" }
    if cal.isDateInYesterday(t) { return "yesterday" }
    if cal.component(.year, from: t) == cal.component(.year, from: Date()) {
        return t.formatted(.dateTime.month(.abbreviated).day())
    }
    return t.formatted(.dateTime.month(.abbreviated).day().year())
}

/// OctoPrint knows how much filament a file uses, not which kind, so PLA stands in for all.
private let gramsPerCm3 = 1.24

/// Filament weight in grams, to a tenth for the smallest parts.
func filamentWeight(_ cm3: Double) -> String {
    let grams = cm3 * gramsPerCm3
    return grams < 10 ? String(format: "%.1f g", grams) : String(format: "%.0f g", grams)
}

func degrees(_ v: Double?) -> String { v.map { "\(Int($0.rounded()))°" } ?? "–" }
