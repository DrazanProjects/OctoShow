import PartKit
import SwiftUI

/// The big picture on the status screen: the job's part drawn as it stands, layer by layer,
/// its top layer filling in along the nozzle's path (see PartKit's `PartView`). With no job
/// selected there is nothing to draw, and it says so.
struct PartPanel: View {
    let session: PrinterSession
    @Environment(Prefs.self) private var prefs
    @State private var turn = 0
    @State private var thumbnail: CGImage?

    var body: some View {
        // A job from the printer's SD card is drawn from OctoPrint's own copy of the file.
        let file = session.jobDrawing ?? session.jobFile
        if let file {
            let p = session.printer
            let busy = p?.phase.busy == true
            let layers = session.layers
            PartView(
                library: session.parts,
                file: PartFile(file),
                fraction: busy ? p?.fraction : nil,
                layer: layers.known ? layers.current : nil,
                layerTotal: layers.known ? layers.total : nil,
                layerProgress: layers.known ? layers.layerProgress : nil,
                nozzle: session.nozzle,
                fromCard: session.jobFile?.origin == sdCard,
                printing: p?.phase == .printing,
                tint: prefs.filament,
                hot: prefs.accent,
                turn: $turn,
                thumbnail: thumbnail
            )
            .background(Ink.panelHi)
            .task(id: file.thumbnail) {
                thumbnail = nil
                if let link = file.thumbnail { thumbnail = await session.thumbnail(link)?.cgImage }
            }
        } else {
            ZStack {
                Ink.panelHi
                VStack(spacing: 10) {
                    Image(systemName: "printer").font(.largeTitle)
                    Text("No job selected").font(.label)
                }
                .foregroundStyle(Ink.faint)
            }
        }
    }
}
