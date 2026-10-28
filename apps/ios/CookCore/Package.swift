// swift-tools-version: 5.9
import PackageDescription

#if os(Linux)
    let cryptoPackages: [Package.Dependency] = [.package(url: "https://github.com/apple/swift-crypto.git", from: "3.9.0")]
    let coreDependencies: [Target.Dependency] = [.product(name: "Crypto", package: "swift-crypto")]
#else
    // Apple builds use the system CryptoKit without fetching a separate implementation.
    let cryptoPackages: [Package.Dependency] = []
    let coreDependencies: [Target.Dependency] = []
#endif

let package = Package(
    name: "CookCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CookCore", targets: ["CookCore"]),
    ],
    dependencies: cryptoPackages,
    targets: [
        .target(name: "CookCore", dependencies: coreDependencies),
        .testTarget(name: "CookCoreTests", dependencies: ["CookCore"]),
    ]
)
