# Touchstone for LWS clients — design

**Status: phases C0 to C7 are built ([D-0076](DECISIONS.md), D-0077, D-0080, D-0081, D-0082,
D-0083, D-0084, D-0085), except C6's pilot with real clients, and the rule format passed Gate C
(D-0079). The service runs at `https://vulcan.bmi.stonybrook.edu/touchstone/clients/`.**
This brief extends [DESIGN.md](DESIGN.md), whose rules still hold: the catalog is the source
of truth, tests are data, the harness is tested against reference and broken twins, and
every deviation gets a DECISIONS.md entry.

---

## 1. Mission

Touchstone tests LWS servers by playing the client. This extension tests **LWS clients** by
playing everything a client talks to: the storage, its authorization server, an OpenID
Provider, the hosts of identity documents, and the notification server that calls the
client's inbox.

The client developer runs their own client. Touchstone gives them a session: their own
storage and identities, a checklist of things to try, and a web page with live feedback.
Touchstone serves every request for real, records it, judges it against the client
requirements in the catalog, and explains each finding: what the client sent, which clause
it breaks, and what to change.

The LWS drafts define an LWS Client conformance class: "an HTTP client [RFC 9112] that
complies with all of the relevant 'MUST' statements in this specification"
(`req:conformance-client-class`). The client obligations number about twenty, and nearly all
of them show in the requests a server receives. §11 lists them.

## 2. Settled decisions

These follow D-0075. Do not relitigate them without a new decision.

1. **The developer drives the client; Touchstone never does.** There are no adapters, no
   per-language bindings, and no MCP-controlled client harness. The only interface is
   HTTP, which every LWS client already speaks: the client uses the session's storage, and
   the developer's own scripts or CI use the session API (§4.4).
2. **Touchstone implements the server side itself.** The session deployment grows out of
   `RefLwsServer` and `RefAuthorizationServer` in `harness-fixtures`. Touchstone takes no
   dependency on lws-server, Halcyon or any other implementation. It grades those servers,
   so their behaviour must not become the yardstick for clients.
3. **Same catalog, same definitions, same reports.** Client rules are YAML-LD definitions
   that cite catalog requirements (§5). Results come out as EARL, JUnit XML and the HTML
   report family.
4. **A failure always cites a clause.** Touchstone's own advice appears as tips, never as
   failures, and never affects an outcome.
5. **Feedback, not certification.** Results cover what the developer exercised, and the
   report says how much that was (§7).
6. **Proxy mode is optional and later** (§10). In proxy mode, Touchstone records clients
   talking to a real, pre-registered server.

## 3. What the developer sees

1. **Start a session** at `{base}/` (for example `https://<host>/touchstone/clients/`).
   Optionally, name the client (name, version, homepage; it becomes the EARL subject) and
   choose the areas to cover: core, authentication suites, notifications, index.
2. **The session page** (a capability URL, §8) gives:
   - the storage URL, which is the only URL the client should need;
   - two identities, **alice** (the storage owner) and **bob**, with four ways to
     authenticate (§6):
     - an OpenID login at the session's own provider;
     - a CID key pair to download, with alice's identity document hosted by the session;
     - a SAML 2.0 assertion from the session's own identity provider (D-0087);
     - a pre-issued access token, as a shortcut for clients without authentication yet;
   - the session API key, for scripts and CI.
3. **A checklist of tasks**, grouped by area. Each task says:
   - what to do, for example "create a container named notes" or "edit note.txt again";
   - which requirements it exercises;
   - whether it arms a fault, for example "this time the server answers 412 once".

   Doing tasks in order is never required. They exist so that every rule can be triggered
   on purpose, including rules that need intent (§5.3) or a fault (§6.2).
4. **Live results, one row per rule:**
   - *passed*;
   - *failed*: the offending exchange (redacted), the clause with its spec link, what was
     expected against what was seen, and how to fix it;
   - *not yet observed*;
   - *inapplicable*: an area the developer declared out of scope.

   SHOULD failures are warnings and MAYs are informational, as in server reports.
5. **A traffic log** of every exchange in the session, annotated with the rules it
   triggered.
6. **Reset and export.** *Reset results* clears outcomes but keeps the storage, so a
   developer can re-run after a fix. *Export* writes EARL, JUnit XML and JSON. An optional
   read-only link could share the results without the session's controls; C6 left it out
   (D-0084), since an export does that job for now.

## 4. Architecture

```mermaid
flowchart LR
    DEV[Developer] -->|browser| PAGE[Session page]
    DEV -->|runs| CL[Client under test]
    CI[Developer's CI] -->|session API| API[Session API]
    CL -->|LWS, OAuth, OpenID| DEP
    subgraph LAB[harness-clients]
        DEP[Session deployment: storage, AS, OP, identity documents]
        REC[Recorder and URL ledger]
        RULES[Rule evaluator]
        PAGE
        API
        DEP --> REC --> RULES
        RULES --> PAGE
        RULES --> API
    end
    DEP -->|signed deliveries| INBOX[Client's inbox]
    CAT[catalog and client definitions] --> RULES
```

### 4.1 Module

`harness-clients` is a new module and a plain embedded Jetty 12 application, like
`harness-fixtures`; it needs no Spring. It depends on:
- `harness-core`, for the catalog, the definitions loader and lint, the matching rules of
  EXECUTION.md §8, and the report writers;
- `harness-fixtures`, for the reference servers.

`harness-core` stays Spring-free and gains no client-specific execution logic beyond the
new definition type's loading and lint.

### 4.2 Sessions

A session is the unit of work, as a run is on the server side. Each has:
- a public **session id** `{sid}`, which appears in protocol URLs;
- a secret **session key**, which unlocks the page and the API (§8).

Its URLs:

| Path | What it is |
|---|---|
| `{base}/s/{sid}/storage/` | the storage: its storage description and root container; resources it names under `_r/`, trap URLs under `_t/` (§6.1) |
| `{base}/s/{sid}/as/` | the authorization server: JWKS and token endpoint. Its RFC 8414 metadata is at `/.well-known/lws-configuration{base path}/s/{sid}/as` |
| `{base}/s/{sid}/op/` | the OpenID Provider: discovery, JWKS, the authorization endpoint with its sign-in form, and the token endpoint |
| `{base}/s/{sid}/id/{name}` | alice's and bob's identity documents, each naming a key and the OpenID Provider |

Paths, not hostnames, separate sessions, so one reverse-proxy rule covers the service.
LWS URIs are opaque to clients, so the prefix costs nothing.

`RefLwsServer` today owns one in-memory store and its own Jetty `Server`. Phase C1
separates its state from its lifecycle, so that one process hosts many session stores
behind one handler. The server-side self-test keeps using it unchanged: one store, one
server.

### 4.3 Recorder and URL ledger

Every exchange in a session passes through the recorder. It stores:
- the request (method, target, headers, body up to 1 MiB);
- the response (status, headers, body up to 64 KiB);
- timing.

It also attaches **annotations**: facts only the server knows, which the rules match on.
- **The identity**, whose token or credential it was, and how it was presented.
- **The target's role:** storage description, container, data resource, linkset, page,
  service endpoint (token, subscriptions, access requests, search, type index), trap or
  inbox.
- **Whether the URL was issued, and how:**
  - The ledger keeps every URL the session has handed out: `Location`, `Link` targets,
    container `items`, the storage description, and the session page.
  - A request to an unissued URL that exists is a URL the client built itself.
- **What the target last advertised to this client:** `Allow`, `Accept-Patch`,
  `Accept-Query`, `ETag`.
- **Whether an armed fault fired** on this exchange.

Rules stay declarative because the cleverness lives in one place, the recorder, which is
tested once.

### 4.4 Session API

Plain HTTP and JSON, authenticated with the session key as a Bearer token. Phases C1 to C6
built all of it; [harness-clients/README.md](harness-clients/README.md) documents it.
- `POST {base}/sessions` creates a session and returns its id, key and URLs, and access
  tokens for alice and bob. It is rate-limited and open, without sign-in (§12.2). Its optional
  body names the client under test and the areas in scope; `PATCH {base}/sessions/{sid}`
  changes them later (C6). With `"proxy": "<id>"` it starts a proxy session (§10, C7), and
  `GET {base}/proxies` lists the targets.
- `GET {base}/sessions/{sid}` describes the session; `POST {base}/sessions/{sid}/tokens/{name}`
  hands out a fresh token; `GET {base}/sessions/{sid}/page` is the session page, which reads
  the key from its URL's fragment.
- `GET {base}/sessions/{sid}/credentials/{name}` gives an identity's password for the OpenID
  Provider, and the private key, a JWK, of the verification method its identity document lists.
- `POST {base}/sessions/{sid}/assertions/{name}` hands out a signed SAML 2.0 assertion about an
  identity from the session's SAML identity provider, base64url-encoded, for a client the body may
  name (D-0087).
- `POST {base}/sessions/{sid}/clients` registers a client with the OpenID Provider:
  `{"redirect_uris": [...]}`, and optionally a `client_id`, an absolute URI. `GET` lists the
  registered clients.
- `GET {base}/sessions/{sid}/results` returns results as JSON, or the JSON, EARL or JUnit XML
  export with `?format=json|earl|junit`.
- `GET {base}/sessions/{sid}/exchanges?after=N` returns the traffic log, paged and redacted.
- `POST {base}/sessions/{sid}/tasks/{rule}` starts a rule's task, arming its fault if it has
  one; `POST {base}/sessions/{sid}/faults/{fault}` arms a fault alone.
- `POST {base}/sessions/{sid}/reset` resets results; `DELETE {base}/sessions/{sid}` ends
  the session.

A developer's CI can create a session, point the client's own test suite at the storage
URL, arm faults between steps, and fail the build on any MUST failure. That gives
automation in the developer's own language with nothing per language on our side.

## 5. Rules

### 5.1 The definition type

Client rules live in their own tree, `definitions/lws10/clients/`, as a new kind of entry,
`ObservationTest`, in format 0.8.0. [`definitions/OBSERVATION.md`](definitions/OBSERVATION.md)
is its contract, frozen at Gate C (§13, D-0079). A rule carries the same metadata as
a server test (`id`, `name`, `label`, `comment`, `status`, `level`, `source`, `traits`,
`requirements`), plus:

- **`area`:** `core`, `authentication`, `notifications` or `index`. A developer can declare an
  area out of scope.
- **`observe`:** a condition that selects the exchanges the rule judges, its trials.
- **`expect`:** a condition each trial must satisfy.
- **`guidance`:** how to fix a failure, in a sentence or two.

Both conditions use one vocabulary:
- the request's own terms, reused from the response vocabulary (`contentType`, `linkHeaders`,
  `otherHeaders`, `bodyMatches`, `json`);
- `statusCode`, for the session's answer;
- the recorder's annotations (§4.3), such as `role`, `issued` or `methodAdvertised`;
- `anyOf`, for alternatives.

`after` in `observe` makes the trial "the next matching exchange after a trigger". Rules have
no variables and no captures. Format 0.9.0 (phase C3, D-0081) adds a rule's `task`, which may
`arm` a fault, and the conditions `repeat` and `containerEmpty`. The absence check within a
window planned as `followedBy` turned out unnecessary. Format 0.10.0 (phase C4, D-0082) adds
what the recorder knows of a token request: where its credential came from
(`credentialSource`), the credential's header and claims (`credential`), `audienceIncludesAs`,
`identifiersAgree` and `realmContainsRequest`; and `form`, for form bodies.

```yaml
  - id: "#client-linkset-put-only-when-advertised"
    type: ObservationTest
    name: client-linkset-put-only-when-advertised
    label: The client replaces a linkset with PUT only after the linkset advertised PUT
    status: Proposed
    level: SHOULD
    source:
      - https://www.w3.org/TR/2026/WD-lws10-core-20261005/#metadata
    traits: [Put, Linkset]
    area: core
    requirements:
      - https://example.org/touchstone/req/lws10-core/client-no-assumed-methods-405-415
    observe:
      role: linkset
      method: PUT
    expect:
      methodAdvertised: true
    guidance: >-
      Read the linkset first (GET or HEAD) and use PUT only if its Allow header lists PUT.
      Otherwise update it with PATCH, in a format its Accept-Patch header lists.
```

### 5.2 Outcomes

Each exchange a rule selects is one trial of that rule. Over the session, the rule's outcome
is:

| Outcome | Meaning | EARL |
|---|---|---|
| `passed` | at least one trial, and none failed | `earl:passed` |
| `failed` | a trial failed; the first is kept as evidence, the rest are counted | `earl:failed` |
| `untested` | no trial yet | `earl:untested` |
| `inapplicable` | the developer declared the area out of scope | `earl:inapplicable` |
| `cantTell` | a sequence window never closed, or the evidence was cut short | `earl:cantTell` |

A rule with `followedBy` shows as *watching* until its window closes. Outcomes only get
worse within a session; *reset* starts them over.

The client's verdict follows the server side: only MUST rules decide it. The report states
it as "no MUST failure in the 14 MUST rules exercised, of 19 that apply", never as plain
"conformant". EARL runs use `earl:mode earl:semiAuto`: a person drives, the harness judges.

### 5.3 Intent

Some obligations depend on what the client meant to do. A POST without `Link: rel="type"`
creates a data resource, which is legal unless the client meant to create a container. Such
rules use a task: the developer says what they are about to do, and the rule judges the
next matching exchange. The page shows rules like these as needing their task.

## 6. Traps and faults

### 6.1 Traps (always on)

Traps are server behaviour that is legal under the spec. Conformant clients handle them
without noticing; clients relying on unspecified behaviour trip over them.

- **Opaque page URLs.** Containers paginate at four members, and page URLs are random
  tokens under `_t/`.
- **URIs that don't mirror containment.** Resources the server names get flat URIs that do
  not nest under their container's path (`uri-independent-of-hierarchy`). It is on by
  default and can be switched off on the session page, to separate this trap from other
  problems.
- **Opaque linkset URLs.** A linkset is reachable only through `rel="linkset"`.
- **Only some methods advertised.** PUT on a linkset is optional: a data resource's linkset
  accepts it and lists it in `Allow`, a container's refuses it. Linksets take PATCH in JSON
  Patch only. Binary data resources (any media type but text, JSON, XML or an RDF syntax)
  refuse PUT and omit it from `Allow`. `Accept-Patch` lists a single format.
- **A decoy resource** listed first in the root container, which answers `401` with a
  challenge whose `realm` does not contain it (`authz-challenge-realm-param`). A conformant
  client neither requests nor presents a token for it.
- **A lagging index.** Search and type-index results trail writes by a few seconds, as
  `client-no-read-your-writes` allows.

### 6.2 Faults (armed on request)

A fault fires once, on the next request it applies to, after the developer arms it by
starting a task or through the API. The traffic log marks the exchange it fired on. Each fault
is something a server may legally do, so a conformant client meets it in the wild too.
[`definitions/OBSERVATION.md`](definitions/OBSERVATION.md) section 6.2 defines them.

| Fault | What the session does | Exercises |
|---|---|---|
| `methodNotAllowed` | answers the next PUT to a linkset that supports PUT with `405`, as a server that withdrew the optional PUT | `client-no-assumed-methods-405-415` |
| `lostCreateResponse` | performs the next POST create, then answers `503` | `create-post-not-idempotent` |
| `pageGone` | answers the next request for a page of search results with `410` | `client-restart` |
| `tokenExpired` (C4) | answers the next storage request with a valid token, other than to the decoy, with `401` and `error="invalid_token"`, and refuses that token from then on | `authz-challenge-realm-param`: a client that keeps its token gets a reason to ask for a new one after meeting the decoy |
| `forgedUnpublishedKey` (C5) | signs the next notification with a key the storage description does not publish, its keyid naming the published one | `inbox-verifies-signature`, `receiver-verification-steps` |
| `forgedAlteredBody` (C5) | signs the next notification, then alters its body | `inbox-verifies-signature` |
| `forgedKeyidWithoutFragment` (C5) | signs the next notification with the published key, under a keyid without a fragment | `receiver-verification-steps` |
| `forgedForeignKeyDocument` (C5) | signs the next notification with the key of a document under the storage that claims to be its description | `receiver-verification-steps` |

The forgeries are not server behaviour: they are what an attacker sends, which an inbox must
refuse. Phase C5 dropped the plan's stale `created` (D-0083): only the webhook suite's
non-normative security considerations encourage checking it, so a rule would cite no clause.

"Handles 405 and 415 gracefully" cannot be seen directly. Its rule checks the observable
part: the client does not repeat the refused request unchanged. The guidance says so.

The plan had four more faults that phase C3 dropped or moved (D-0081):
- **`preconditionFailedOnce`.** A 412 tests only that the next write stays conditional, which
  `client-put-conditional` already judges on every PUT. Re-reading after a 412 is good
  practice that the drafts do not require.
- **`unsupportedMediaTypeOnce`.** A 415 for JSON Patch on a linkset would break a server
  MUST, and natural 415s, for an unadvertised format, already trigger the rule.
- **`queryFormat415`.** Refusing the baseline query format would break a server MUST.
- **`tokenExpired`.** It moved to phase C4, which built it (D-0082; the table above), because
  before C4 a client's fresh token came from the session API, which is not recorded.

## 7. Limits

- **Coverage is what the developer exercised.** The checklist and the *not yet observed*
  rows make gaps visible, and the CI path makes coverage repeatable. A session still
  proves less than a full server run does.
- **Some obligations are invisible to a server.** `client-no-read-your-writes` is the clearest
  case: a client that mishandles stale results does so internally. Such requirements are
  listed with guidance and stay `untested`.
- **The inbox must be reachable.** Webhook receiver rules need the client's inbox to accept
  connections from the service. A client on a laptop needs a public endpoint or a tunnel;
  without one, those rules stay `untested`. The subscription rules still apply.
- **One session deployment, one behaviour.** A client tested only against Touchstone may
  still trip over a real server's legal variations that the traps don't cover. Proxy mode
  (§10) is the answer for that.

## 8. Security invariants

The client service inverts DESIGN.md §7's situation. It accepts requests from anyone, and
for notifications it sends requests to URLs that strangers supply. These hold in addition
to §7:

1. **Capability-protected sessions.** The session key is 256 random bits, required by the
   page and the API. It never appears in protocol URLs or in recorded exchanges.
   Protocol traffic is protected by the session's own tokens, as any storage is.
2. **Bounded everything:**
   - Requests: a body of at most 1 MiB, and a per-session request rate limit.
   - Storage: per-session caps on resources and on total bytes.
   - The traffic log: a cap on exchanges and on the body text they hold, and on the URLs the
     ledger remembers, so that a client sending endless made-up URLs gains nothing (C6).
   - Sessions:
     - they expire after two idle hours, and after 24 hours at most;
     - a global cap on live sessions;
     - session creation is rate-limited per address.

   Everything is in memory. Nothing outlives a session except an export the developer
   downloads.
3. **Outbound requests only to the public internet:**
   - The session's authorization server dereferences nothing: it validates credentials with
     the session's own identity documents, OpenID Provider and SAML identity provider's key, in
     process, and refuses any subject, issuer or key elsewhere (D-0082, D-0087).
   - Deliveries go only to `https` inbox URLs that resolve to public unicast addresses. That
     excludes loopback, RFC 1918, link-local (including `169.254.169.254`), unique local,
     CGNAT, multicast, reserved and documentation addresses, and IPv6 prefixes that embed an
     IPv4 address.
   - The address is checked at connect time, on the addresses connected to, so DNS rebinding
     gains nothing (phase C5: the delivery client's address resolver filters them).
   - Redirects are never followed; no cookie is kept, and no proxy used.
   - A delivery times out after ten seconds, keeps at most 64 KiB of the answer, and a session
     sends at most 500.
   - Inboxes come only from subscriptions and access grants, which need a session identity's
     access to the storage.
   - Each delivery is recorded, a refused one with status 0 and the reason.
   - `--allow-private-inboxes` lifts the address and scheme checks for local development and the
     self-test. A public service never sets it.
4. **Redaction.** Stored and displayed exchanges carry `Authorization`, `Cookie` and
   `DPoP` values as a fingerprint, so rules can still tell tokens apart without showing
   them. Raw values exist only in memory, while rules evaluate.
5. **Client input is untrusted.**
   - Bodies are parsed with the offline loaders (no JSON-LD context is ever fetched).
   - The page escapes everything the client sent and is served with a strict Content
     Security Policy.
   - Nothing a client sends reaches a shell, a file path or a query language.
6. **Separate from server testing.** The client service runs as its own process and never
   shares a port, path or fixture host with server runs. Its outbound requests are only
   the deliveries in invariant 3; DESIGN.md §7's "no arbitrary targets" is untouched,
   because the client service has no targets.

## 9. Proving the rules

The same discipline as the server side: each rule must pass against a client that does the
right thing, and fail against one that does not.

- **`RefLwsClient`** (in `harness-fixtures`) is a scripted, conformant client on the JDK
  `HttpClient`. Its script gives every rule a trial, and it starts every task in the checklist
  through the session API before doing what the task asks. It reports nothing itself: the
  session judges it.
- **Broken twins** each get one thing wrong, for example:
  - one builds page URLs;
  - one drops `If-Match` after a 412;
  - one sends its token in the query string;
  - one accepts forged deliveries;
  - one mints CID credentials without `client_id`;
  - one creates containers without `Link: rel="type"`.
- **`ClientRulesSelfTest`** in `./mvnw verify` runs the reference client and every twin in
  sessions of their own:
  - `RefLwsClient` passes every rule it exercises;
  - each twin fails exactly the rules aimed at it, and nothing else.

## 10. Proxy mode (phase C7, D-0085)

Touchstone as a recording reverse proxy in front of a real server: a target registered out of
band, in the target registry format, never a URL the developer supplies (DESIGN.md §7.1). A
proxy session records and judges a client talking to that server.

**A front-door proxy.** A proxy in front of an existing deployment would have to rewrite its
URLs, and authentication does not survive that:
- A rewritten `realm` names a resource the server's authorization server refuses tokens for.
- An unrewritten one does not contain the URL the client was refused, so a conformant client
  does not ask for a token at all.
- A self-issued credential's audience is signed, so it cannot be rewritten.

So the target is deployed behind the service, configured with `{base}/p/{id}/` as the start
of its public URLs. Nothing is rewritten, and every authentication suite works as it would
without the proxy. Because a target's URLs are its own, not a session's, one session holds a
target at a time. The service is started with `--proxy-targets`, and the public service on
vulcan registers none (D-0085): a stranger's session should not reach a real server.

With the proxy:
- the recorder infers each exchange's role from what the server handed out (OBSERVATION.md
  section 11). Against the reference server, it infers the server's own role for every request;
- passive rules work as before;
- four faults work, injected by the proxy alone: `methodNotAllowed`, `lostCreateResponse`,
  `pageGone` and `tokenExpired`;
- traps do not, since they need the server's cooperation. A rule that needs what only the
  session's own servers know is inapplicable: the details of a credential, a notification's
  signature, a container's members, the decoy. That makes 19 of the 53 rules.

A real server delivers its notifications itself, not through the proxy, and the session's own
storage and authorization server are not served, and its SAML identity provider hands out no
assertions. The session's identities and OpenID Provider remain; they work if the server trusts
them. A target may name its authorization server's
issuer, which the session's provider then adds to the audience of its ID Tokens.

Proxy mode tests clients against real-world server behaviour. It is also a second
cross-check of the servers themselves, because a conformant client hitting a server's
quirk is a finding about one of the two.

## 11. Initial rule inventory

Phase C0 tagged every catalog requirement with the roles it binds (D-0076). The full list
of the 79 that bind a client or a receiver is generated from those tags, in
[`definitions/COVERAGE.md`](definitions/COVERAGE.md) section 4. This table is the plan for
observing them, grouped by mechanism. "Half" marks a clause that binds both servers and
clients; only the client half is judged here.

| Requirement | Level | Observed by | Phase |
|---|---|---|---|
| `lws10-core/authz-bearer-presentation-rfc6750` | MUST | passive: a session token anywhere but `Authorization` (query string, form body) | C2 |
| `lws10-core/client-no-assumed-methods-405-415`; `linkset-put-405-if-unsupported` (half) | MUST | passive, on linksets, where the clause sits: PUT only when `Allow` lists it, PATCH only in a format `Accept-Patch` lists (SHOULD); faults 405 and 415: no unchanged repeat (MUST) | C2, C3 |
| `lws10-core/subscription-create-post-lws-json`, `subscription-request-*`; `lws10-notifications-webhook/subscription-type-and-fields`, `subscription-inbox-required`, `subscription-type-identifier` (some half) | MUST | passive: subscription bodies | C2 |
| `lws10-core/access-jsonld-context-lws-v1`, `access-type-values`, and the other access and policy data-model clauses (half) | MUST | passive: the access requests and grants the client POSTs | C2 |
| `lws10-index/client-baseline-only`, `query-content-type-required` (half) | MUST | passive: a QUERY without `Content-Type`, or in a format the server did not advertise and kept after a 415 | C2, C3 |
| `lws10-core/put-clients-use-conditional-requests` | SHOULD | passive: a PUT replacing a resource carries `If-Match` | C2 |
| `lws10-core/linkset-precondition-failed-412` (half) | SHOULD | passive: PUT and PATCH on a linkset are conditional | C2 |
| `lws10-core/pagination-uris-opaque` | SHOULD | trap: page requests use issued URLs | C2 |
| `lws10-core/uri-independent-of-hierarchy` (half) | SHOULD | trap: no request to an unissued URL built from another URL's path | C2 |
| `lws10-core/create-container-type-link` | MUST | task "create a container" | C3 |
| `lws10-core/create-server-managed-metadata-protected` (half) | MUST | passive: a create's Link headers name no server-managed relation, `linkset` | D-0087 |
| `lws10-core/update-content-vs-metadata-prefer-set-linkset` (half) | MUST | task "update content and metadata in one request": the Link headers go with `Prefer: set-linkset` | D-0087 |
| `lws10-core/delete-non-empty-container-409-depth` (half) | MUST | task "delete a container and its contents": `Depth: infinity`, or the members first | C3 |
| `lws10-core/create-post-not-idempotent` | SHOULD | fault `lostCreateResponse`: no identical blind retry | C3 |
| `lws10-index/client-restart` | SHOULD | fault `pageGone` | C3 |
| `lws10-index/client-415-accept-query` | MAY | no rule: indistinguishable from `client-query-baseline-after-415` while the session accepts only the baseline (D-0078) | — |
| `lws10-core/authz-challenge-realm-param` (half) | MUST | trap: the decoy's foreign realm; fault `tokenExpired` | C4 |
| `lws10-core/authn-client-claim`, `lws10-authn-ssi-cid/client-id-claim`, and the CID suite's other credential MUSTs | MUST | passive: the self-issued credentials the client presents at the token endpoint | C4 |
| `lws10-core/authz-token-exchange-resource-param`, `authz-token-exchange-subject-token-param` (half); the suites' token types `id-token-token-type-uri`, `token-type-jwt` | MUST | passive: token requests | C4 |
| `lws10-authn-saml/token-type-saml2` | MUST | passive: token requests presenting an assertion of the session's SAML identity provider, which the session API hands out (D-0087; D-0082 had none) | D-0087 |
| `lws10-notifications-webhook/inbox-verifies-signature`, `receiver-verification-steps`; `keyid-url-with-fragment`, `storage-description-id-matches` (half) | MUST | the forgery faults: forged deliveries refused, genuine ones acknowledged | C5 |
| `lws10-notifications-webhook/per-subscription-inbox-urls` | MAY | informational: one inbox per subscription | C5 |
| `lws10-core/prefer-link-relations-filtering`, `delete-if-match-optional` | MAY | informational | C2 |
| `lws10-index/client-no-read-your-writes` | MUST | not observable; the lagging-index trap surfaces it to the developer; stays `untested` | — |
| `lws10-core/conformance-client-class` | MUST | the aggregate: the client's verdict (§5.2) | C6 |

**The C2 rules,** in `definitions/lws10/clients/` (D-0080). Gate C reviewed the format against
them, and `ClientRulesSelfTest` proves each passes for the reference client and fails for a
twin. The two MAY rows
of the C2 plan above get no rule. The syntax of `prefer-link-relations-filtering` is left open by
the draft, so nothing can be checked. `client-415-accept-query` cannot be told apart from
`client-query-baseline-after-415` while the session accepts only the baseline format.

| Rule | Level | Cites | Trials (`observe`) | Passes when (`expect`) |
|---|---|---|---|---|
| `client-token-in-authorization-header` | MUST | `authz-bearer-presentation-rfc6750` | storage requests carrying a credential | it is in `Authorization: Bearer` only |
| `client-linkset-put-only-when-advertised` | SHOULD | `client-no-assumed-methods-405-415`, `linkset-put-405-if-unsupported` (half) | PUT to a linkset | the linkset's `Allow` listed PUT |
| `client-linkset-patch-format-advertised` | SHOULD | `client-no-assumed-methods-405-415` | PATCH to a linkset | its `Accept-Patch` listed the format |
| `client-put-conditional` | SHOULD | `put-clients-use-conditional-requests` | PUT to a data resource | `If-Match` or `If-Unmodified-Since` |
| `client-linkset-write-conditional` | SHOULD | `linkset-precondition-failed-412` | PUT or PATCH to a linkset | `If-Match` or `If-Unmodified-Since` |
| `client-page-urls-issued` | SHOULD | `pagination-uris-opaque` | page requests, and URLs built from a container's or page's URL by query | the URL was issued |
| `client-member-urls-issued` | SHOULD | `uri-independent-of-hierarchy` | storage requests for containers, data resources and linksets, and URLs built by path | the URL was issued |
| `client-access-document-context` | MUST | `access-jsonld-context-lws-v1` | POSTs of access requests and grants | `@context` is an array with the LWS context |
| `client-access-request-type` | MUST | `access-type-required`, `access-type-values` | POSTs of access requests | `type` includes `AccessRequest` |
| `client-access-grant-type` | MUST | `access-type-required`, `access-type-values` | POSTs of access grants | `type` includes `AccessGrant` |
| `client-access-document-storage` | MUST | `access-storage-required`, `access-storage-uri` | POSTs of access requests and grants | `storage` is a URI |
| `client-access-document-access` | MUST | `access-access-required`, `access-access-collection` | POSTs of access requests and grants | `access` is an array of one or more objects |
| `client-access-policy-type` | MUST | `policy-type-required`, `policy-type-access-policy` | those whose `access` is an array | every policy's `type` includes `AccessPolicy` |
| `client-access-policy-action` | MUST | `policy-action-required`, `policy-action-values` | the same | every `action` is an array of one or more of read, modify, create, delete |
| `client-access-policy-assignee` | MUST | `policy-assignee-required`, `policy-assignee-uri-foaf-agent` | the same | every `assignee` is a URI |
| `client-access-policy-target` | MUST | `policy-target-object` | those with a policy target | each target is an object with a `type` and a `value` array of strings |
| `client-access-policy-constraint` | MUST | `policy-constraint-objects` | those with constraints | each is an array of objects with `leftOperand`, `operator`, `rightOperand` |
| `client-access-document-inbox` | MUST | `access-inbox-uri` | those with an `inbox` | it is a URI |
| `client-delete-conditional` | MAY | `delete-if-match-optional` | DELETE of a data resource or container | `If-Match` |
| `client-subscription-media-type` | MUST | `subscription-create-post-lws-json` | POSTs to the NotificationService | `application/lws+json`, a JSON object |
| `client-subscription-type` | MUST | `subscription-request-required-fields`, `-type`; webhook `subscription-type-and-fields`, `subscription-type-identifier` | the same | `type` is `WebhookSubscription`, the one type advertised |
| `client-subscription-topic` | MUST | `subscription-request-required-fields`, `-topic` | the same | `topic` is an array of URIs |
| `client-subscription-inbox` | MUST | webhook `subscription-inbox-required` | webhook subscription POSTs | `inbox` is a URI |
| `client-query-content-type` | MUST | `query-content-type-required` | QUERY to the Type Search Service | `Content-Type` is present |
| `client-query-baseline-after-415` | MUST | `client-baseline-only` | the next QUERY to it after a 415 | the baseline format, `application/lws-query+json` |

**The C3 rules** each have a task (D-0081). The first two take the developer's word for what the
client meant; the other three arm a fault.

| Rule | Level | Cites | Task | Trials (`observe`) | Passes when (`expect`) |
|---|---|---|---|---|---|
| `client-create-container-type-link` | MUST | `create-container-type-link` | create a container | the first POST into a container after the task starts | `Link: <…lws#Container>; rel="type"` |
| `client-delete-container-depth` | MUST | `delete-non-empty-container-409-depth` | delete a container with its contents | the first DELETE of a container after the task starts | `Depth: infinity`, or the container was empty |
| `client-no-repeat-after-405-415` | MUST | `client-no-assumed-methods-405-415` | replace a linkset; `methodNotAllowed` | the next request to a linkset after it answered 405 or 415 | not the refused request again |
| `client-no-blind-retry-of-create` | SHOULD | `create-post-not-idempotent` | create a data resource; `lostCreateResponse` | the next request to a container after a POST to it got a 5xx | not the same POST again |
| `client-restart-after-refused-page` | SHOULD | `client-restart` | follow search results to a next page; `pageGone` | the next request to the search or index after a page got 404 or 410 | a fresh QUERY, or the type index again |

**The C4 rules** judge requests to the session's token endpoint, in the area `authentication`
(D-0082). The CID rules judge credentials the client signed itself: JWTs that name one of the
session's identities and that the session did not issue (`OBSERVATION.md` section 4.9).

| Rule | Level | Cites | Trials (`observe`) | Passes when (`expect`) |
|---|---|---|---|---|
| `client-token-exchange-resource` | MUST | `authz-token-exchange-resource-param` | token exchange requests | `resource` is a URI |
| `client-token-exchange-subject-token` | MUST | `authz-token-exchange-subject-token-param` | token exchange requests | `subject_token` is present |
| `client-token-for-containing-realm` | MUST | `authz-challenge-realm-param` | the next token request naming a realm after a storage 401; task: open the decoy, then a resource; `tokenExpired` | the realm contains the URL the 401 answered |
| `client-cid-token-type-jwt` | MUST | CID `token-type-jwt` | self-issued credentials | `subject_token_type` is the jwt URI |
| `client-cid-credential-signed` | MUST | CID `alg-not-none`; `authn-credential-signed` | self-issued credentials | `alg` is not `none` |
| `client-cid-subject-claim` | MUST | CID `sub-claim`; `authn-subject-claim-uri`, `authn-credential-tamper-evident-claims` | self-issued credentials | `sub` is a URI |
| `client-cid-issuer-claim` | MUST | CID `iss-claim`; `authn-issuer-claim-uri` | self-issued credentials | `iss` is a URI |
| `client-cid-client-id-claim` | MUST | CID `client-id-claim`; `authn-client-claim` | self-issued credentials | `client_id` is a string |
| `client-cid-identifiers-agree` | MUST | CID `sub-iss-client-same-uri` | self-issued credentials with all three | `sub`, `iss` and `client_id` are equal |
| `client-cid-audience-includes-as` | MUST | CID `aud-includes-as` | self-issued credentials with `aud` | `aud` names the authorization server |
| `client-cid-audience-restricted` | SHOULD | `authn-audience-restriction-recommended` | self-issued credentials | `aud` is present |
| `client-cid-expiry-claim` | MUST | CID `exp-claim` | self-issued credentials | `exp` is a number |
| `client-cid-issued-at-claim` | MUST | CID `iat-claim` | self-issued credentials | `iat` is a number |
| `client-oidc-token-type-id-token` | MUST | OpenID `id-token-token-type-uri` | ID Tokens of the session's OpenID Provider | `subject_token_type` is the id_token URI |

The ID Token's own claims (`azp`, `aud` and the rest) bind the OpenID Provider, not the client,
so no rule judges them. The session's provider issues them as the OpenID suite asks.

**The C5 rules** judge the client's inbox, in the area `notifications` (D-0083). Their trials are
deliveries: the session's notifications and the inbox's answers (`OBSERVATION.md` section 3).
Each forgery rule has a task that arms its forgery for the next notification.

| Rule | Level | Cites | Trials (`observe`) | Passes when (`expect`) |
|---|---|---|---|---|
| `client-inbox-acknowledges-genuine-delivery` | SHOULD | `receiver-verification-steps` | answered genuine notifications | a 2xx |
| `client-inbox-refuses-unpublished-key` | MUST | `inbox-verifies-signature`, `receiver-verification-steps` | notifications signed with an unpublished key | not a 2xx |
| `client-inbox-refuses-altered-body` | MUST | `inbox-verifies-signature` | notifications altered after signing | not a 2xx |
| `client-inbox-refuses-keyid-without-fragment` | MUST | `receiver-verification-steps`, `keyid-url-with-fragment` (half) | notifications whose keyid has no fragment | not a 2xx |
| `client-inbox-refuses-foreign-key-document` | MUST | `receiver-verification-steps`, `storage-description-id-matches` (half) | notifications whose key document names another id | not a 2xx |
| `client-subscription-own-inbox` | MAY | `per-subscription-inbox-urls` | subscription requests naming an inbox | no subscription already delivers to it |

Acknowledging a genuine notification is a SHOULD rule citing a MUST clause, because an inbox may
refuse one for reasons of its own, such as a 410 to end a subscription. The verification steps
also leave a gap the session does not test: nothing ties the keyid to the storage the inbox
subscribed to, so a notification signed with a key from another storage's own description passes
all five steps. §12.4 takes that to the working group.

**Closing the gaps (D-0087).** A session now has a SAML 2.0 identity provider. It has no
endpoint: the session API hands out its signed assertions about alice and bob, as it hands out
tokens, and the session's authorization server trusts its key, as the SAML suite leaves trust to
configuration. Its assertions are minted by the harness-core code the server self-test proves, and
verified by the reference authorization server's own.

Three more clauses the rules already judged are now cited: the receiver halves of
`keyid-url-with-fragment` and `storage-description-id-matches`, steps 1 and 3 of the webhook
suite's section 5.2, and the client half of `linkset-put-405-if-unsupported`, whose permission to
PUT holds "if advertised in the Allow header". New rules:

| Rule | Level | Cites | Task | Trials (`observe`) | Passes when (`expect`) |
|---|---|---|---|---|---|
| `client-create-no-server-managed-links` | MUST | `create-server-managed-metadata-protected` | | POSTs into a container with a `Link` header | no `rel="linkset"` |
| `client-combined-update-prefer-set-linkset` | MUST | `update-content-vs-metadata-prefer-set-linkset` | update a resource's content and metadata in one request | the first PUT or PATCH of a data resource with a `Link` header after the task starts | `Prefer` lists `set-linkset` |
| `client-saml-token-type-saml2` | MUST | SAML `token-type-saml2` | | token requests presenting an assertion of the session's SAML identity provider | `subject_token_type` is the saml2 URI |

Of the relations the metadata section calls server-managed, only `linkset` is one a create's
`Link` header can carry that is never the client's: `rel="type"` to `lws#Container` asks for a
container, and the index services take a resource's other types from the `rel="type"` links of its
create or update. That is also why Link headers on an update are not wrong in themselves, and the
second rule needs the developer's word. The SAML suite's other clauses bind the identity provider
or the authorization server, so `token-type-saml2` is its only client rule.

## 12. Open questions (for Erich)

1. **Hosting. Decided 2026-10-05 (D-0084): vulcan.** The service runs there as its own process
   and user, behind the nginx that fronts Halcyon, at `/touchstone/clients/`. It never talks to
   Halcyon. ebremer.com was the other candidate, but it has 3 GB of memory for everything and
   shares Apache with regalbait.
2. **Session creation. Decided 2026-10-05 (D-0084): open and rate-limited**, as built: 10 sessions
   per address per hour and 100 live at once. A sign-in can come later if it is abused.
3. **OpenID client registration. Decided 2026-10-05 (D-0082):** per-session redirect URIs,
   entered on the page or through the API, with a client identifier the developer chooses or
   the session assigns. Client identifiers dereferenced to metadata documents can come later,
   if lws10-authn-openid comes to require them (`id-token-azp-claim` says only that `azp`
   carries one).
4. **The working group.** Raise the webhook suite's gap (D-0083): section 5.2 never ties the
   keyid to the storage the inbox subscribed to, or to the notification's `storage`, so a
   notification signed with a key from any storage's own description passes all five steps.
   Ask whether lws-test-suite plans client tests. If so, contribute
   the rule format back as JSON-LD, as D-0047 does for server tests.

## 13. Delivery plan

The phases run in order, and each has an acceptance criterion. **Gate C:** the
`ObservationTest` schema waits for Erich's review before the first rule is written, as
Gate 2 did for the server-side schema.

- **C0: catalog. Done 2026-10-05 (D-0076).** Every requirement names the roles it binds,
  `touchstone:appliesTo`, one value per role:
  - `Server`, `AuthorizationServer`, `Client`, `Receiver`;
  - `IdentityProvider`, for OpenID Providers and SAML identity providers;
  - `Specification`, for the two clauses that bind other specifications.

  A clause binding several roles names each, instead of a "mixed" value. `check.py` enforces
  that a server test citing requirements cites a server-side one; a negative test may rest on
  the clause of the party whose message it forges. Client rules get the mirror-image check
  when their type exists (C2). Coverage everywhere counts the server-side requirements only.
  *Done when:* COVERAGE.md lists client requirements separately, generated from the tags.
  Section 11 points to that list.
- **C1: session deployment. Done 2026-10-05 (D-0077).**
  - `RefLwsServer`'s state split from its lifecycle, and the session manager.
  - The recorder and URL ledger, the traps, and quotas and expiry.
  - The session API without results, and a page showing only the traffic log.

  *Done when:* `curl` with a session token can create, list and delete in a session's
  storage, and the log shows the redacted exchanges with their annotations. The traps also
  passed the server-side check: every definition passes against a reference deployment with
  them set.
- **C2: rules. Done 2026-10-05 (D-0080).**
  - Gate C: format 0.8.0 proposed (D-0078) and frozen (D-0079).
  - The new format version: schema, loader and lint for `ObservationTest`.
  - The passive rules of §11.
  - `RefLwsClient` and its first twins in `ClientRulesSelfTest`.

  *Done when:* every C2 rule passes for the reference client and fails for its twin. All 25
  pass for `RefLwsClient`, and each of its 25 twins fails exactly the rule aimed at it.
- **C3: tasks and faults. Done 2026-10-05 (D-0081).** The checklist, the faults of §6.2 except
  the forged deliveries, and the task-based rules. *Done when:* every C3 rule discriminates in the
  self-test. All five pass for `RefLwsClient`, and each of its five C3 twins fails exactly the
  rules aimed at it.
- **C4: authentication. Done 2026-10-05 (D-0082).**
  - The session OpenID Provider and client registration (§12.3).
  - Hosted CID documents and key download.
  - The credential and token-request rules, and the `tokenExpired` fault.

  *Done when:* the reference client authenticates all three ways, and a broken-credential
  twin fails. `RefLwsClient` uses a token from the session, then credentials it signs with
  alice's key, and bob's OpenID sign-in. It passes all 44 rules, and each of its 14 C4 twins,
  ten of them with broken credentials, fails exactly the rule aimed at it.
- **C5: notifications. Done 2026-10-05 (D-0083).** Signed deliveries under §8.3's guard, the
  forgery faults, and the receiver rules. *Done when:* the self-test's reference inbox refuses
  every forgery and accepts every genuine delivery, and a twin that accepts everything fails.
  `RefInbox` refuses all four forgeries and acknowledges every genuine notification; its twin
  that accepts everything fails the four forgery rules, and each twin that skips one check of
  section 5.2 fails exactly that one.
- **C6: page and reports. Built 2026-10-05 (D-0084), except the pilot.**
  - The live page with guidance: a getting-started guide, the client's name and areas, a
    checklist grouped by area, failures with their specification links, and export.
  - EARL, JUnit XML and JSON export, and a docs-site page, "Testing a client".
  - Deployment on vulcan (§12.1). The pilot with one or two real clients waits for Erich to
    choose them.

  *Done when:* a developer who has never seen Touchstone gets from the start page to an
  exported report on their own. A scripted Firefox walk does it: it names a client on the start
  page, takes a token from the session page, sends requests, finds the failure and opens its
  exchange, starts a task, and downloads all three exports, with no script error.
- **C7 (optional): proxy mode** (§10). **Done 2026-10-05 (D-0085).** Also read-only MCP tools
  over a session (`get_client_session`, `get_client_findings`, `get_client_exchange`), so a
  developer's coding agent can read the feedback. They are read-only and redacted, and they
  never drive a client.

  *Done:* `ProxyRulesSelfTest` runs the reference client through the proxy against the
  reference server deployed as a front-door target. It passes all 32 rules a proxy session can
  judge, and each of the 30 twins whose mistake shows without a trap fails exactly its aimed
  rules. For each of the server's requests, the proxy inferred the role the server itself
  gave it. `ClientSessionToolsTest` reads a live session through the three tools.
