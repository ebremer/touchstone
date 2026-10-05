# Judging the LWS client rules

**Status: frozen, format 0.11.0 (2026-10-05, DECISIONS.md D-0083; 0.10.0 the same day, D-0082;
0.9.0 the same day, D-0081; 0.8.0 the same day, D-0079, after Gate C).** 0.9.0 adds tasks and
faults (section 6) and two annotations, `repeat` and `containerEmpty` (sections 4.6 and 4.7).
0.10.0 adds the session's OpenID Provider and identity documents (sections 4.1 and 4.2), what the
recorder knows of a token request (sections 4.9 and 4.10), the conditions `form` and `credential`
(section 5), and the fault `tokenExpired`. 0.11.0 adds deliveries, the notifications the session
sends a client's inbox (sections 3, 4.11 and 4.12), and four forgery faults. None changes
anything an earlier rule relies on. This
is the contract the client service (`harness-clients`) must implement to judge a client's
traffic against the client rules, the `ObservationTest` entries under `lws10/clients/`.
`EXECUTION.md` is the contract for server tests, where Touchstone plays the client. Here the
developer drives their own client against a session ([CLIENT-TESTING.md](../CLIENT-TESTING.md)),
and Touchstone plays the servers and judges what it receives. Where this document cites
`EXECUTION.md`, the rule there applies unchanged.

Key words MUST, SHOULD and MAY in this document are about the client service, not about the
client under test.

## 1. Scope

The service records every exchange of a session (section 3). It annotates each with facts
only the server knows (section 4) and judges it against every rule in scope. A rule selects
exchanges, its *trials*, and states what must hold of each (sections 5 to 7). Some rules need
a task, which the developer starts, and some tasks arm a fault (section 6). Over the
session, a rule's trials decide its outcome (section 8).

Everything a rule asserts is in its definition. The service adds no assertions and softens
none, and a rule's `guidance` is advice that never decides anything.

## 2. Loading rules

1. **Where they live.** The root is `lws10/clients/manifest.yamlld`, traversed as in
   `EXECUTION.md` section 2.4. The server root `lws10/manifest.yamlld` does not include it,
   and no server manifest includes anything under `clients/`. Every entry under `clients/` is
   an `ObservationTest`, and no `ObservationTest` lives anywhere else.
2. **YAML, schema and JSON-LD** are checked as in `EXECUTION.md` sections 2.1 to 2.3, against
   the same schema.
3. **Lint.** The service refuses to start on rules that fail a check. The checks of
   `EXECUTION.md` section 2.5 that apply hold here too:
   - names are unique across all manifests, server and client;
   - `id` is `#` + `name`;
   - no value names an example host;
   - every `requirements` IRI is in the catalog.

   Client rules add four checks:
   - a rule cites at least one requirement that binds a `Client` or a `Receiver`
     (`touchstone:appliesTo`, D-0076), the mirror image of the server-side check;
   - a rule's level is no stronger than the strongest level of the requirements it cites;
   - no value contains `${`, because rules have no variables;
   - no expectation has `capture`, because nothing passes from one trial to another.

## 3. The exchange

The recorder keeps each request and its answer.

- **The request:** the method, the URL resolved against the session's public origin, the
  header fields in order, and the body. Rules judge the body whole, as the exchange is
  recorded; the traffic log then keeps its first 64 KiB. A body larger than the session's
  request bound (1 MiB) is refused `413` before any server sees it (CLIENT-TESTING.md section
  8.2).
- **The answer:** the status, the header fields, and the first 64 KiB of the body.
- **Its annotations** (section 4).

A rule sees the exchange as the developer sees it in the traffic log, redacted (section 9).
The recorder computes annotations from the request before redaction, so no rule needs a raw
credential.

**A delivery** (since 0.11.0) is the other way round: a notification the session POSTs to an
inbox a client named, in a subscription or an access grant, and the inbox's answer. Its role is
`delivery` and it has no `server`; its URL is the inbox's, `statusCode` is the inbox's status,
and the body judged is the session's. When nothing answered, because the outbound guard refused
the inbox (CLIENT-TESTING.md section 8.3) or the connection failed, its status is 0, which no
`statusCode` matches. The recorder computes for it only `deliverySignature` and `fault`.

Two kinds of exchange stay in the log but are never trials:
- CORS preflights (role `preflight`), which the browser sends rather than the client's code;
- requests refused by a session bound (role `limited`: rate, body size or storage), which no
  server saw.

## 4. Annotations

The recorder MUST compute these for every exchange. It reads the session's state as it was
before the exchange, so that an answer never vouches for its own request.

### 4.1 `server`

Which of the session's servers the request addressed:
- `storage`, for a URL under `{base}/s/{sid}/storage/`;
- `authorizationServer`, for one under `{base}/s/{sid}/as/`, or for its RFC 8414 metadata
  URL;
- `openidProvider` (since 0.10.0), for one under `{base}/s/{sid}/op/`;
- `identityHost` (since 0.10.0), for one under `{base}/s/{sid}/id/`, where the identities'
  documents are.

Any other URL of the session has no server, and its role is `unknown`.

### 4.2 `role`

What the request addressed, as the session's server found it:

| Role | Server | What the URL is |
|---|---|---|
| `storageDescription` | storage | the storage description |
| `container` | storage | a container at its own URL, the storage root included |
| `page` | storage | a page of a container's listing |
| `dataResource` | storage | a data resource |
| `linkset` | storage | a resource's linkset |
| `accessGrants`, `accessRequests` | storage | the access grant or access request service's endpoint |
| `accessGrant`, `accessRequest` | storage | a grant or request that service created |
| `subscriptions` | storage | the NotificationService's endpoint |
| `subscription` | storage | a subscription it created |
| `typeIndex`, `typeSearch` | storage | the Type Index Service, the Type Search Service |
| `searchPage` | storage | a page of index or search results |
| `decoy` | storage | the decoy (CLIENT-TESTING.md section 6.1) |
| `asMetadata`, `asJwks`, `asToken` | authorizationServer | its metadata, JWKS and token endpoint |
| `opDiscovery`, `opJwks`, `opAuthorize`, `opToken` | openidProvider | its OpenID Connect Discovery document, JWKS, authorization endpoint (with its sign-in form) and token endpoint |
| `identityDocument` | identityHost | an identity's controlled identifier document |
| `keyDocument` | storage | a document under the storage that claims to be its description, which the forgery `forgedForeignKeyDocument` names (since 0.11.0) |
| `delivery` | none | a notification the session sent an inbox (section 3; since 0.11.0) |
| `unknown` | any, or none | nothing: no resource and no endpoint |

A role is the server's view, not the client's intent. A URL the client built that names
nothing is `unknown`, and `builtBy` says what it was built from.

### 4.3 `issued`, `builtBy` and `builtFromRole`

**The ledger** holds every URL of the session that the session handed out, without its
fragment, and the first way it was handed out:
- the storage URL, which the session's description gives;
- in an answer:
  - the `Location` and `Content-Location` fields;
  - every `Link` target;
  - a challenge's `as_uri`, and the metadata URL that RFC 8414 section 3.1 derives from it;
  - every absolute `http` or `https` string in a JSON body that the server generated: a
    listing, a page, the storage description, the metadata, a service's answer, an identity
    document, the OpenID Provider's discovery document;
  - for an OpenID Provider an identity document names, the discovery URL that OpenID Connect
    Discovery section 4 derives from its issuer.

URLs are resolved against the request URL and compared as strings, as in `EXECUTION.md`
section 8.

**`issued`** is true when the request URL, without its fragment, was in the ledger before
the request.

**`builtBy`.** For a URL that was not issued, the recorder looks for the issued URL it was
built from. That URL has the same scheme and authority, and is related in one of two ways:
- `query`: it has the same path and a different query, or no query where this one has one;
- `path`: failing that, its path is a proper prefix of this one's ending at a `/`, so the
  built URL extends it; or the two paths are equal up to and including their last `/`, so the
  built URL is a sibling.

Where several URLs qualify, the one with the longest path wins, then the one issued first.
When none does, `builtBy` is absent.

**`builtFromRole`** is the role of the most recent exchange for the URL it was built from. It
is absent when the client never requested that URL.

### 4.4 `presentation`

The set of places the request carried a credential:
- `bearer`: an `Authorization` field with the scheme `Bearer`, compared case-insensitively;
- `otherScheme`: an `Authorization` field with any other scheme;
- `query`: an `access_token` query parameter, or a query parameter whose value is a token
  the session issued;
- `form`: an `access_token` member of an `application/x-www-form-urlencoded` body;
- `otherHeader`: a token the session issued, in any other header field (a cookie, a header of
  the client's own).

The set is `{none}` when there is no credential. The session's tokens are every access token
it handed out, through the session API or its token endpoint. The recorder compares them by
value, in memory.

### 4.5 `methodAdvertised`, `patchFormatAdvertised` and `queryFormatAdvertised`

For every URL, the recorder keeps the last value of `Allow`, `Accept-Patch` and `Accept-Query`
in an answer for that URL with a status of 2xx, 304, 405 or 415. It keeps each field
separately, so an answer without one of them leaves its earlier value in place. Answers to
any identity count.

From those values, as they stood before the request:
- `methodAdvertised`: the request's method is listed in `Allow`. Methods compare
  case-sensitively (RFC 9110 section 9.1).
- `patchFormatAdvertised`: the essence of the request's `Content-Type` is listed in
  `Accept-Patch`.
- `queryFormatAdvertised`: the essence of the request's `Content-Type` is listed in
  `Accept-Query`.

Each is false when the field was never seen for the URL, or when the request has no
`Content-Type`.

### 4.6 `repeat` (since 0.9.0)

For every URL, the recorder keeps the method, the essence of the `Content-Type` and the
SHA-256 of the body of the latest request to it. `repeat` is true when the request has the
same three as the request to the same URL before it. Preflights and requests a bound refused
are left out of this, as they are left out of trials.

### 4.7 `containerEmpty` (since 0.9.0)

True when the request addressed a container, its role being `container`, that had no members
when the request arrived. False for anything else.

### 4.8 `fault`

The fault that fired on the exchange (section 6.2), or none. It appears in the traffic log. A
rule does not name it: a rule's trigger is the answer a fault produces, such as a 503, so a
server that gives the same answer for a reason of its own triggers the rule as well.

### 4.9 The credential of a token request (since 0.10.0)

For a POST to the session's token endpoint (role `asToken`) with an
`application/x-www-form-urlencoded` body, the recorder reads the `subject_token` parameter, the
authentication credential, before redaction. Each of these is absent when the request has no
such body or no `subject_token`:

- **`credentialSource`:** where the credential came from.
  - `openidProvider`: an ID Token the session's OpenID Provider issued, compared by value.
  - `authorizationServer`: an access token the session issued (section 4.4).
  - `selfIssued`: any other JWT whose `kid` header, or `iss`, `sub` or `client_id` claim, names
    one of the session's identities: its URL, or that URL with a fragment. That is a credential
    the client signed itself, or tried to.
  - `other`: anything else, such as a JWT about a subject outside the session.
- **The credential's header and claims,** when it is a compact JWT: three base64url segments,
  the first two of them JSON objects. The `credential` condition (section 5) judges them. The
  signature is never kept.
- **`audienceIncludesAs`:** the JWT's `aud` claim is the session's authorization server's issuer
  identifier, or an array that contains it. False when `aud` is absent.
- **`identifiersAgree`:** the JWT's `sub`, `iss` and `client_id` claims are the same string.
  False when any is absent or is not a string.

### 4.10 `realmContainsRequest` (since 0.10.0)

For a token request whose form body has a `resource` parameter. The recorder keeps, for each
realm a 401 answer's challenge named, the URL of the latest request answered with it.
`realmContainsRequest` is true when that URL is logically contained within the realm, and false
when it is not. It is absent when no 401 named the realm.

A URL, without its query and fragment, is contained within a realm when it equals the realm or
starts with it. A realm that does not end in `/` must be followed by `/`. URLs compare as
strings, as in `EXECUTION.md` section 8. This is what lws10-core section 5.2.1 asks a client to
check before it asks for a token: "that the URI of the originating request is logically
contained within the realm".

### 4.11 `deliverySignature` (since 0.11.0)

For a delivery, how the session made its HTTP Message Signature:
- `genuine`: with the key the storage description publishes, as lws10-notifications-webhook
  section 5 describes;
- `unpublishedKey`, `alteredBody`, `keyidWithoutFragment`, `foreignKeyDocument`: forged, by the
  fault of section 6.2 with the same name after `forged`.

Absent for anything but a delivery.

### 4.12 `inboxShared` (since 0.11.0)

For a POST to the NotificationService whose JSON body has a string `inbox`: whether a
subscription the storage held when the request arrived already delivers to that inbox. Absent for
anything else.

## 5. Conditions

`observe`, `expect` and a trigger's `after` are conditions on one exchange. A condition holds
when every term it has holds. The terms, in the order they are checked:

1. **`server`, `role`, `method`, `builtBy`, `builtFromRole`, `credentialSource`,
   `deliverySignature`:** a value or a list. The annotation, or the request's method, equals a value listed. An absent annotation
   equals nothing.
2. **`statusCode`:** the status the session answered, matched as in `EXECUTION.md` section
   7.1.
3. **`issued`, `methodAdvertised`, `patchFormatAdvertised`, `queryFormatAdvertised`,
   `repeat`, `containerEmpty`, `audienceIncludesAs`, `identifiersAgree`,
   `realmContainsRequest`, `inboxShared`:** the annotation equals the boolean. An absent annotation equals
   neither `true` nor `false`.
4. **`presentation`:** a value or a list. Every place in the annotation is listed, so
   `presentation: bearer` fails a request that also carried its token in the query string.
5. **`contentType`:** the essence of the request's `Content-Type` equals the value, as in
   `EXECUTION.md` section 7.2. A request without one fails.
6. **`linkHeaders`, `otherHeaders`:** as in `EXECUTION.md` sections 7.4 and 7.5, applied to the
   request's header fields, with link targets resolved against the request URL. Redacted
   fields are seen redacted (section 9).
7. **`bodyMatches`, `json`, `form`:** as in `EXECUTION.md` sections 7.7 and 7.8, applied to the
   request body. `equalsIri` resolves against the request URL. A body that is not JSON fails
   every `json` term. `form` (since 0.10.0) takes `json` expectations and applies them to an
   `application/x-www-form-urlencoded` body seen as a JSON object: each parameter name, decoded,
   with its first value, a string. Any other body fails it. Credentials in it are seen redacted
   (section 9).
8. **`credential`** (since 0.10.0): `header` and `claims`, each a list of `json` expectations, as
   in `EXECUTION.md` section 7.9, applied to the header and claims of the token request's
   credential (section 4.9). A request without a credential that is a JWT fails it.
9. **`anyOf`:** at least one of the listed conditions holds.

A rule has no variables, so every template in it is a literal. Regular expressions are
those of `EXECUTION.md` section 8.

## 6. Selecting trials

The service applies every rule in scope to every exchange, in the order the log numbers
them, as each is recorded.

**Without `after`,** an exchange is a trial when it satisfies `observe`.

**With `after`,** a rule judges "the next X after Y". It keeps a list of open triggers, and
for each exchange, in order:
1. If the exchange satisfies the rest of `observe` and closes an open trigger, it is a trial,
   once, however many triggers it closes. It closes a trigger whose `sameTarget` is set when it
   requests the trigger's URL, fragments ignored, and any other trigger regardless of URL. All
   the triggers it closes close.
2. Then, if the exchange satisfies `after`, it opens a trigger.

So no exchange is the trial of its own trigger, and a trigger yields at most one trial. A
trigger that never meets a matching exchange stays open. It is evidence of nothing.

### 6.1 Tasks (since 0.9.0)

A rule's `task` is something the developer is asked to do so the rule can be tried. Its
`prompt` is what they read. They start it on the session page, or with
`POST {base}/sessions/{sid}/tasks/{rule name}`, and then do what it says with their client.

- **A task without `arm`** stands for the developer's intent, for a rule that needs to know
  what the client meant: a POST without `Link: rel="type"` creates a data resource, legal
  unless a container was meant. Such a rule has no `after`. Each start of its task opens a
  trigger, and the first exchange after it that satisfies `observe` is the trial. A rule like
  this is never tried until its task starts.
- **A task with `arm`** arms that fault when it starts (section 6.2). Such a rule selects its
  trials by `observe` alone, with or without `after`: as above, its trigger being the answer the
  fault produces; or, for a forged notification, the delivery itself, which `deliverySignature`
  describes (since 0.11.0). The task only makes the fault happen on demand.

### 6.2 Faults (since 0.9.0)

A fault fires once, on the next request it applies to, after a task arms it or
`POST {base}/sessions/{sid}/faults/{fault}` does. The first four make the session do something a
server may legally do. The forgeries (since 0.11.0) make it do what an attacker does, which an
inbox must withstand. The exchange a fault fired on carries its name (section 4.8).

| Fault | Applies to | What the session does |
|---|---|---|
| `methodNotAllowed` | a PUT to a linkset that supports PUT | answers 405, with an `Allow` that leaves PUT out, as a server that stopped supporting the optional PUT would |
| `lostCreateResponse` | a POST that creates a resource in a container | creates it, then answers 503 with `Retry-After` and no `Location`, as if the answer had been lost |
| `pageGone` | a request for a page of search results | answers 410, as for a page link that expired |
| `tokenExpired` (since 0.10.0) | a request to the storage with a valid access token, other than to the decoy | answers 401 with a challenge naming the storage's realm and `error="invalid_token"`, and refuses that token from then on, as for one that expired or was revoked |
| `forgedUnpublishedKey` (since 0.11.0) | the next notification | signs it with a key the storage description does not publish, its keyid naming the published one |
| `forgedAlteredBody` (since 0.11.0) | the next notification | signs it, then alters its body; the Content-Digest is the signed one |
| `forgedKeyidWithoutFragment` (since 0.11.0) | the next notification | signs it with the published key, its keyid the storage's URL without a fragment |
| `forgedForeignKeyDocument` (since 0.11.0) | the next notification | signs it with the key of a document under the storage whose id is the storage's, not its own URL's, and names that document's key in the keyid |

## 7. Judging a trial

A trial passes when every term of `expect` holds. Otherwise it fails. The evidence is the
first term that failed, in section 5's order: the term, what it expected, and what the
exchange had.

A trial is undecided (*cantTell*) when a term needs something the recorder did not keep. In
format 0.8.0 that cannot happen, since request bodies are kept whole, but the outcome exists
for later terms that need more.

## 8. Outcomes, verdict and EARL

| Outcome | When | EARL |
|---|---|---|
| passed | at least one trial, and every trial passed | `earl:passed` |
| failed | a trial failed. The first is kept as evidence; the rest are counted | `earl:failed` |
| cantTell | no trial failed, and at least one was undecided | `earl:cantTell` |
| untested | no trial yet | `earl:untested` |
| inapplicable | the developer declared the rule's `area` out of scope | `earl:inapplicable` |

- **Outcomes only get worse** within a session. *Reset* discards trials and open triggers and
  starts again.
- **Verdict.** Only MUST rules decide it. The report says "no MUST failure in N MUST rules
  exercised, of M that apply". It never says plain "conformant", because untested rules are
  coverage the session did not have, not evidence. A MUST rule that failed, or ended
  *cantTell*, rules out a clean verdict, as on the server side (`EXECUTION.md` section 9).
  SHOULD failures are warnings, and MAY rules are information.
- **EARL.** Each assertion has `earl:mode earl:semiAuto`, since a person drives the client and
  the service judges. `earl:test` is the rule's IRI, and the test case carries
  `touchstone:verifies` for each `requirements` IRI. The subject is the client as the developer
  named it, or the session.

## 9. Redaction and safety

- **Rules see redacted exchanges.** `Authorization`, `Cookie`, `DPoP` and
  `Proxy-Authorization` values appear as `[redacted <fingerprint>]`, and so do credentials
  anywhere else:
  - query parameters and the members of form bodies named `access_token`, `refresh_token`,
    `id_token`, `subject_token`, `actor_token`, `client_secret`, `code`, `code_verifier`,
    `password` or `assertion`, in every request and in a `Location` header;
  - the same members of the JSON answers of the token endpoints.

  The fingerprint is the first 12 hexadecimal digits of the value's SHA-256. Raw values exist
  only in memory, while annotations are computed. A credential's JWT header and claims are kept
  (section 4.9), since they are not secret, but its signature is not.
- **Request bodies are untrusted input.** They are parsed with the offline loaders, and no
  JSON-LD context is ever fetched (D-0026). Nothing a client sends reaches a shell, a file path
  or a query language.
- **Judging sends nothing.** Evaluating rules makes no request of any kind.
- **Deliveries go only where CLIENT-TESTING.md section 8.3 allows** (since 0.11.0): https URLs
  whose host resolves to public unicast addresses, checked as the connection is made, never
  redirected, bounded in time, size and number. A refused one is recorded with status 0.
- **The session's authorization server dereferences nothing outside the session** (since
  0.10.0). It validates a credential with the session's identity documents and the session's
  OpenID Provider, read in the same process, and refuses any subject, issuer or key elsewhere. No
  credential a client presents can make the service send a request.

## 10. Examples

The token rule. Every storage request with a credential is a trial, and it passes only when
the credential is in an `Authorization: Bearer` field and nowhere else:

```yaml
  - id: "#client-token-in-authorization-header"
    type: ObservationTest
    name: client-token-in-authorization-header
    label: The client presents its access token to the storage in an Authorization Bearer header and nowhere else
    status: Proposed
    level: MUST
    source:
      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#authorization-token-validation-presentation
      - https://www.rfc-editor.org/rfc/rfc6750#section-2
    traits: [Authz]
    area: core
    requirements:
      - https://example.org/touchstone/req/lws10-core/authz-bearer-presentation-rfc6750
    observe:
      server: storage
      presentation: [bearer, otherScheme, query, form, otherHeader]
    expect:
      presentation: bearer
    guidance: >-
      Send the access token as "Authorization: Bearer <token>" and in no other place: not in
      the query string, a form body, a cookie or a header of your own.
```

Page URLs. The page trap makes every page URL an opaque token, so a client that builds one
asks for a URL that was never handed out. `anyOf` lets the trials include those requests,
which the server sees as `unknown`:

```yaml
    observe:
      anyOf:
        - role: page
        - builtBy: query
          builtFromRole: [container, page]
    expect:
      issued: true
```

Methods a linkset advertised. The session's linksets refuse PUT, as the draft allows:

```yaml
    observe:
      role: linkset
      method: PUT
    expect:
      methodAdvertised: true
```

A policy member of an access grant. A term in `observe` keeps a document whose `access` is not
an array from failing this rule as well as the one about `access`:

```yaml
    observe:
      role: [accessRequests, accessGrants]
      method: POST
      json:
        - pointer: /access
          jsonType: array
    expect:
      json:
        - pointer: /access
          every:
            - pointer: /assignee
              jsonType: string
              matches: "^[A-Za-z][A-Za-z0-9+.-]*:"
```

A task for intent. The rule is tried on the first POST into a container after the developer
starts the task:

```yaml
    task:
      prompt: Create a container, in any container you like.
    observe:
      method: POST
      role: container
    expect:
      linkHeaders:
        - rel: type
          href: https://www.w3.org/ns/lws#Container
```

A task that arms a fault. The session creates the resource and then answers 503; the client's
next request to the container is the trial:

```yaml
    task:
      prompt: >-
        Create a data resource. The session will create it and then answer 503, as if the answer
        had been lost.
      arm: lostCreateResponse
    observe:
      server: storage
      after:
        server: storage
        method: POST
        statusCode: 5xx
        sameTarget: true
    expect:
      repeat: false
```

A credential's claims. Only credentials the client signed itself are trials, and the claim
must be a URI:

```yaml
    observe:
      role: asToken
      method: POST
      credentialSource: selfIssued
    expect:
      credential:
        claims:
          - pointer: /sub
            jsonType: string
            matches: "^[A-Za-z][A-Za-z0-9+.-]*:"
```

The realm check. Each 401 from the storage opens a trigger, and the client's next token request
naming a realm is the trial. The decoy's 401 names a realm that does not contain it, so a client
that does not check asks for a token for that realm:

```yaml
    task:
      prompt: >-
        Open the first entry of the storage's root container, then any resource you can read. The
        session answers that request once with 401, as if your access token had expired: get a
        new token and try again.
      arm: tokenExpired
    observe:
      role: asToken
      method: POST
      form:
        - pointer: /resource
          jsonType: string
      after:
        server: storage
        statusCode: 401
    expect:
      realmContainsRequest: true
```

The next search after a refused format. Each 415 opens a trigger, and the next QUERY to the
same endpoint is the trial:

```yaml
    observe:
      role: typeSearch
      method: QUERY
      after:
        role: typeSearch
        method: QUERY
        statusCode: 415
        sameTarget: true
    expect:
      contentType: application/lws-query+json
```
