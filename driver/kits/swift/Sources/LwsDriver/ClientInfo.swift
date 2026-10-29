/// The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
/// subject of the session's EARL report.
public struct ClientInfo: Sendable {

    /// The client's name.
    public var name: String
    /// Its version, or nil.
    public var version: String?
    /// An absolute http(s) URL, or nil.
    public var homepage: String?

    public init(name: String, version: String? = nil, homepage: String? = nil) {
        self.name = name
        self.version = version
        self.homepage = homepage
    }
}
