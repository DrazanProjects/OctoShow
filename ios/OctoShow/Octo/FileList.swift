import Foundation

/// Reads GET /api/files. Only what the app shows is kept: plugins such as PrintTimeGenius
/// hang a lot of analysis off every file, and none of it is needed here.
enum FileList {
    static func parse(_ data: Data) -> [PrintFile] {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON else { return [] }
        return entries(o.arr("files"))
    }

    /// GET /api/files/{origin}/{path}: a single entry.
    static func parseOne(_ data: Data) -> PrintFile? {
        ((try? JSONSerialization.jsonObject(with: data)) as? JSON).flatMap(entry)
    }

    private static func entries(_ list: [Any]?) -> [PrintFile] {
        (list ?? []).compactMap { ($0 as? JSON).flatMap(entry) }
    }

    private static func entry(_ o: JSON) -> PrintFile? {
        guard let name = o.str("name") else { return nil }
        let type = o.str("type")
        let isFolder = type == "folder"
        guard isFolder || type == "machinecode" else { return nil }
        let analysis = o.obj("gcodeAnalysis")
        // {"tool0": {"length": mm, "volume": cm3}, ...} summed over tools
        var filament: Double?
        if let tools = analysis?.obj("filament") {
            for case let tool as JSON in tools.values {
                if let v = tool.dbl("volume") { filament = (filament ?? 0) + v }
            }
        }
        return PrintFile(
            name: name,
            display: o.str("display") ?? name,
            path: o.str("path") ?? name,
            origin: o.str("origin") ?? "local",
            isFolder: isFolder,
            size: Int64(o.dbl("size") ?? 0),
            date: Int64(o.dbl("date") ?? 0),
            estimatedPrintTime: analysis?.dbl("estimatedPrintTime"),
            filamentCm3: filament,
            thumbnail: o.str("thumbnail"),
            lastPrintSuccess: o.obj("prints")?.obj("last")?.bool("success"),
            children: entries(o.arr("children"))
        )
    }
}

extension Array where Element == PrintFile {
    /// Every printable file in the tree, most recent upload first.
    var newestFirst: [PrintFile] {
        func flat(_ list: [PrintFile]) -> [PrintFile] { list.flatMap { [$0] + flat($0.children) } }
        return flat(self).filter { !$0.isFolder }.sorted { $0.date > $1.date }
    }
}

/// The file in OctoPrint's own storage that `job`, printed from the printer's SD card, is
/// another copy of, going by its name. OctoPrint can't read files on the card, so this is
/// the one to draw the part from. Of several with the same name the newest upload wins.
/// A job started from the printer's own screen may only be known by the card's short name,
/// such as "SPOOL_~1.GCO": then the first letters of the long name have to do, and only
/// when just one file starts with them.
func sameFile(as job: JobFile, in newestFirst: [PrintFile]) -> PrintFile? {
    var names: [String] = []
    for n in [job.display, job.name, job.path].map(stem) where !n.isEmpty && !names.contains(n) { names.append(n) }
    if let hit = newestFirst.first(where: { names.contains(stem($0.name)) || names.contains(stem($0.display)) }) { return hit }
    guard let start = names.lazy.compactMap(shortNameStart(of:)).first else { return nil }
    let matches = newestFirst.filter { cardShortStart($0.name) == start }
    return matches.count == 1 ? matches[0] : nil
}

private let gcodeExtensions: Set<String> = ["gcode", "gco", "gc", "g"]

/// A file's name without its folders or its G-code extension, in lower case.
private func stem(_ name: String) -> String {
    let base = (name.split(separator: "/").last.map(String.init) ?? name).lowercased()
    guard let dot = base.lastIndex(of: "."), dot > base.startIndex else { return base }
    return gcodeExtensions.contains(String(base[base.index(after: dot)...])) ? String(base[..<dot]) : base
}

/// "spool_~1" once `stem` has been at it: up to six letters, a tilde and a number.
private func shortNameStart(of s: String) -> String? {
    guard let m = s.wholeMatch(of: #/([^~.]{1,6})~[0-9]{1,6}/#) else { return nil }
    return String(m.1)
}

/// How the card shortens a long name: spaces and dots dropped, odd characters made "_", six kept.
private func cardShortStart(_ name: String) -> String {
    let kept = stem(name).filter { $0 != " " && $0 != "." }
        .map { c -> Character in "+,;=[]".contains(c) || !c.isASCII ? "_" : c }
    return String(kept.prefix(6))
}
