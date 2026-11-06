// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AIStockCore",
    defaultLocalization: "ko",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "AIStockCore", targets: ["AIStockCore"])],
    targets: [
        .target(name: "AIStockCore", resources: [.process("Resources")]),
        .testTarget(name: "AIStockCoreTests", dependencies: ["AIStockCore"]),
    ]
)
