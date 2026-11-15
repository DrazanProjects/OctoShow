// swift-tools-version: 6.0
// PartKit: OctoShow's "part drawn layer by layer" — reads a job's G-code, builds the part as
// a stack of little columns, and draws it as it stands while it prints. Ported from the
// Android app (octo/GcodePreview.kt, octo/Nozzle.kt, octo/Previews.kt, ui/Preview.kt).
import PackageDescription

let package = Package(
    name: "PartKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "PartKit", targets: ["PartKit"]),
    ],
    targets: [
        .target(
            name: "PartKit",
            // Optimised even in Debug: Xcode's Run button makes a Debug build, and unoptimised
            // Swift takes tens of seconds to read a part's G-code where this takes about one.
            swiftSettings: [.unsafeFlags(["-O"], .when(configuration: .debug))],
            linkerSettings: [.linkedLibrary("z")]
        ),
        .testTarget(
            name: "PartKitTests",
            dependencies: ["PartKit"]
        ),
    ],
    swiftLanguageModes: [.v5]
)
