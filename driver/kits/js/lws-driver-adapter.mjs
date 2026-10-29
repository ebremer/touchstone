// Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
// one method per operation. Each takes the operation's arguments and returns its result, both as the
// JSON objects the protocol describes, and throws an AdapterError for an outcome that is not a
// result, such as a 404. A method may be async.
//
// Implement the operations your client has, by calling your client's own operation with its own
// options. Leave the others: they answer unsupported, and the rules that need them stay untested,
// never failed. adapter-runner.mjs lists the ones you implement in the hello.
//
// Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The point
// is to see what the client sends.
//
// lws-driver-adapter.d.mts gives the types of every argument and result.

/** The error kinds of section 4.2. */
const KINDS = new Set([
  "http",
  "authentication",
  "protocol",
  "verification",
  "transport",
  "invalidArguments",
  "unsupported",
  "internal",
]);

/**
 * An operation's outcome that is not a result (section 4.2): it is reported to the driver, and the
 * adapter keeps running. A 409 from deleting a non-empty container is exactly what a drive may be
 * looking for.
 */
export class AdapterError extends Error {
  /**
   * @param kind    http, authentication, protocol, verification, transport, invalidArguments,
   *                unsupported or internal
   * @param message what happened
   * @param status  the HTTP status of the response that caused it; required for http
   */
  constructor(kind, message, status) {
    if (!KINDS.has(kind)) {
      throw new TypeError(`${kind} is not an error kind of touchstone-driver/1`);
    }
    if (status !== undefined && status !== null && !(Number.isInteger(status) && status >= 100 && status <= 599)) {
      throw new TypeError(`${status} is not an HTTP status`);
    }
    if (kind === "http" && (status === undefined || status === null)) {
      throw new TypeError("an http error has a status");
    }
    super(message);
    this.name = "AdapterError";
    this.kind = kind;
    this.status = status ?? null;
  }

  /** A response with a status the operation does not succeed with. */
  static http(status, message) {
    return new AdapterError("http", message, status);
  }

  /** The operation, or an argument it was given, is not available in this client. */
  static unsupported(what) {
    return new AdapterError("unsupported", `${what} is not available in this client`);
  }

  /** The request's arguments are missing or malformed. */
  static invalidArguments(message) {
    return new AdapterError("invalidArguments", message);
  }

  /** No response: connection refused, TLS failure, timeout. */
  static transport(message) {
    return new AdapterError("transport", message);
  }
}

/** Every operation of section 5 but shutdown, with the method of LwsDriverAdapter each calls. */
export const OPERATIONS = new Map([
  ["configure", "configure"],
  ["discover_storage", "discoverStorage"],
  ["head", "head"],
  ["read", "read"],
  ["list_container", "listContainer"],
  ["create", "create"],
  ["create_container", "createContainer"],
  ["update", "update"],
  ["patch", "patch"],
  ["delete", "delete"],
  ["read_linkset", "readLinkset"],
  ["update_linkset", "updateLinkset"],
  ["patch_linkset", "patchLinkset"],
  ["move", "move"],
  ["request_access", "requestAccess"],
  ["get_access_request", "getAccessRequest"],
  ["list_access_requests", "listAccessRequests"],
  ["cancel_access_request", "cancelAccessRequest"],
  ["grant_access", "grantAccess"],
  ["get_access_grant", "getAccessGrant"],
  ["list_access_grants", "listAccessGrants"],
  ["revoke_access_grant", "revokeAccessGrant"],
  ["subscribe", "subscribe"],
  ["list_subscriptions", "listSubscriptions"],
  ["get_subscription", "getSubscription"],
  ["unsubscribe", "unsubscribe"],
  ["verify_notification", "verifyNotification"],
  ["list_types", "listTypes"],
  ["search", "search"],
]);

/**
 * Extend it, and override clientInfo, authKinds, configure and the operations your client has.
 */
export class LwsDriverAdapter {
  /** The client under test, as the session's report names it: {name, version?, homepage?}. */
  clientInfo() {
    throw new Error(`${this.constructor.name} must implement clientInfo()`);
  }

  /** The kinds of configure authentication your client supports: none, bearer, cid, openid, saml. */
  authKinds() {
    throw new Error(`${this.constructor.name} must implement authKinds()`);
  }

  /**
   * Section 5.1: `auth, userAgent?, timeoutSeconds?` → `{agent?}`. Build the client every later
   * operation uses, with `auth`. Always implemented.
   */
  configure(args) {
    throw new Error(`${this.constructor.name} must implement configure()`);
  }

  // ---- core ----

  /** `url` → Storage: find the storage a URL belongs to and read its description. */
  discoverStorage(args) {
    throw AdapterError.unsupported("discover_storage");
  }

  /** `url` → Metadata. */
  head(args) {
    throw AdapterError.unsupported("head");
  }

  /** `url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?` → `{metadata, notModified, body?}`. */
  read(args) {
    throw AdapterError.unsupported("read");
  }

  /** `url, limit?` → `{items, truncated}`, following every page. */
  listContainer(args) {
    throw AdapterError.unsupported("list_container");
  }

  /** `container, body?, contentType?, slug?, types?, links?` → Created. */
  create(args) {
    throw AdapterError.unsupported("create");
  }

  /** `parent, slug?` → Created. */
  createContainer(args) {
    throw AdapterError.unsupported("create_container");
  }

  /** `url, body, contentType?, ifMatch?, ifNoneMatch?, links?` → Update. */
  update(args) {
    throw AdapterError.unsupported("update");
  }

  /** `url, patch, ifMatch?` → Update. */
  patch(args) {
    throw AdapterError.unsupported("patch");
  }

  /** `url, recursive?, ifMatch?` → `{}`. */
  delete(args) {
    throw AdapterError.unsupported("delete");
  }

  /** `url` (the resource) → `{url, etag?, linkset, allow, acceptPatch}`. */
  readLinkset(args) {
    throw AdapterError.unsupported("read_linkset");
  }

  /** `linksetUrl, linkset, ifMatch?` → Update. */
  updateLinkset(args) {
    throw AdapterError.unsupported("update_linkset");
  }

  /** `linksetUrl, patch, ifMatch?` → Update. */
  patchLinkset(args) {
    throw AdapterError.unsupported("patch_linkset");
  }

  /** `url, destination, ifMatch?` → Moved. */
  move(args) {
    throw AdapterError.unsupported("move");
  }

  /** `serviceUrl, request` → `{location}`. */
  requestAccess(args) {
    throw AdapterError.unsupported("request_access");
  }

  /** `url` → `{document}`. */
  getAccessRequest(args) {
    throw AdapterError.unsupported("get_access_request");
  }

  /** `serviceUrl, limit?` → `{items, truncated}`. */
  listAccessRequests(args) {
    throw AdapterError.unsupported("list_access_requests");
  }

  /** `url` → `{}`. */
  cancelAccessRequest(args) {
    throw AdapterError.unsupported("cancel_access_request");
  }

  /** `serviceUrl, grant` → `{location}`. */
  grantAccess(args) {
    throw AdapterError.unsupported("grant_access");
  }

  /** `url` → `{document}`. */
  getAccessGrant(args) {
    throw AdapterError.unsupported("get_access_grant");
  }

  /** `serviceUrl, limit?` → `{items, truncated}`. */
  listAccessGrants(args) {
    throw AdapterError.unsupported("list_access_grants");
  }

  /** `url` → `{}`. */
  revokeAccessGrant(args) {
    throw AdapterError.unsupported("revoke_access_grant");
  }

  // ---- notifications ----

  /** `serviceUrl, topics, inbox, expires?` → Subscription. */
  subscribe(args) {
    throw AdapterError.unsupported("subscribe");
  }

  /** `serviceUrl, limit?` → `{items, truncated}`. */
  listSubscriptions(args) {
    throw AdapterError.unsupported("list_subscriptions");
  }

  /** `url` → Subscription. */
  getSubscription(args) {
    throw AdapterError.unsupported("get_subscription");
  }

  /** `url` → `{}`. */
  unsubscribe(args) {
    throw AdapterError.unsupported("unsubscribe");
  }

  /**
   * Section 5.2: `method, url, headers, bodyBase64, trustedStorages?` → `{storage, keyid,
   * activities}`, or a verification error. Verify the delivery the way your client does.
   */
  verifyNotification(args) {
    throw AdapterError.unsupported("verify_notification");
  }

  // ---- index ----

  /** `serviceUrl, limit?` → `{types, truncated}`. */
  listTypes(args) {
    throw AdapterError.unsupported("list_types");
  }

  /** `serviceUrl, query, limit?` → `{items, truncated}`, following every page. */
  search(args) {
    throw AdapterError.unsupported("search");
  }
}
