import Foundation
import LwsDriver

/// The starting point: an adapter that implements only `configure`, so every other operation
/// answers `unsupported`. It passes `touchstone driver check` as it is. Replace the client
/// name, keep your client in a property that `configure` builds, and implement the operations your
/// client has, one by one, adding each to `operations`.
final class StubAdapter: LwsDriverAdapter {

    let client = ClientInfo(name: "My LWS client (Swift)", version: "0.0.0")

    let auth: Set<String> = ["none"]

    let operations: Set<DriverOperation> = []

    func configure(_ args: [String: Any]) async throws -> [String: Any] {
        let type = (args["auth"] as? [String: Any])?["type"] as? String ?? ""
        guard auth.contains(type) else {
            throw AdapterError.unsupported("authentication \(type)")
        }
        // Build your client here, with the authentication args["auth"] describes.
        return [:]
    }
}

await AdapterRunner.serve(StubAdapter())
exit(0)
