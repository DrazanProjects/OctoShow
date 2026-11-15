import SwiftUI

/// The printable files in OctoPrint's own storage, folder by folder, newest first, with
/// previews, print times and how much filament each needs. Starting a print asks once.
struct FilesView: View {
    let session: PrinterSession
    let printed: () -> Void

    var body: some View {
        NavigationStack {
            FolderList(session: session, title: "Files", entries: session.files.root, printed: printed)
        }
        .onAppear { session.watchFiles(true) }
        .onDisappear { session.watchFiles(false) }
    }
}

private struct FolderList: View {
    let session: PrinterSession
    let title: String
    let entries: [PrintFile]
    let printed: () -> Void
    @State private var chosen: PrintFile?

    private var sorted: [PrintFile] {
        // folders first, then files newest first
        entries.filter(\.isFolder).sorted { $0.display.localizedStandardCompare($1.display) == .orderedAscending }
            + entries.filter { !$0.isFolder }.sorted { $0.date > $1.date }
    }

    var body: some View {
        List {
            if session.files.loaded && entries.isEmpty {
                Text("Nothing here yet. Send a file from your slicer and it will show up.")
                    .font(.body).foregroundStyle(Ink.soft)
                    .listRowBackground(Ink.panel)
            }
            ForEach(sorted) { f in
                if f.isFolder {
                    NavigationLink {
                        FolderList(session: session, title: f.display, entries: f.children, printed: printed)
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "folder.fill").font(.title3).foregroundStyle(Ink.soft).frame(width: 64)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(f.display).font(.heading).foregroundStyle(Ink.bone)
                                Text("\(f.children.count) \(f.children.count == 1 ? "item" : "items")").font(.meta).foregroundStyle(Ink.faint)
                            }
                        }
                    }
                    .listRowBackground(Ink.panel)
                } else {
                    Button { chosen = f } label: { FileRow(session: session, file: f) }
                        .listRowBackground(Ink.panel)
                }
            }
        }
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
        .navigationTitle(title)
        .sheet(item: $chosen) { f in
            FileSheet(session: session, file: f) {
                chosen = nil
                printed()
            }
        }
    }
}

private struct FileRow: View {
    let session: PrinterSession
    let file: PrintFile

    var body: some View {
        HStack(spacing: 14) {
            FileThumb(session: session, file: file).frame(width: 64, height: 48)
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
        .contentShape(Rectangle())
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
        VStack(alignment: .leading, spacing: 16) {
            FileThumb(session: session, file: file)
                .aspectRatio(4 / 3, contentMode: .fit)
            Text(prettyName(file.display)).font(.title).foregroundStyle(Ink.bone)
            Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 8) {
                if let t = file.estimatedPrintTime { row("Print time", duration(Int(t))) }
                if let v = file.filamentCm3 { row("Filament", filamentWeight(v) + " (as PLA)") }
                if file.date > 0 { row("Uploaded", uploadedAt(file.date)) }
                if let ok = file.lastPrintSuccess { row("Last time", ok ? "Printed fine" : "Failed") }
            }
            Spacer(minLength: 0)
            Button { asking = true } label: {
                Label("Print", systemImage: "printer.fill").font(.button).frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 14))
            .foregroundStyle(Ink.black)
            .disabled(!canPrint)
            if !canPrint {
                Text(session.printer?.phase.busy == true ? "A job is running." : "Connect the printer first.")
                    .font(.label).foregroundStyle(Ink.faint).frame(maxWidth: .infinity)
            }
        }
        .padding(20)
        .presentationDetents([.large])
        .presentationBackground(Ink.panel)
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

    private func row(_ k: String, _ v: String) -> some View {
        GridRow {
            Text(k).font(.label).foregroundStyle(Ink.faint)
            Text(v).font(.body).foregroundStyle(Ink.bone)
        }
    }
}
