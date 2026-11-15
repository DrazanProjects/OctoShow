import SwiftUI

/// OctoPrint's camera. Live at home; away from home, a still every couple of seconds, since a
/// camera can push megabytes a second and mobile data or a relay's allowance won't thank you.
struct CameraView: View {
    let session: PrinterSession
    let webcam: Webcam
    /// What shows round the picture where it doesn't fill the space: a panel in a card, black full screen.
    var backdrop: Color = Ink.panelHi
    @State private var still: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            backdrop
            if let still {
                Image(uiImage: still)
                    .resizable()
                    .scaledToFit()
                    .rotationEffect(.degrees(webcam.rotate90 ? -90 : 0))
                    .scaleEffect(x: webcam.flipH ? -1 : 1, y: webcam.flipV ? -1 : 1)
                    .accessibilityLabel("Camera view of the printer")
            } else if failed {
                VStack(spacing: 8) {
                    Image(systemName: "video.slash").font(.title2)
                    Text("The camera isn't answering").font(.label)
                }
                .foregroundStyle(Ink.faint)
            } else {
                ProgressView()
            }
        }
        .overlay(alignment: .bottomLeading) {
            if session.via == .away, still != nil {
                Text(webcam.snapshotUrl != nil ? "A still every 2 s, to save data" : "Live")
                    .font(.meta).foregroundStyle(Ink.bone)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.black.opacity(0.55), in: Capsule())
                    .padding(10)
            }
        }
        .task(id: "\(session.via)-\(webcam.streamUrl)-\(session.link == .online)") { await watch() }
    }

    private func watch() async {
        guard let octo = session.api, session.link == .online else { return }
        let away = session.via == .away
        while !Task.isCancelled {
            let stills = away ? webcam.snapshotUrl.flatMap(octo.resolve) : nil
            guard let url = stills ?? octo.resolve(webcam.streamUrl) else { return }
            let frames = stills != nil ? MjpegStream.stills(api: octo, url: url) : MjpegStream.frames(api: octo, url: url)
            do {
                for try await image in frames {
                    still = image
                    failed = false
                }
            } catch {
                if Task.isCancelled { return }
            }
            failed = still == nil
            try? await Task.sleep(for: .seconds(3))
        }
    }
}
