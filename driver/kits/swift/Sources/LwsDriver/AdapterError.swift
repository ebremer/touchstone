/// An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to the
/// driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly what a
/// drive may be looking for.
public struct AdapterError: Error, Sendable, CustomStringConvertible {

    /// http, authentication, protocol, verification, transport, invalidArguments, unsupported or internal.
    public var kind: String
    /// The HTTP status of the response that caused it, or nil; required for http.
    public var status: Int?
    public var message: String

    public init(kind: String, status: Int? = nil, message: String) {
        self.kind = kind
        self.status = status
        self.message = message
    }

    public var description: String { "\(kind): \(message)" }

    /// A response with a status the operation does not succeed with.
    public static func http(_ status: Int, _ message: String) -> AdapterError {
        AdapterError(kind: "http", status: status, message: message)
    }

    /// The operation, or an argument it was given, is not available in this client.
    public static func unsupported(_ what: String) -> AdapterError {
        AdapterError(kind: "unsupported", message: "\(what) is not available in this client")
    }

    /// The request's arguments are missing or malformed.
    public static func invalidArguments(_ message: String) -> AdapterError {
        AdapterError(kind: "invalidArguments", message: message)
    }

    /// No response: connection refused, TLS failure, timeout.
    public static func transport(_ message: String) -> AdapterError {
        AdapterError(kind: "transport", message: message)
    }
}
