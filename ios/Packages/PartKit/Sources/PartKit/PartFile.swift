import Foundation

/// A printable file on OctoPrint, as much of it as PartKit needs: where it is, and what
/// changes when it is replaced by a new upload.
public struct PartFile: Hashable, Sendable {
    /// "local" or "sdcard".
    public let origin: String
    public let path: String
    public let display: String
    /// Upload time, epoch seconds.
    public let date: Int64
    /// Bytes.
    public let size: Int64

    public init(origin: String, path: String, display: String, date: Int64, size: Int64) {
        self.origin = origin
        self.path = path
        self.display = display
        self.date = date
        self.size = size
    }

    /// The printer's own SD card, which OctoPrint can't read files from.
    public var onCard: Bool { origin == "sdcard" }
}
