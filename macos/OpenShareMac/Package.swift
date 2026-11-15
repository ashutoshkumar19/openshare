// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OpenShareMac",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "OpenShareMac", targets: ["OpenShareMac"]),
        .library(name: "OpenShareCore", targets: ["OpenShareCore"])
    ],
    targets: [
        .target(name: "OpenShareCore", path: "Sources/Core"),
        .executableTarget(
            name: "OpenShareMac",
            dependencies: ["OpenShareCore"],
            path: "Sources",
            exclude: ["Core"]
        )
    ]
)
