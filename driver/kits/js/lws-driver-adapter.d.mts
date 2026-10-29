// The types of lws-driver-adapter.mjs: each operation's arguments and result, as driver/PROTOCOL.md
// sections 4 and 5 give them. An optional member may be left out, undefined or null: the runner
// leaves out null and undefined members, and the protocol reads every one of them as absent.

/** A JSON value. */
export type Json = null | boolean | number | string | Json[] | { [member: string]: Json };

/** A JSON object. */
export type JsonObject = { [member: string]: Json };

/** A value, or a promise of it: every method may be async. */
export type Awaitable<T> = T | PromiseLike<T>;

/** An operation of section 5 but shutdown. */
export type Operation =
  | "configure"
  | "discover_storage"
  | "head"
  | "read"
  | "list_container"
  | "create"
  | "create_container"
  | "update"
  | "patch"
  | "delete"
  | "read_linkset"
  | "update_linkset"
  | "patch_linkset"
  | "move"
  | "request_access"
  | "get_access_request"
  | "list_access_requests"
  | "cancel_access_request"
  | "grant_access"
  | "get_access_grant"
  | "list_access_grants"
  | "revoke_access_grant"
  | "subscribe"
  | "list_subscriptions"
  | "get_subscription"
  | "unsubscribe"
  | "verify_notification"
  | "list_types"
  | "search";

/** Every operation of section 5 but shutdown, with the method of LwsDriverAdapter each calls. */
export declare const OPERATIONS: ReadonlyMap<Operation, string>;

// ---- the client and its authentication (sections 3 and 5.1) ----

/** The client under test, as the hello names it: it becomes the subject of the session's EARL report. */
export interface ClientInfo {
  /** The client's name. */
  name: string;
  /** Its version. */
  version?: string | null;
  /** An absolute http(s) URL. */
  homepage?: string | null;
}

/** A kind of configure authentication. */
export type AuthKind = "none" | "bearer" | "cid" | "openid" | "saml";

/** A private JSON Web Key. */
export interface PrivateJwk {
  kty: string;
  [member: string]: Json;
}

/** configure's `auth`: what the client authenticates with. */
export type Auth =
  | { type: "none" }
  | { type: "bearer"; token: string }
  | { type: "cid"; agent: string; privateJwk: PrivateJwk; kid?: string | null }
  | { type: "openid"; idToken: string }
  | { type: "saml"; assertion: string };

// ---- conventions (section 4) ----

/** A `body` argument: the UTF-8 bytes of `text`, the decoded `base64`, or the JSON serialization of `json`. */
export type RequestBody = { text: string } | { base64: string } | { json: Json };

/** A `body` in a result: `text` when the content type is textual, `base64` otherwise. */
export type ResponseBody = { text: string } | { base64: string };

/** A link the client is asked to attach (`links` arguments). */
export interface LinkArgument {
  href: string;
  rel: string;
}

/** An RFC 6902 operation. */
export interface JsonPatchOperation {
  op: "add" | "remove" | "replace" | "move" | "copy" | "test";
  path: string;
  from?: string;
  value?: Json;
}

/** An `application/lws-query+json` document. */
export type TypeQuery = { [member: string]: (string | string[])[] };

/** A linkset document. */
export interface LinksetDocument {
  linkset: Json[];
  [member: string]: Json;
}

// ---- result shapes (section 4.1) ----

/** A link of a response, one per relation type; `params` holds its parameters other than rel. */
export interface Link {
  href: string;
  rel: string;
  params?: { [name: string]: string } | null;
}

/** What a response's headers say. Only url and status are required: report what your client exposes. */
export interface Metadata {
  url: string;
  status: number;
  /** Verbatim, quotes and W/ included. */
  etag?: string | null;
  lastModified?: string | null;
  contentType?: string | null;
  contentLength?: number | null;
  links?: Link[] | null;
  types?: string[] | null;
  allow?: string[] | null;
  acceptPatch?: string[] | null;
}

/** One member of a listing or of search results; anything else the listing says may be added as received. */
export interface Item {
  id: string;
  types?: string[] | null;
  [member: string]: Json | undefined;
}

/** A listing, followed page by page up to `limit` items (default 1000). */
export interface Items {
  items: Item[];
  /** Whether there were more. */
  truncated: boolean;
}

export interface Created {
  location: string;
  metadata?: Metadata | null;
}

/** The result of a PUT or PATCH. */
export interface Update {
  status: number;
  etag?: string | null;
  metadata?: Metadata | null;
}

export interface Moved {
  status: number;
  location: string;
  metadata?: Metadata | null;
}

/** A service of a storage description. */
export interface Service {
  id?: string | null;
  types: string[];
  serviceEndpoint: string;
  [member: string]: Json | undefined;
}

/** A storage description. */
export interface Storage {
  id: string;
  /** Left out when the description has none. */
  storageRoot?: string | null;
  services: Service[];
  /** The document. */
  raw?: JsonObject | null;
}

export interface Subscription {
  subscription: string;
  raw?: JsonObject | null;
}

/** An activity of a verified notification. */
export interface Activity {
  id?: string | null;
  types: string[];
  object: string;
  objectTypes: string[];
}

/** The result of an operation that answers `{}`. */
export type Empty = { [member: string]: never };

// ---- arguments and results of each operation (section 5) ----

export interface UrlArgs {
  url: string;
}

export interface ConfigureArgs {
  auth: Auth;
  userAgent?: string | null;
  timeoutSeconds?: number | null;
}

export interface ConfigureResult {
  /** The agent the client acts as, when it knows one. */
  agent?: string | null;
}

export interface ReadArgs {
  url: string;
  accept?: string | null;
  ifNoneMatch?: string | null;
  rangeStart?: number | null;
  rangeEnd?: number | null;
}

export interface ReadResult {
  metadata: Metadata;
  notModified: boolean;
  body?: ResponseBody | null;
}

export interface ListContainerArgs {
  url: string;
  limit?: number | null;
}

export interface CreateArgs {
  container: string;
  body?: RequestBody | null;
  contentType?: string | null;
  slug?: string | null;
  /** IRIs. */
  types?: string[] | null;
  links?: LinkArgument[] | null;
}

export interface CreateContainerArgs {
  parent: string;
  slug?: string | null;
}

export interface UpdateArgs {
  url: string;
  body: RequestBody;
  contentType?: string | null;
  ifMatch?: string | null;
  ifNoneMatch?: string | null;
  /** Change the resource's content and its metadata in one request. */
  links?: LinkArgument[] | null;
}

export interface PatchArgs {
  url: string;
  patch: JsonPatchOperation[];
  ifMatch?: string | null;
}

export interface DeleteArgs {
  url: string;
  recursive?: boolean | null;
  ifMatch?: string | null;
}

export interface ReadLinksetResult {
  url: string;
  etag?: string | null;
  linkset: LinksetDocument;
  allow: string[];
  acceptPatch: string[];
}

export interface UpdateLinksetArgs {
  linksetUrl: string;
  linkset: LinksetDocument;
  ifMatch?: string | null;
}

export interface PatchLinksetArgs {
  linksetUrl: string;
  patch: JsonPatchOperation[];
  ifMatch?: string | null;
}

export interface MoveArgs {
  url: string;
  /** A container. */
  destination: string;
  ifMatch?: string | null;
}

export interface RequestAccessArgs {
  serviceUrl: string;
  /** An AccessRequest document. */
  request: JsonObject;
}

export interface GrantAccessArgs {
  serviceUrl: string;
  /** An AccessGrant document. */
  grant: JsonObject;
}

export interface LocationResult {
  location: string;
}

export interface DocumentResult {
  document: JsonObject;
}

export interface ServiceListArgs {
  serviceUrl: string;
  limit?: number | null;
}

export interface SubscribeArgs {
  serviceUrl: string;
  topics: string[];
  inbox: string;
  /** RFC 3339. */
  expires?: string | null;
}

export interface VerifyNotificationArgs {
  method: string;
  /** The inbox URL as it was registered. */
  url: string;
  /** Every header field with all its values. */
  headers: { [name: string]: string[] };
  bodyBase64: string;
  /** Never empty when present; absent, any storage is accepted. */
  trustedStorages?: string[] | null;
}

export interface VerifiedNotification {
  storage: string;
  keyid: string;
  activities: Activity[];
}

export interface ListTypesResult {
  types: Json[];
  truncated: boolean;
}

export interface SearchArgs {
  serviceUrl: string;
  query: TypeQuery;
  limit?: number | null;
}

// ---- the error and the adapter ----

/** An error kind of section 4.2. */
export type ErrorKind =
  | "http"
  | "authentication"
  | "protocol"
  | "verification"
  | "transport"
  | "invalidArguments"
  | "unsupported"
  | "internal";

/**
 * An operation's outcome that is not a result (section 4.2): it is reported to the driver, and the
 * adapter keeps running.
 */
export declare class AdapterError extends Error {
  /** @param status the HTTP status of the response that caused it; required for http */
  constructor(kind: ErrorKind, message: string, status?: number | null);
  readonly kind: ErrorKind;
  readonly status: number | null;
  /** A response with a status the operation does not succeed with. */
  static http(status: number, message: string): AdapterError;
  /** The operation, or an argument it was given, is not available in this client. */
  static unsupported(what: string): AdapterError;
  /** The request's arguments are missing or malformed. */
  static invalidArguments(message: string): AdapterError;
  /** No response: connection refused, TLS failure, timeout. */
  static transport(message: string): AdapterError;
}

/**
 * Your client, as Touchstone's driver sees it: one method per operation. Each takes the operation's
 * arguments and returns its result, and throws an AdapterError for an outcome that is not a result.
 * Every operation answers unsupported until you override it.
 */
export declare class LwsDriverAdapter {
  /** The client under test, as the session's report names it. */
  clientInfo(): ClientInfo;
  /** The kinds of configure authentication your client supports. */
  authKinds(): readonly AuthKind[];
  /** Section 5.1: build the client every later operation uses, with `auth`. Always implemented. */
  configure(args: ConfigureArgs): Awaitable<ConfigureResult | Empty | void>;

  // core
  /** Find the storage a URL belongs to and read its description. */
  discoverStorage(args: UrlArgs): Awaitable<Storage>;
  head(args: UrlArgs): Awaitable<Metadata>;
  read(args: ReadArgs): Awaitable<ReadResult>;
  /** Following every page. */
  listContainer(args: ListContainerArgs): Awaitable<Items>;
  create(args: CreateArgs): Awaitable<Created>;
  createContainer(args: CreateContainerArgs): Awaitable<Created>;
  update(args: UpdateArgs): Awaitable<Update>;
  patch(args: PatchArgs): Awaitable<Update>;
  delete(args: DeleteArgs): Awaitable<Empty | void>;
  /** `url` is the resource: find its linkset the way your client finds it. */
  readLinkset(args: UrlArgs): Awaitable<ReadLinksetResult>;
  updateLinkset(args: UpdateLinksetArgs): Awaitable<Update>;
  patchLinkset(args: PatchLinksetArgs): Awaitable<Update>;
  move(args: MoveArgs): Awaitable<Moved>;
  requestAccess(args: RequestAccessArgs): Awaitable<LocationResult>;
  getAccessRequest(args: UrlArgs): Awaitable<DocumentResult>;
  listAccessRequests(args: ServiceListArgs): Awaitable<Items>;
  cancelAccessRequest(args: UrlArgs): Awaitable<Empty | void>;
  grantAccess(args: GrantAccessArgs): Awaitable<LocationResult>;
  getAccessGrant(args: UrlArgs): Awaitable<DocumentResult>;
  listAccessGrants(args: ServiceListArgs): Awaitable<Items>;
  revokeAccessGrant(args: UrlArgs): Awaitable<Empty | void>;

  // notifications
  subscribe(args: SubscribeArgs): Awaitable<Subscription>;
  listSubscriptions(args: ServiceListArgs): Awaitable<Items>;
  getSubscription(args: UrlArgs): Awaitable<Subscription>;
  unsubscribe(args: UrlArgs): Awaitable<Empty | void>;
  /** Section 5.2: verify the delivery the way your client does, or throw a verification error. */
  verifyNotification(args: VerifyNotificationArgs): Awaitable<VerifiedNotification>;

  // index
  listTypes(args: ServiceListArgs): Awaitable<ListTypesResult>;
  /** Following every page. */
  search(args: SearchArgs): Awaitable<Items>;
}
