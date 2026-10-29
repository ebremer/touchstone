import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#endif

/// The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
/// writes the hello, reads one request a line from stdin, calls the method of `LwsDriverAdapter`
/// for its operation, and writes one response a line to stdout. stdout carries protocol lines only:
/// log to stderr. While it serves, `print` goes to stderr too, so a stray print cannot break the
/// protocol.
///
///     await AdapterRunner.serve(MyAdapter())
///     exit(0)
///
/// Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.
public enum AdapterRunner {

    public static let protocolName = "touchstone-driver/1"

    /// Serves the driver on stdin and stdout until end of input or `shutdown`.
    public static func serve(_ adapter: some LwsDriverAdapter) async {
        let out = protocolOutput()
        write(out, hello(adapter))
        while let line = readLine(strippingNewline: true) {
            if line.allSatisfy(\.isWhitespace) {
                continue
            }
            guard let request = (try? JSONSerialization.jsonObject(with: Data(line.utf8))) as? [String: Any] else {
                write(out, failure(NSNull(), .invalidArguments("a request is a JSON object")))
                continue
            }
            let id = request["id"] ?? NSNull()
            let op = request["op"] as? String ?? ""
            if op == "shutdown" {
                write(out, success(id, [:]))
                return
            }
            let args = request["args"] as? [String: Any] ?? [:]
            var response: [String: Any]
            do {
                response = success(id, try await call(adapter, op, args))
            } catch let e as AdapterError {
                response = failure(id, e)
            } catch {
                // A bug in the adapter or the client: report it, and keep running.
                response = failure(id, internalError("\(type(of: error)): \(error)"))
            }
            fflush(nil)  // whatever the operation printed, to the log now
            if !JSONSerialization.isValidJSONObject(response) {
                response = failure(id, internalError("the result of \(op) is not JSON"))
            }
            write(out, response)
        }
    }

    /// The hello: the client, its authentication, and every operation the adapter implements.
    static func hello(_ adapter: some LwsDriverAdapter) -> [String: Any] {
        let info = adapter.client
        var client: [String: Any] = ["name": info.name]
        if let version = info.version {
            client["version"] = version
        }
        if let homepage = info.homepage {
            client["homepage"] = homepage
        }
        let implemented = adapter.operations
        let operations = ["configure"] + DriverOperation.allCases.filter { implemented.contains($0) }.map(\.rawValue)
        let hello: [String: Any] = [
            "protocol": protocolName,
            "client": client,
            "language": "swift",
            "auth": adapter.auth.sorted(),
            "operations": operations,
        ]
        return ["hello": hello]
    }

    /// Calls the method of an operation the adapter lists; any other answers unsupported.
    private static func call(_ adapter: some LwsDriverAdapter, _ op: String, _ args: [String: Any]) async throws -> [String: Any] {
        if op == "configure" {
            return try await adapter.configure(args)
        }
        guard let operation = DriverOperation(rawValue: op), adapter.operations.contains(operation) else {
            throw AdapterError.unsupported(op)
        }
        switch operation {
        case .discoverStorage: return try await adapter.discoverStorage(args)
        case .head: return try await adapter.head(args)
        case .read: return try await adapter.read(args)
        case .listContainer: return try await adapter.listContainer(args)
        case .create: return try await adapter.create(args)
        case .createContainer: return try await adapter.createContainer(args)
        case .update: return try await adapter.update(args)
        case .patch: return try await adapter.patch(args)
        case .delete: return try await adapter.delete(args)
        case .readLinkset: return try await adapter.readLinkset(args)
        case .updateLinkset: return try await adapter.updateLinkset(args)
        case .patchLinkset: return try await adapter.patchLinkset(args)
        case .move: return try await adapter.move(args)
        case .requestAccess: return try await adapter.requestAccess(args)
        case .getAccessRequest: return try await adapter.getAccessRequest(args)
        case .listAccessRequests: return try await adapter.listAccessRequests(args)
        case .cancelAccessRequest: return try await adapter.cancelAccessRequest(args)
        case .grantAccess: return try await adapter.grantAccess(args)
        case .getAccessGrant: return try await adapter.getAccessGrant(args)
        case .listAccessGrants: return try await adapter.listAccessGrants(args)
        case .revokeAccessGrant: return try await adapter.revokeAccessGrant(args)
        case .subscribe: return try await adapter.subscribe(args)
        case .listSubscriptions: return try await adapter.listSubscriptions(args)
        case .getSubscription: return try await adapter.getSubscription(args)
        case .unsubscribe: return try await adapter.unsubscribe(args)
        case .verifyNotification: return try await adapter.verifyNotification(args)
        case .listTypes: return try await adapter.listTypes(args)
        case .search: return try await adapter.search(args)
        }
    }

    private static func success(_ id: Any, _ result: [String: Any]) -> [String: Any] {
        ["id": id, "ok": true, "result": result]
    }

    private static func failure(_ id: Any, _ e: AdapterError) -> [String: Any] {
        var error: [String: Any] = ["kind": e.kind, "message": e.message.isEmpty ? e.kind : e.message]
        if let status = e.status {
            error["status"] = status
        }
        return ["id": id, "ok": false, "error": error]
    }

    /// An internal error, which goes to the log too.
    private static func internalError(_ message: String) -> AdapterError {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        return AdapterError(kind: "internal", message: message)
    }

    /// The protocol's stream, stdout, which carries nothing else from now on: file descriptor 1 becomes
    /// stderr, so that whatever else the process prints goes to the log.
    private static func protocolOutput() -> FileHandle {
        #if canImport(Darwin) || canImport(Glibc) || canImport(Musl)
        fflush(nil)
        let fd = dup(STDOUT_FILENO)
        if fd >= 0 && dup2(STDERR_FILENO, STDOUT_FILENO) >= 0 {
            return FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        }
        #endif
        return FileHandle.standardOutput
    }

    private static func write(_ out: FileHandle, _ message: [String: Any]) {
        guard var line = try? JSONSerialization.data(withJSONObject: message, options: [.withoutEscapingSlashes]) else {
            return
        }
        line.append(0x0A)
        try? out.write(contentsOf: line)
    }
}
