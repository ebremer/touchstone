# Judging the LWS client rules

**Status: proposed, format 0.8.0 (2026-10-05, DECISIONS.md D-0078), awaiting Gate C.** This
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
exchanges, its *trials*, and states what must hold of each (sections 5 to 7). Over the
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
  header fields in order, and the body. Bodies are kept whole: one larger than the session's
  request bound (1 MiB) is refused `413` before any server sees it (CLIENT-TESTING.md section
  8.2).
- **The answer:** the status, the header fields, and the first 64 KiB of the body.
- **Its annotations** (section 4).

A rule sees the exchange as the developer sees it in the traffic log, redacted (section 9).
The recorder computes annotations from the request before redaction, so no rule needs a raw
credential.

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
  URL.

Any other URL of the session has no server, and its role is `unknown`. Phase C4 adds
`openidProvider` and `identityHost`.

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
| `unknown` | either, or none | nothing: no resource and no endpoint |

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
    listing, a page, the storage description, the metadata, a service's answer.

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

## 5. Conditions

`observe`, `expect` and a trigger's `after` are conditions on one exchange. A condition holds
when every term it has holds. The terms, in the order they are checked:

1. **`server`, `role`, `method`, `builtBy`, `builtFromRole`:** a value or a list. The
   annotation, or the request's method, equals a value listed. An absent annotation equals
   nothing.
2. **`statusCode`:** the status the session answered, matched as in `EXECUTION.md` section
   7.1.
3. **`issued`, `methodAdvertised`, `patchFormatAdvertised`, `queryFormatAdvertised`:** the
   annotation equals the boolean.
4. **`presentation`:** a value or a list. Every place in the annotation is listed, so
   `presentation: bearer` fails a request that also carried its token in the query string.
5. **`contentType`:** the essence of the request's `Content-Type` equals the value, as in
   `EXECUTION.md` section 7.2. A request without one fails.
6. **`linkHeaders`, `otherHeaders`:** as in `EXECUTION.md` sections 7.4 and 7.5, applied to the
   request's header fields, with link targets resolved against the request URL. Redacted
   fields are seen redacted (section 9).
7. **`bodyMatches`, `json`:** as in `EXECUTION.md` sections 7.7 and 7.8, applied to the request
   body. `equalsIri` resolves against the request URL. A body that is not JSON fails every
   `json` term.
8. **`anyOf`:** at least one of the listed conditions holds.

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
  `Proxy-Authorization` values appear as `[redacted <fingerprint>]`, and so do tokens anywhere
  else: an `access_token` query parameter, and the `access_token`, `subject_token`,
  `refresh_token` and `id_token` members of form and JSON bodies. The fingerprint is the first
  12 hexadecimal digits of the value's SHA-256. Raw values exist only in memory, while
  annotations are computed.
- **Request bodies are untrusted input.** They are parsed with the offline loaders, and no
  JSON-LD context is ever fetched (D-0026). Nothing a client sends reaches a shell, a file path
  or a query language.
- **Judging sends nothing.** Evaluating rules makes no request of any kind.

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
