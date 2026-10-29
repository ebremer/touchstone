// swift-tools-version:6.0
// The Swift starter kit for touchstone-driver/1 (driver/PROTOCOL.md). Copy this directory into your
// client's repository, rename the stub, and build: swift build -c release --product stub, then
// .build/release/stub.
import PackageDescription

let package = Package(
    name: "lws-driver-adapter",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "LwsDriver", targets: ["LwsDriver"]),
        .executable(name: "stub", targets: ["stub"]),
    ],
    dependencies: [
        // Your client library goes here.
    ],
    targets: [
        // The protocol: copy it as it is.
        .target(name: "LwsDriver"),
        // Your adapter: add your client library to its dependencies.
        .executableTarget(name: "stub", dependencies: ["LwsDriver"]),
    ]
)
