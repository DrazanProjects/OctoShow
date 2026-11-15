import Foundation
import Observation

/**
 * Where the nozzle really is, for a screen to show: REALPOS readings in, and out the layer
 * the nozzle is on and how far through it it has got. The Android app kept this in its view
 * model (followNozzle and the state around it).
 *
 * Feed it every reading as it arrives ([add]); [PartView] calls [follow] every frame while it
 * is on screen and a job runs. Anything else that wants the nozzle's layer, such as a layer
 * stack, can read [layer], [layerCount] and [done], or call [follow] itself.
 */
@MainActor
@Observable
public final class NozzleWatch {
    /** Readings of the nozzle's real position are coming in. */
    public private(set) var reporting = false
    /**
     * The layer the nozzle is really on, when the firmware says where it is and the file's
     * layers are known. Nil otherwise, and then the Dashboard plugin's layer is the best there
     * is, which is what has been sent to the printer and so somewhat ahead of the nozzle.
     */
    public private(set) var layer: Int?
    /** How high that layer is, in millimetres. */
    public private(set) var height: Float?
    /** How many layers the file [layer] counts in has, once its layers have been read. */
    public private(set) var layerCount: Int?
    /** How far through [layer] the nozzle has got, 0..1. */
    public private(set) var done: Float = 0

    @ObservationIgnored private let follower = NozzleFollower()
    /** The layers of a file being read, or the map of a layer: one at a time is plenty. */
    @ObservationIgnored private var work: Task<Void, Never>?
    @ObservationIgnored private var workBusy = false
    @ObservationIgnored private var following: PartFile?

    public init() {}

    /** The app's steady clock, in milliseconds: what readings are timed by on arrival. */
    public nonisolated static func clock() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    /** A reading, as it arrives. */
    public func add(_ fix: NozzleFix, arrivedAt: Int64 = NozzleWatch.clock()) {
        follower.add(fix, arrivedAt: arrivedAt)
        if !reporting { reporting = true }
    }

    /** A line of OctoPrint's terminal log; true if it was a reading. */
    @discardableResult
    public func add(line: String) -> Bool {
        guard let fix = parseNozzleFix(line) else { return false }
        add(fix)
        return true
    }

    /** Forgets everything: for a new job, or a new server. */
    public func reset() {
        work?.cancel()
        work = nil
        workBusy = false
        follower.reset()
        following = nil
        layer = nil
        height = nil
        layerCount = nil
        reporting = false
        done = 0
    }

    /**
     * Brings the nozzle's real position up to [now], for a screen that is showing it. Called
     * for every frame while readings are coming in.
     *
     * - Parameter file: the file the job's part is drawn from (OctoPrint's own copy, for a job
     *   printed from the SD card).
     * - Parameter sent: the last layer the Dashboard plugin says was sent to the printer.
     * - Parameter fromCard: the job is printed from the printer's SD card, where the plugin's
     *   figures are left over from another job.
     */
    public func follow(library: PartLibrary, file: PartFile?, sent: Int?, fromCard: Bool, now: Int64 = NozzleWatch.clock()) {
        // a new job starts from nothing
        if file != following {
            if following != nil {
                work?.cancel()
                work = nil
                workBusy = false
                follower.useLayers([], [])
                layer = nil
                height = nil
                layerCount = nil
                done = 0
            }
            following = file
        }
        // The Dashboard plugin counts the layers OctoPrint sends, and it sends none of a file
        // the printer reads from its own SD card, so what it has is left over from another job.
        let live = follower.look(now, sent: fromCard ? NozzleFollower.ANY_LAYER : sent.flatMap { $0 > 0 ? $0 : nil })
        if !live {
            if reporting { reporting = false }
            if layer != nil { layer = nil }
            if height != nil { height = nil }
            return
        }
        if !reporting { reporting = true }
        guard let file else { return }
        if follower.knowsLayers {
            let now = follower.layer
            if now != layer {
                layer = now
                height = now.map { _ in follower.height }
                if let now { readMap(library, file, now) }
            }
            if done != follower.done { done = follower.done }
        } else {
            readLayers(library, file)
        }
    }

    private func readLayers(_ library: PartLibrary, _ file: PartFile) {
        if workBusy { return }
        workBusy = true
        work = Task { [weak self] in
            let found = await Task.detached { library.layersOf(file) }.value
            guard let self, !Task.isCancelled else { return }
            if let found {
                self.follower.useLayers(found.numbers, found.heights)
                self.layerCount = found.numbers.max()
            } else {
                // a file not drawn yet: this is asked again once a second or so, as the frames go by
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            if !Task.isCancelled { self.workBusy = false }
        }
    }

    private func readMap(_ library: PartLibrary, _ file: PartFile, _ layer: Int) {
        work?.cancel()
        workBusy = true
        work = Task { [weak self] in
            let map = await Task.detached { library.mapOf(file, number: layer) }.value
            guard let self, !Task.isCancelled else { return }
            if let map { self.follower.useMap(map) }
            self.workBusy = false
        }
    }
}
