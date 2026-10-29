/// Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
/// one method per operation. Each takes the operation's arguments and returns its result, both as the
/// JSON objects the protocol describes (`[String: Any]`, as Foundation's `JSONSerialization` reads and
/// writes them), and throws an `AdapterError` for an outcome that is not a result, such as a 404.
///
/// Implement the operations your client has, by calling your client's own operation with its own
/// options, and list each in `operations`: Swift cannot see which methods a type implements, so you
/// say it. `AdapterRunner` lists them in the hello and calls only them. Leave the others: they answer
/// `unsupported`, and the rules that need them stay untested, never failed.
///
/// Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The
/// point is to see what the client sends.
///
/// In a result, report what your client exposes and leave out the rest: an optional member may be
/// absent (section 4.1).
public protocol LwsDriverAdapter: AnyObject {

    /// The client under test, as the session's report names it.
    var client: ClientInfo { get }

    /// The kinds of `configure` authentication your client supports: none, bearer, cid, openid, saml.
    var auth: Set<String> { get }

    /// The operations you implement, besides `configure`. The runner lists them in the hello, and
    /// answers every other one `unsupported` without calling it.
    var operations: Set<DriverOperation> { get }

    /// Section 5.1: build the client every later operation uses, with `auth`. Always implemented.
    func configure(_ args: [String: Any]) async throws -> [String: Any]

    // ---- core ----

    /// `url` → Storage: find the storage a URL belongs to and read its description.
    func discoverStorage(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → Metadata.
    func head(_ args: [String: Any]) async throws -> [String: Any]

    /// `url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?` → `{metadata, notModified, body?}`.
    func read(_ args: [String: Any]) async throws -> [String: Any]

    /// `url, limit?` → `{items, truncated}`, following every page.
    func listContainer(_ args: [String: Any]) async throws -> [String: Any]

    /// `container, body?, contentType?, slug?, types?, links?` → Created.
    func create(_ args: [String: Any]) async throws -> [String: Any]

    /// `parent, slug?` → Created.
    func createContainer(_ args: [String: Any]) async throws -> [String: Any]

    /// `url, body, contentType?, ifMatch?, ifNoneMatch?, links?` → Update.
    func update(_ args: [String: Any]) async throws -> [String: Any]

    /// `url, patch, ifMatch?` → Update.
    func patch(_ args: [String: Any]) async throws -> [String: Any]

    /// `url, recursive?, ifMatch?` → `{}`.
    func delete(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` (the resource) → `{url, etag?, linkset, allow, acceptPatch}`.
    func readLinkset(_ args: [String: Any]) async throws -> [String: Any]

    /// `linksetUrl, linkset, ifMatch?` → Update.
    func updateLinkset(_ args: [String: Any]) async throws -> [String: Any]

    /// `linksetUrl, patch, ifMatch?` → Update.
    func patchLinkset(_ args: [String: Any]) async throws -> [String: Any]

    /// `url, destination, ifMatch?` → Moved.
    func move(_ args: [String: Any]) async throws -> [String: Any]

    /// `serviceUrl, request` → `{location}`.
    func requestAccess(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → `{document}`.
    func getAccessRequest(_ args: [String: Any]) async throws -> [String: Any]

    /// `serviceUrl, limit?` → `{items, truncated}`.
    func listAccessRequests(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → `{}`.
    func cancelAccessRequest(_ args: [String: Any]) async throws -> [String: Any]

    /// `serviceUrl, grant` → `{location}`.
    func grantAccess(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → `{document}`.
    func getAccessGrant(_ args: [String: Any]) async throws -> [String: Any]

    /// `serviceUrl, limit?` → `{items, truncated}`.
    func listAccessGrants(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → `{}`.
    func revokeAccessGrant(_ args: [String: Any]) async throws -> [String: Any]

    // ---- notifications ----

    /// `serviceUrl, topics, inbox, expires?` → Subscription.
    func subscribe(_ args: [String: Any]) async throws -> [String: Any]

    /// `serviceUrl, limit?` → `{items, truncated}`.
    func listSubscriptions(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → Subscription.
    func getSubscription(_ args: [String: Any]) async throws -> [String: Any]

    /// `url` → `{}`.
    func unsubscribe(_ args: [String: Any]) async throws -> [String: Any]

    /// Section 5.2: `method, url, headers, bodyBase64, trustedStorages?` → `{storage, keyid,
    /// activities}`, or a `verification` error. Verify the delivery the way your client does.
    func verifyNotification(_ args: [String: Any]) async throws -> [String: Any]

    // ---- index ----

    /// `serviceUrl, limit?` → `{types, truncated}`.
    func listTypes(_ args: [String: Any]) async throws -> [String: Any]

    /// `serviceUrl, query, limit?` → `{items, truncated}`, following every page.
    func search(_ args: [String: Any]) async throws -> [String: Any]
}

/// The operations of section 5 an adapter may implement, by their names in the protocol.
/// `configure` and `shutdown` are always implemented, so they are not among them.
public enum DriverOperation: String, CaseIterable, Sendable {
    case discoverStorage = "discover_storage"
    case head
    case read
    case listContainer = "list_container"
    case create
    case createContainer = "create_container"
    case update
    case patch
    case delete
    case readLinkset = "read_linkset"
    case updateLinkset = "update_linkset"
    case patchLinkset = "patch_linkset"
    case move
    case requestAccess = "request_access"
    case getAccessRequest = "get_access_request"
    case listAccessRequests = "list_access_requests"
    case cancelAccessRequest = "cancel_access_request"
    case grantAccess = "grant_access"
    case getAccessGrant = "get_access_grant"
    case listAccessGrants = "list_access_grants"
    case revokeAccessGrant = "revoke_access_grant"
    case subscribe
    case listSubscriptions = "list_subscriptions"
    case getSubscription = "get_subscription"
    case unsubscribe
    case verifyNotification = "verify_notification"
    case listTypes = "list_types"
    case search
}

/// Every operation answers `unsupported` until you implement it.
extension LwsDriverAdapter {
    public func discoverStorage(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("discover_storage") }
    public func head(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("head") }
    public func read(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("read") }
    public func listContainer(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("list_container") }
    public func create(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("create") }
    public func createContainer(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("create_container") }
    public func update(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("update") }
    public func patch(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("patch") }
    public func delete(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("delete") }
    public func readLinkset(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("read_linkset") }
    public func updateLinkset(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("update_linkset") }
    public func patchLinkset(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("patch_linkset") }
    public func move(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("move") }
    public func requestAccess(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("request_access") }
    public func getAccessRequest(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("get_access_request") }
    public func listAccessRequests(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("list_access_requests") }
    public func cancelAccessRequest(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("cancel_access_request") }
    public func grantAccess(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("grant_access") }
    public func getAccessGrant(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("get_access_grant") }
    public func listAccessGrants(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("list_access_grants") }
    public func revokeAccessGrant(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("revoke_access_grant") }
    public func subscribe(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("subscribe") }
    public func listSubscriptions(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("list_subscriptions") }
    public func getSubscription(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("get_subscription") }
    public func unsubscribe(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("unsubscribe") }
    public func verifyNotification(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("verify_notification") }
    public func listTypes(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("list_types") }
    public func search(_ args: [String: Any]) async throws -> [String: Any] { throw AdapterError.unsupported("search") }
}
