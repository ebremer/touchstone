# This format compared with lws-test-suite's

This compares format 0.2.0 with the format of lws-test-suite as it stands. `lws10/manifest.yaml`
there is YAML-LD: it has an `@context` and an `@graph`, and `manifest.jsonld` carries the
same data as JSON-LD. The three authentication manifests are JSON-LD only. The snapshot
compared is lws-test-suite commit b8cb134 (20 September 2026). Every count below was
measured on its files.

In short: 0.2.0 keeps what lws-test-suite gets right, which is its vocabulary, its one-request
tests and its declared prerequisites. It fixes what stops those tests running against a real
server or makes them contradict the draft. It is a merge, not a rival. All 27
lws-test-suite tests have a counterpart here (`COVERAGE.md`).

## What the merged format took from lws-test-suite

- **The vocabulary.** `request`, `response`, `method`, `url`, `contentType`, `linkHeaders`,
  `rel`, `href`, `mediaType`, `otherHeaders`, `headerName`, `headerValue`, `body`, `bodyURL`,
  `statusCode`, `authenticationChallenge`, `wwwAuthenticate`, `asUri`, `realm`, `prereqs`,
  `hierarchy`, `authorization`, `traits`, `status`, `source`, `name`, `entries`, `include`,
  all with lws-test-suite's IRIs in the `lwst:` namespace.
- **One-request tests.** A test that is one exchange has `request` and `response` directly
  on it. 30 of the 101 definitions are written that way.
- **Declared prerequisites.** The state a test needs is described, not scripted, and the
  engine establishes it. 25 definitions declare prerequisites instead of opening with
  setup requests.
- **The manifest layout.** `Manifest` nodes with `entries` and `include`, one directory per
  suite, and fixtures beside the manifests.

## The same tests in both formats

A private container refuses an anonymous request. First lws-test-suite's version:

```yaml
      - "@type": NegativeTest
        "@id": "#getContainer-private-unauthorized"
        name: getContainer-private-unauthorized
        traits: [Get, Private, Container, Authz]
        status: Proposed
        source: https://w3c.github.io/lws-protocol/lws10-core/#authorization
        prereqs:
          hierarchy:
            - url: /alice/notes/
              authorization:
                read: Role-Owner
                write: Role-Owner
        request:
          method: GET
          url: /alice/notes/
        response:
          statusCode: 401
          authenticationChallenge:
            wwwAuthenticate: Bearer
            asUri: https://authorization.example
            realm: https://storage.example/alice/
```

The merged format:

```yaml
  - id: "#getContainer-private-unauthorized"
    type: NegativeTest
    name: getContainer-private-unauthorized
    label: An anonymous request for a protected container is refused with 401 and a conforming challenge
    status: Proposed
    level: MUST
    source:
      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#authorization-server-discovery
      - https://www.rfc-editor.org/rfc/rfc6750#section-3
    traits: [Get, Container, Private, Authn]
    requires: [Authentication]
    as: anonymous
    request:
      method: GET
      url: "${test.container}"
      accept: application/lws+json
    response:
      statusCode: 401
      authenticationChallenge:
        wwwAuthenticate: Bearer
        asUri: {matches: '^https?://'}
        realm: {matches: '^https?://'}
```

The shape is the same. The differences are the point:
- **The URL is the test's own.** The container is fresh, and the server chose its URI.
- **Nothing depends on the server's hostname.** The expected `asUri` and `realm` accept any
  absolute URI, where theirs hard-code `authorization.example` and `storage.example`.
- **The test says what it is.** It names its level, cites a dated draft, and says that it
  needs a target which enforces authentication.

A public container can be read without credentials. First lws-test-suite's `getContainer`,
abridged:

```yaml
        prereqs:
          hierarchy:
            - url: /alice/notes/
              authorization:
                read: Role-Public
                write: Role-Owner
        request:
          method: GET
          url: /alice/notes/
          otherHeaders:
            - headerName: Accept
              headerValue: application/lws+json
        response:
          statusCode: 200
          contentType: application/lws+json
          linkHeaders:
            - rel: linkset
              hrefTemplate: /alice/notes/.meta
              mediaType: application/linkset+json
            - rel: storageDescription
              hrefTemplate: /alice/description
          bodyURL: containers/alice-notes.json
```

The merged format's `getContainer-public-read`:

```yaml
    prereqs:
      hierarchy:
        - container: notes
          authorization:
            read: [anonymous]
    as: anonymous
    request:
      method: GET
      url: "${notes}"
      accept: application/lws+json
    response:
      statusCode: 200
      contentType: application/lws+json
      json:
        - pointer: /type
          hasValue: Container
```

The prerequisite is theirs, declared the same way. In the merged format, though, it can be
carried out. The engine creates `notes` wherever the server puts it, and makes it public
with the one mechanism the draft defines, an access grant (EXECUTION.md section 4.3). Their
version assumes things the draft does not define: a fixed path, a `.meta` linkset location,
a `storageDescription` relation from an older draft, and a byte-exact listing of resources
that no test creates.

## Where the merged format is stronger

### 1. It can run against a real server

- **Theirs:** 21 of the 27 tests name hosts reserved for examples: `authorization.example`
  16 times, `id.example` 13, `storage.example` 7, `identity.example` 4 and `idp.example` 4.
  No live server has those names, so every one of those assertions fails wherever it runs.
  Paths are fixed as well: 7 distinct paths, with `/alice/notes/` shared by 13 tests, so
  tests collide and assume a layout the server does not choose.
- **Merged:** every URL the server chooses is a variable: `${test.container}`, prerequisite
  names, captured `Location` and `Link` targets, and discovered services. Each test runs in
  its own container. The lint rejects any executable value that names an example host.

### 2. Its prerequisites can be carried out

- **Theirs:**
  - The access keys are `read`, `write`, `append` and `control`. The LWS draft defines
    four actions: `read`, `modify`, `create` and `delete` (WD section 11.3.2).
    The tests grant `write` 18 times, an action no LWS server recognises.
  - The roles are `Role-Owner`, `Role-Public` and `Role-Authenticated`. The draft has no
    assignee meaning "any authenticated agent" (section 11.3.3 requires a URI), and no test
    uses `Role-Authenticated`.
  - Nothing says how a harness makes a resource public.
- **Merged:**
  - The actions are the draft's four.
  - Assignees are identities: `anonymous` means the public (`foaf:Agent`), and a named
    identity means that agent. alice, the owner, needs no grant.
  - EXECUTION.md specifies the exact access grant the engine posts. If the target has no
    grant service, the target's provisioning adapter may grant the access. If neither can,
    the test is inapplicable rather than failed.

### 3. Its expectations mean one thing

- **Theirs:** the format does not say:
  - whether `contentType` ignores parameters;
  - whether `linkHeaders` must match exactly or only include the listed links, or how
    relative targets resolve;
  - whether a body is compared as bytes, as JSON or as a graph;
  - how a challenge's parameters are parsed, or how `asUri` is compared.

  Two harnesses can give opposite verdicts on the same response.
- **Merged:** EXECUTION.md sections 7 and 8 define every comparison. Media types compare
  by essence. Links are parsed per RFC 8288 and resolved. Challenges are parsed per RFC
  9110, in any parameter order and quoting. Regular expressions use a dialect every common
  engine shares.

### 4. It can express flows

- **Theirs:** every test is exactly one request, and none of the 27 has more. So a test
  cannot:
  - delete a resource and then check that it is gone;
  - read an ETag and send it back;
  - exchange a token and then use it.

  Such checks are dropped, or pushed into fixtures that assume the answer.
- **Merged:** the one-request shape stays for tests that are one request. `steps`, with
  captured values, cover the rest.

### 5. Credentials are identities, not placeholders

- **Theirs:**
  - 7 tests carry literal placeholders such as `<subject-token>`, `<expired-token>` and
    `<did-key-bad-signature-jwt>`, which a harness has to guess how to fill.
  - `prereqs.authentication` comes in two shapes: a string in 9 tests, an object in 4.
  - One test supplies credentials in two places at once.
- **Merged:**
  - A step names who sends it (`as: bob`, `as: alice-expired`).
  - `identities.yamlld` defines how each credential is made, including the deliberately
    broken ones.

### 6. Each test has one level and a pinned source

- **Theirs:**
  - No test declares a level. `ValidationTest` implies MUST, yet some tests check SHOULD
    behaviour, so a missing SHOULD makes a server fail.
  - All 19 `source` links point at the undated editor's draft, so drift is invisible. The 8
    authentication tests have no source at all.
- **Merged:**
  - `level` is required, and a SHOULD check is always its own test.
  - `source` must be a dated snapshot with an anchor, and the lint checks that the anchor
    exists in that snapshot.

### 7. The RDF is sound

- **Theirs:**
  - `mf:` is bound to `https://www.w3.org/ns/test-manifest#`, which does not exist (HTTP
    404). The W3C test-manifest vocabulary is
    `http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#`.
  - An `@vocab` fallback turns any misspelt key into a valid-looking term instead of an
    error. `source` itself is defined only through that fallback.
  - `locationURL` is typed `@id`, so a server's `Location` would be resolved against the
    manifest file's own URL.
  - `status` uses `mf:Proposed`, which the test-manifest vocabulary does not define.
- **Merged:**
  - The real `mf:` namespace, and `rdft:approval` for status, as W3C test suites use.
  - A `@protected` context with no `@vocab`. JSON-LD safe mode makes a dropped or misspelt
    term an error.
  - `location` is a capture.

### 8. It is checked

- **Theirs:**
  - Nothing validates the files. The YAML and JSON-LD copies disagree on 8 `bodyURL`
    values and 1 `include`.
  - The authentication manifests are reachable only through symlinks into an AI-sandbox
    output path.
- **Merged:**
  - The YAML-LD is the only source, and the JSON-LD is generated from it.
  - Six checks run on every change: YAML 1.2 and 1.1 agreement, strict JSON Schema, JSON-LD
    safe mode, the lint, vocabulary parity and coverage regeneration.
  - Negative controls prove the schema rejects what it should, and an export trial proves
    the JSON-LD graph equals the YAML-LD one.

### 9. It is larger and current

There are 101 tests against 27, and the 27 counterparts follow the 21 September 2026 draft:
- DELETE answers 204, where lws-test-suite expects 200;
- PUT may answer 200 or 204;
- discovery uses `rel="https://www.w3.org/ns/lws#storage"` and `application/lws+cid`;
- the did:key suite uses the `jwt` token type.

## What it costs

- **More to learn and to implement.**
  - Steps, captures, templates and the matching operators are more than their format asks
    a reader to know.
  - EXECUTION.md is the contract a second, independent harness must follow, and it is long.

  The one-request shape and declared prerequisites keep the common case as short as theirs,
  but the rest is still there.
- **Grants depend on the target.** A declared public resource needs the storage's access
  grant service, or the target's adapter. Without either, those tests are inapplicable.
  Their format is silent about how to set up access at all, so it never faces this, but it
  cannot run either.
- **No RDF graph assertions yet.** Neither format has them. Both wait for W3C to publish the
  LWS JSON-LD context (`README.md`, open question 7).
- **Not yet executed.** No engine runs these definitions yet. Nothing in lws-test-suite's
  repository runs its manifests either: `manifest.html` displays them. Until an engine runs
  these, some choices here have not met a real server.

## What would change in lws-test-suite's files

Adopting the merged format is a vocabulary proposal to the test group. For each change:

| lws-test-suite today | Merged format | Why |
|---|---|---|
| `url: /alice/notes/` in `prereqs` and `request` | `container: notes`, then `url: "${notes}"` | The server chooses URIs (WD section 9.2). |
| `authorization: {read: Role-Public, write: Role-Owner}` | `authorization: {read: [anonymous]}`; the owner is implicit | The draft's actions and assignees (sections 11.3.2 and 11.3.3). |
| `authentication: https://id.example/alice` | `as: alice` | An identity the harness can act as, not a host no server has. |
| `asUri: https://authorization.example` | `asUri: {matches: '^https?://'}`, or a template | The expected value must fit the server under test. |
| `locationURL` | `location: {capture: …}` | A `Location` is the server's, not relative to the manifest. |
| `urlTemplate`, `hrefTemplate` | `url`, `href` | Every string is a template; one term per meaning. |
| `slug` | dropped | The draft no longer defines `Slug`. |
| no level | `level: MUST`, `SHOULD` or `MAY` | A SHOULD must not decide conformance. |
| `source` via `@vocab`, undated | `dcterms:source`, dated snapshot and anchor | Traceable, and drift becomes visible. |
| `mf:` at `https://www.w3.org/ns/test-manifest#` | the W3C test-manifest namespace | The former does not exist. |
| `status: Proposed` as `mf:Proposed` | `rdft:approval` values | What W3C test suites use. |
| `@vocab` fallback | none, and `@protected` | A misspelt key must fail, not become a term. |
| one request per test | the same, or `steps` for flows | Some requirements need more than one exchange. |

The `lwst:` namespace, `https://www.w3.org/ns/lws-tests/v1#`, is shared by both formats. It
is not yet published, and W3C would need to allocate it (`README.md`, open question 8).
