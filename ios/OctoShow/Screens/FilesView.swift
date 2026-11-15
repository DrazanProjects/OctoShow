import SwiftUI

/// The printable files in OctoPrint's own storage, folder by folder, with previews, print times
/// and how much filament each needs. Searchable across every folder, sortable, and a file can
/// be swiped to print. Starting a print always asks once.
struct FilesView: View {
    let session: PrinterSession
    let printed: () -> Void
    @AppStorage("filesSort") private var sort = FileSort.newest

    var body: some View {
        NavigationStack {
            FolderList(session: session, title: "Files", entries: session.files.root, sort: $sort, printed: printed)
        }
        .onAppear { session.watchFiles(true) }
        .onDisappear { session.watchFiles(false) }
    }
}

enum FileSort: String, CaseIterable, Identifiable {
    case newest, name, time
    var id: String { rawValue }
    var label: String {
        switch self {
        case .newest: "Newest first"
        case .name: "Name"
        case .time: "Shortest print first"
        }
    }

    func sorted(_ files: [PrintFile]) -> [PrintFile] {
        switch self {
        case .newest: files.sorted { $0.date > $1.date }
        case .name: files.sorted { prettyName($0.display).localizedStandardCompare(prettyName($1.display)) == .orderedAscending }
        case .time: files.sorted { ($0.estimatedPrintTime ?? .infinity) < ($1.estimatedPrintTime ?? .infinity) }
        }
    }
}

private struct FolderList: View {
    let session: PrinterSession
    let title: String
    let entries: [PrintFile]
    @Binding var sort: FileSort
    let printed: () -> Void
    @State private var chosen: PrintFile?
    @State private var confirming: PrintFile?
    @State private var query = ""

    private var canPrint: Bool { session.link == .online && session.printer?.phase == .ready }

    /// Folders first, then files in the chosen order; or, while searching, every matching file
    /// in every folder.
    private var shown: [PrintFile] {
        let q = query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty {
            let all = session.files.newestFirst.filter {
                prettyName($0.display).localizedCaseInsensitiveContains(q) || $0.path.localizedCaseInsensitiveContains(q)
            }
            return sort.sorted(all)
        }
        let folders = entries.filter(\.isFolder).sorted { $0.display.localizedStandardCompare($1.display) == .orderedAscending }
        return folders + sort.sorted(entries.filter { !$0.isFolder })
    }

    var body: some View {
        List {
            if session.files.loaded && shown.isEmpty {
                Text(query.isEmpty ? "Nothing here yet. Send a file from your slicer and it will show up." : "No files match “\(query)”.")
                    .font(.body).foregroundStyle(Ink.soft)
                    .listRowBackground(Ink.panel)
            }
            ForEach(shown) { f in
                if f.isFolder {
                    NavigationLink {
                        FolderList(session: session, title: f.display, entries: f.children, sort: $sort, printed: printed)
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "folder.fill").font(.title2).foregroundStyle(Ink.soft)
                                .frame(width: 64, height: 56)
                                .background(Ink.panelHi, in: .rect(cornerRadius: 14, style: .continuous))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(f.display).font(.heading).foregroundStyle(Ink.bone)
                                Text("\(f.children.count) \(f.children.count == 1 ? "item" : "items")").font(.meta).foregroundStyle(Ink.faint)
                            }
                        }
                    }
                    .listRowBackground(Ink.panel)
                } else {
                    Button { chosen = f } label: { FileRow(session: session, file: f) }
                        .listRowBackground(Ink.panel)
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            if canPrint {
                                Button { confirming = f } label: { Label("Print", systemImage: "printer.fill") }
                                    .tint(.accentColor)
                            }
                        }
                }
            }
        }
        .listRowSpacing(6)
        .scrollContentBackground(.hidden)
        .background(Ink.black)
        .overlay {
            if !session.files.loaded {
                if let error = session.files.error {
                    ContentUnavailableView("Couldn't load the files", systemImage: "exclamationmark.triangle", description: Text(error))
                } else {
                    ProgressView()
                }
            }
        }
        .refreshable { session.refreshFiles() }
        .searchable(text: $query, prompt: "Search files")
        .navigationTitle(title)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        ForEach(FileSort.allCases) { Text($0.label).tag($0) }
                    }
                } label: {
                    Label("Sort", systemImage: "arrow.up.arrow.down")
                }
            }
        }
        .sheet(item: $chosen) { f in
            FileSheet(session: session, file: f) {
                chosen = nil
                printed()
            }
        }
        .confirmationDialog("Start this print?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible, presenting: confirming) { f in
            Button("Print \(prettyName(f.display))") {
                session.print(f)
                printed()
            }
            Button("Not now", role: .cancel) {}
        } message: { _ in
            Text("Check the bed is clear before you start.")
        }
    }
}

private struct FileRow: View {
    let session: PrinterSession
    let file: PrintFile

    var body: some View {
        HStack(spacing: 14) {
            FileThumb(session: session, file: file).frame(width: 64, height: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(prettyName(file.display)).font(.heading).foregroundStyle(Ink.bone).lineLimit(2)
                Text(meta(file)).font(.meta).foregroundStyle(Ink.soft).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let ok = file.lastPrintSuccess {
                Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .foregroundStyle(ok ? Ink.ok : Ink.bad)
                    .font(.footnote)
                    .accessibilityLabel(ok ? "Printed fine last time" : "Failed last time")
            }
        }
        .padding(.vertical, 2)
        .contentShape(.rect)
    }
}

func meta(_ f: PrintFile) -> String {
    [f.estimatedPrintTime.map { duration(Int($0)) }, f.filamentCm3.map(filamentWeight), uploadedAt(f.date)]
        .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
}

/// A file's picture: the slicer's own if the file carries one, otherwise the one this phone
/// draws from the G-code.
struct FileThumb: View {
    let session: PrinterSession
    let file: PrintFile
    @Environment(Prefs.self) private var prefs
    @State private var image: UIImage?

    var body: some View {
        FilePicture(image: image)
            // asked again when drawing becomes allowed: the printer coming free, say
            .task(id: "\(file.id)-\(file.date)-\(prefs.filamentId)-\(file.thumbnail == nil && session.mayDrawPreviews)") {
                if let link = file.thumbnail {
                    image = await session.thumbnail(link)
                } else {
                    image = await session.drawnPreview(of: file, tint: prefs.filament)
                }
            }
    }
}

private struct FileSheet: View {
    let session: PrinterSession
    let file: PrintFile
    let started: () -> Void
    @State private var asking = false

    private var canPrint: Bool { session.link == .online && session.printer?.phase == .ready }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                FileThumb(session: session, file: file)
                    .aspectRatio(4 / 3, contentMode: .fit)
                Text(prettyName(file.display)).font(.screenTitle).foregroundStyle(Ink.bone)
                HStack(spacing: 10) {
                    if let t = file.estimatedPrintTime { Fact(label: "Print time", value: duration(Int(t))) }
                    if let v = file.filamentCm3 { Fact(label: "Filament", value: filamentWeight(v)) }
                    if let ok = file.lastPrintSuccess { Fact(label: "Last time", value: ok ? "Printed" : "Failed", color: ok ? Ink.ok : Ink.bad) }
                }
                if file.date > 0 {
                    Text("Uploaded \(uploadedAt(file.date)) · filament weighed as PLA")
                        .font(.meta).foregroundStyle(Ink.faint)
                }
            }
            .padding(20)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                Button { asking = true } label: { Label("Print", systemImage: "printer.fill") }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!canPrint)
                if !canPrint {
                    Text(session.printer?.phase.busy == true ? "A job is running." : "Connect the printer first.")
                        .font(.label).foregroundStyle(Ink.faint)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
            .background(Ink.panel)
        }
        .presentationDetents([.large])
        .presentationBackground(Ink.panel)
        .presentationDragIndicator(.visible)
        .confirmationDialog("Start this print?", isPresented: $asking, titleVisibility: .visible) {
            Button("Print \(prettyName(file.display))") {
                session.print(file)
                started()
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("Check the bed is clear before you start.")
        }
    }
}

/// One figure about a file, in a little box of its own.
private struct Fact: View {
    let label: String
    let value: String
    var color: Color = Ink.bone

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Caption(label)
            Text(value).font(.heading).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Ink.panelHi, in: .rect(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
