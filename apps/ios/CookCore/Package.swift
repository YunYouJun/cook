// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CookCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CookCore", targets: ["CookCore"]),
    ],
    targets: [
        .target(name: "CookCore"),
        .testTarget(name: "CookCoreTests", dependencies: ["CookCore"]),
    ]
)
