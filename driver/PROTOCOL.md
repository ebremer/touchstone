# The client driver protocol, `touchstone-driver/1`

**Status: frozen on 2026-10-29, at Gate D (DECISIONS.md D-0103, D-0104).** A change needs a
DECISIONS.md entry, and only the additions of section 8 stay within `/1`.

Touchstone can drive an LWS client through a client session instead of waiting for a person
to (CLIENT-TESTING.md section 14). It does this through an **adapter**: a small program, written
by the client's developers in the client's own language, that runs the operations of section 5
with the client and reports what happened. This document is the whole contract between
Touchstone's driver and an adapter. [`protocol.schema.json`](protocol.schema.json) is its JSON
Schema, and where the two disagree this document wins.

Driving is a second way in. A client that has no adapter is tested as before: its developer
drives it against a session, by hand or from its own test suite.

## 1. Principles

- **The adapter is a thin, faithful wrapper.** It calls the client's own operation with the
  client's own options, and reports the client's own result or error. It never retries, falls
  back or fixes anything up on the client's behalf. The point of driving a client is to see what
  the **client** sends.
- **The traffic decides, never the adapter.** Touchstone judges the requests the session
  receives, by the same rules as when a person drives. Nothing an adapter reports decides a
  rule's outcome. A result or an error only tells the driver whether it can go on.
- **An operation a client lacks costs it nothing.** The adapter lists the operations it
  implements. A rule whose drive needs another stays *untested*, which is coverage the session
  did not have, never a failure.
- **Named after the drafts, not after a library.** The operations are the ones the LWS drafts
  define, named for what they do. Their arguments are what a client needs to be told, and their
  results are what the drafts say a server answers. No client library's API is the yardstick.

## 2. Framing

- The driver writes requests to the adapter's **stdin**, and the adapter writes responses to its
  **stdout**: UTF-8 JSON, one object per line, terminated by `\n`.
- The adapter flushes stdout after every line.
- **stdout carries protocol messages only.** Logs, diagnostics and stack traces go to **stderr**,
  which the driver keeps as the adapter's log.
- The adapter handles one request at a time, in the order received. The driver never sends a
  second request before the first is answered.
- On end of input (stdin closed) the adapter exits with status 0.
- A line is at most 16 MiB. The driver treats a longer line, a line that is not JSON, or a
  response with an unknown `id` as a broken adapter: it stops the adapter and ends that client's
  drive. Nothing an adapter writes is ever run, evaluated or used as a path.

## 3. Messages

**Hello.** Before reading anything, the adapter writes one line announcing itself:

```json
{"hello": {"protocol": "touchstone-driver/1",
           "client": {"name": "lws-client-go", "version": "0.1.0", "homepage": "https://github.com/ebremer/lws-client"},
           "language": "go",
           "auth": ["bearer", "cid", "openid"],
           "operations": ["configure", "discover_storage", "read", "..."]}}
```

- `client` names the client under test. It becomes the session's client under test, the EARL
  subject of its report. `version` and `homepage` are optional.
- `language` is optional and only informative.
- `auth` lists the kinds of `configure` authentication (section 5.1) the client supports.
- `operations` lists every operation of section 5 the adapter implements. `configure` and
  `shutdown` are always implemented, listed or not.

**Request.**

```json
{"id": 7, "op": "read", "args": {"url": "https://storage.example/notes/a.txt"}}
```

`id` is a positive integer, `op` an operation name, `args` an object, possibly empty.

**Response.** Exactly one per request, with the request's `id`:

```json
{"id": 7, "ok": true, "result": { "...": "..." }}
{"id": 7, "ok": false, "error": {"kind": "http", "status": 404, "message": "GET https://… → 404"}}
```

A response that is not `ok` is an **outcome**, not a breakdown: a `409` from deleting a
non-empty container is exactly what a drive may be looking for. The adapter catches every error
the client raises, reports it this way, and keeps running. A request line that is not a JSON
object gets an `invalidArguments` error with `"id": null`, and an unknown `op` gets
`unsupported`.

## 4. Conventions

**Absent values.** An optional argument or result member may be omitted or `null`; both mean
absent.

**URLs** in arguments are absolute. URLs in results are absolute, as the client resolved them.

**Request bodies** (`body` arguments) are one of:

| Form | Meaning |
|---|---|
| `{"text": "Hello"}` | the UTF-8 bytes of the string |
| `{"base64": "AAEC"}` | the decoded bytes (standard base64, padded) |
| `{"json": {"name": "Alice"}}` | the JSON serialization of the value; `contentType` defaults to `application/json` |

**Response bodies** (`body` in results) are `{"text": "…"}` when the content type is textual
(`text/*`, `application/json`, `application/xml`, or a type with a `+json` or `+xml` suffix), and
`{"base64": "…"}` otherwise, or when there is no content type.

**Links** (`links` arguments) are `[{"href": "…", "rel": "…"}]`: metadata the client is asked to
attach, in whatever way it attaches metadata.

**JSON Patch** arguments (`patch`) are an RFC 6902 operations array. The client sends them in its
own way, with its own media type.

**Type queries** (`query`) are the `application/lws-query+json` document itself. The client
chooses how to send it, including the query format, from what the service advertises.

**Sequences.** The `list_*` operations and `search` follow every page, as the client follows
them, up to `limit` items (default 1000), and report `truncated` when there were more.

### 4.1 Result shapes

**Metadata**, what the response's headers say:

```json
{"url": "https://…/a.txt", "status": 200,
 "etag": "\"3\"", "lastModified": "Tue, 06 Oct 2026 12:00:00 GMT",
 "contentType": "text/plain", "contentLength": 11,
 "links": [{"href": "https://…/a.txt.meta", "rel": "linkset", "params": {"type": "application/linkset+json"}}],
 "types": ["https://www.w3.org/ns/lws#DataResource"],
 "allow": ["GET", "HEAD", "PUT", "PATCH", "DELETE"], "acceptPatch": ["application/json-patch+json"]}
```

`etag` is verbatim, quotes and `W/` included. `links` holds every link, one per relation type,
`params` holding its parameters other than `rel`. `types`, `links`, `allow` and `acceptPatch`
are always present, possibly empty. Only `url` and `status` are required: an adapter reports what
its client exposes.

**Item**, one member of a listing or of search results:

```json
{"id": "https://…/a.txt", "types": ["DataResource"]}
```

`id` is required; anything else the listing says, such as `format`, `size` or `modified`, may be
added as received.

**Created**: `{"location": "https://…/hello.txt", "metadata": Metadata}`.

**Update**, the result of a PUT or PATCH: `{"status": 204, "etag": "\"4\"", "metadata": Metadata}`.

**Moved**: `{"status": 200, "location": "https://…/archive/a.txt", "metadata": Metadata}`.

**Storage**, a storage description:

```json
{"id": "https://…/", "storageRoot": "https://…/root/",
 "services": [{"id": "…", "types": ["NotificationService"], "serviceEndpoint": "https://…/notifications/"}],
 "raw": { "…": "the document" }}
```

`id` and `services` are required. `storageRoot` is omitted when the description has none.

**Subscription**: `{"subscription": "https://…/notifications/s1", "raw": { "…": "…" }}`.

### 4.2 Errors

`error` is `{"kind": …, "message": …}` plus, where they apply, `status` (the HTTP status of the
response that caused it) and `problem` (an RFC 9457 problem details object).

| `kind` | When |
|---|---|
| `http` | a response with a status the operation does not succeed with; `status` is required |
| `authentication` | obtaining or using a credential failed: discovery of the authorization server, a check the client makes on what it discovered, or the token request; `status` when a response caused it |
| `protocol` | a response the client found to break the specification |
| `verification` | a notification that failed verification (`verify_notification`) |
| `transport` | no response: connection refused, TLS failure, timeout |
| `invalidArguments` | the request's arguments are missing or malformed |
| `unsupported` | the operation, or an argument it was given, is not available in this client |
| `internal` | anything else, which is a bug in the adapter or the client |

## 5. Operations

An operation not in `operations` is `unsupported`. So is an optional argument the client has no
way to honour: the adapter never drops one silently.

| Area | `op` | `args` | `result` |
|---|---|---|---|
| | `configure` | `auth`, `userAgent?`, `timeoutSeconds?` | `{"agent"?}` |
| core | `discover_storage` | `url` | Storage |
| core | `head` | `url` | Metadata |
| core | `read` | `url`, `accept?`, `ifNoneMatch?`, `rangeStart?`, `rangeEnd?` | `{"metadata", "notModified", "body"?}` |
| core | `list_container` | `url`, `limit?` | `{"items": [Item], "truncated"}` |
| core | `create` | `container`, `body?`, `contentType?`, `slug?`, `types?` (IRIs), `links?` | Created |
| core | `create_container` | `parent`, `slug?` | Created |
| core | `update` | `url`, `body`, `contentType?`, `ifMatch?`, `ifNoneMatch?`, `links?` | Update |
| core | `patch` | `url`, `patch`, `ifMatch?` | Update |
| core | `delete` | `url`, `recursive?`, `ifMatch?` | `{}` |
| core | `read_linkset` | `url` (the resource) | `{"url", "etag"?, "linkset", "allow", "acceptPatch"}` |
| core | `update_linkset` | `linksetUrl`, `linkset`, `ifMatch?` | Update |
| core | `patch_linkset` | `linksetUrl`, `patch`, `ifMatch?` | Update |
| core | `move` | `url`, `destination` (a container), `ifMatch?` | Moved |
| core | `request_access` | `serviceUrl`, `request` (an `AccessRequest` document) | `{"location"}` |
| core | `get_access_request` | `url` | `{"document"}` |
| core | `list_access_requests` | `serviceUrl`, `limit?` | `{"items", "truncated"}` |
| core | `cancel_access_request` | `url` | `{}` |
| core | `grant_access` | `serviceUrl`, `grant` (an `AccessGrant` document) | `{"location"}` |
| core | `get_access_grant` | `url` | `{"document"}` |
| core | `list_access_grants` | `serviceUrl`, `limit?` | `{"items", "truncated"}` |
| core | `revoke_access_grant` | `url` | `{}` |
| notifications | `subscribe` | `serviceUrl`, `topics` (URLs), `inbox`, `expires?` (RFC 3339) | Subscription |
| notifications | `list_subscriptions` | `serviceUrl`, `limit?` | `{"items", "truncated"}` |
| notifications | `get_subscription` | `url` | Subscription |
| notifications | `unsubscribe` | `url` | `{}` |
| notifications | `verify_notification` | `method`, `url`, `headers`, `bodyBase64`, `trustedStorages?` | `{"storage", "keyid", "activities"}` |
| index | `list_types` | `serviceUrl`, `limit?` | `{"types", "truncated"}` |
| index | `search` | `serviceUrl`, `query`, `limit?` | `{"items": [Item], "truncated"}` |
| | `shutdown` | none | `{}`, then the adapter exits with status 0 |

`update` with `links` asks the client to change a data resource's content and its metadata in one
request. `read_linkset` finds the linkset the way the client finds it; `update_linkset` and
`patch_linkset` take the `url` it reported.

### 5.1 `configure`

`configure` sets up the client every later operation uses. The driver sends it first, and may send
it again, which replaces the client and drops any token it holds. `auth` is one of:

| `auth` | The client authenticates with |
|---|---|
| `{"type": "none"}` | nothing: anonymous requests |
| `{"type": "bearer", "token": "…"}` | an access token it was given |
| `{"type": "cid", "agent": "https://…/id/alice", "privateJwk": {…}, "kid"?: "…"}` | credentials it signs itself as the agent, with the key its controlled identifier document lists, exchanged for an access token (lws10-authn-cid) |
| `{"type": "openid", "idToken": "…"}` | an OpenID Connect ID Token, exchanged for an access token (lws10-authn-openid) |
| `{"type": "saml", "assertion": "…"}` | a SAML 2.0 assertion, base64url-encoded, exchanged for an access token (lws10-authn-saml) |

A kind the client does not list in `auth` is `unsupported`. The result's `agent` is the agent the
client acts as, when it knows one.

### 5.2 `verify_notification`

The driver hosts the inbox a client subscribes with (`subscribe`'s `inbox`), and passes each
delivery on unchanged: the method, the inbox URL as it was registered, every header field with all
its values (`{"name": ["value", …]}`), and the body in base64. The adapter verifies it the way the
client verifies a notification, and returns the verified notification, or a `verification` error.
The driver answers the delivery `202` when it verified and `401` when it did not, so the session
sees the client's own verdict as the inbox's answer.

`trustedStorages`, when present, is never empty; absent, any storage is accepted. An activity is
`{"id"?, "types", "object", "objectTypes"}`.

The driver verifies deliveries in a second process of the same adapter, configured as the first,
so that a delivery never waits for an operation in progress (D-0105). An adapter does not assume it
is the only process of its kind.

## 6. Checking an adapter

`touchstone driver check -- <the adapter's command line>` starts the adapter and checks, through
this protocol, that it keeps it (D-0107):
- its hello, and `configure` with `{"type": "none"}`;
- an answer within the protocol, a result or an error other than `unsupported`, to every operation
  it lists, each sent with its required arguments only, naming a storage that cannot be reached
  (`https://storage.invalid/`);
- `unsupported` for every operation it does not list, and for one this version does not define;
- `invalidArguments`, with `"id": null`, for a line that is not a JSON object;
- after `shutdown`, an empty result and an exit with status 0 within 5 seconds.

Every line the adapter writes is checked against the schema as well. It reports one line per check
and exits 1 when one fails. It needs no session: a passing check says the adapter speaks the
protocol, and whether the client it wraps is conformant is what a drive says.

## 7. Changes from `lws-driver/1`

This protocol starts from lws-client's adapter protocol, `lws-driver/1`, whose eleven adapters have
driven lws-client's clients through Touchstone sessions since October 2026 (D-0088). It keeps the framing, the messages
and the conventions, and changes what was lws-client's API rather than the drafts':

| `lws-driver/1` | `touchstone-driver/1` | Why |
|---|---|---|
| hello `language`, `library` | hello `client` (`name`, `version`, `homepage`), `auth`; `language` optional | names the client under test, not a library of one project |
| auth `selfSigned` | auth `cid` | the suite's name in the drafts |
| auth `didKey` | removed | the did:key suite was retired (D-0058) |
| — | auth `saml` | the session's SAML identity provider (D-0087) |
| auth `bearer`'s `realm` | removed | the drafts of 19 October 2026 replaced the realm with protected resource metadata (D-0100) |
| `configure`'s `allowInsecureHttp`, `headers` | removed | options of one library; a client that needs them for a local session sets them itself |
| `get_storage_description` | `discover_storage` | one operation: find the storage, as the client does |
| `linkset_url` | `read_linkset` | the client finds the linkset by reading it |
| `read_container`, `read_type_index` | `list_container`, `list_types` | single-page reads of one library; the drafts' operation is the listing, followed page by page |
| `search_types`, `search_all` | `search` | one search, followed page by page |
| `accepted_query_formats` | removed | the client negotiates the query format inside `search` |
| `read`'s `prefer` | removed | no rule needs it |
| `update` | gains `links` | the combined update of content and metadata (`client-update-content-and-metadata`) |
| error kinds named after lws-client's exceptions | `http` with `status`, `authentication`, `protocol`, `verification`, `transport`, … | a client's error classes are its own |
| result members only lws-client exposes (`parent`, `storage`, `linkset`, `moveCapability`, `identifierPreserved`, …) | optional, or removed | an adapter reports what its client exposes |

## 8. Versions

`touchstone-driver/1` may gain operations, optional arguments and optional result members. It
never removes or changes one: that is `touchstone-driver/2`. An adapter answers an operation or an
argument it does not know `unsupported`, and the driver ignores result members it does not know, so
an adapter written today keeps working as `/1` grows.
