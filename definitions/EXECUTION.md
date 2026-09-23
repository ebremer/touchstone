# Executing the LWS test definitions

**Status: proposal 0.2.0.** This is the contract an engine that runs `definitions/` must
implement. 0.2.0 merges two strengths of lws-test-suite's format into it: the
one-exchange short form and declared prerequisites (`COMPARISON.md`). It is written for that engine's authors, human or AI: where a definition
relies on a behaviour, the behaviour is specified here, and an engine that does something
else is wrong even if every test it runs passes. `README.md` explains why the
definitions exist, `vocab.yamlld` gives the RDF meaning of each term, and
`schema/definitions.schema.json` gives their syntax.

Key words MUST, SHOULD and MAY in this document are about the engine, not about the
server under test.

## 1. Scope

The engine plays the client. It loads definitions, provisions a run root on a registered
target, runs each test's steps against the target's storage server and authorization
server, and reports an outcome per test. Everything a test asserts is in its definition.
The engine adds no assertions and softens none.

## 2. Loading definitions

1. **YAML.** Parse every `.yamlld` file as YAML 1.2 with the Core Schema, as YAML-LD
   requires. Use a YAML 1.2 parser (for Java, snakeyaml-engine), not a YAML 1.1 one:
   SnakeYAML 1.x and jackson-dataformat-yaml turn `yes`/`on` into booleans and
   `2026-09-21` into a date. The definitions are written so that YAML 1.1 and 1.2 read
   them identically, which CI checks (README.md, "Authoring rules"), but the engine
   must not depend on that. Reject a file with more than one YAML document, duplicate
   keys, tags, or anchors/aliases.
2. **Schema.** Validate the parsed JSON against `schema/definitions.schema.json`. An
   invalid document stops the run before any request is sent.
3. **JSON-LD.** Expand each document with a document loader that serves only the
   repository's own `context.jsonld` and `touchstone.jsonld`. It must never touch the
   network (the same rule as DECISIONS.md D-0026). Expansion in safe mode (a dropped key
   is an error) is a second typo guard. The executor itself may work on the JSON form,
   whose shape the schema fixes. The RDF form supplies test IRIs and the
   `touchstone:verifies` links for EARL.
4. **Traversal.** Start at `lws10/manifest.yamlld`. Visit its `include`s depth-first,
   in order, then its `entries` in order. The identity registry is
   `lws10/identities.yamlld`.
5. **Lint.** Before a run starts, refuse (exit code 2, as for an uncatalogued
   requirement in D-0039) any set of definitions that fails a check:
   - every test `name` is unique across all manifests, and `id` is `#` + `name`;
   - every test has either `steps`, or both `request` and `response` (the short form),
     never both;
   - every prerequisite variable is new, and an entry's `in` names an earlier container
     entry;
   - every `authorization` assignee is `anonymous` or a StorageAccessToken identity other
     than alice, and a test that grants access `requires` Authentication;
   - no value in `prereqs`, `steps`, `request` or `response` names an example host
     (RFC 2606 and RFC 6761: `example`, `*.example`, `example.com`, `example.net`,
     `example.org`). Such a value can never match a live server.
   - every `${...}` in a step is a built-in, derived or identity variable, or was bound
     by a prerequisite or captured by an earlier step. A `jwt` block may also use captures made in its own
     step.
   - every `as:` names a registered NoCredential or StorageAccessToken identity;
   - every `credential.<name>` names a SubjectCredential identity whose capabilities
     the test's `requires` includes;
   - every `bodyURL` resolves to a file;
   - every `requirements` IRI is in the loaded catalog.

A test's identity is `<manifest path without extension>#<name>` (for example
`core/containers#getContainer`). That is the same in YAML-LD and in the exported
JSON-LD, and it is what maps a Touchstone result to an lws-test-suite test.

## 3. Templates and variables

A template is a string in which `${expression}` is replaced by a value. It is plain
substitution: nothing is percent-encoded, escaped or trimmed. Templates occur in
`url`, `href`, `headerValue`, `ifMatch`, `body`, `paramValue`, `equalsIri`, `differsFrom`,
`token`, `jwks`, the identity templates, and every string inside `bodyJSON`,
`bodyForm`, `equals` and `hasValue`.

```
expression := name ( "." name )*          e.g. test.container, identity.bob.webid, created
            | "now" ( ("+" | "-") digits )?
name       := [A-Za-z][A-Za-z0-9_-]*
```

**JSON values.** Inside a JSON value (`bodyJSON`, `equals`, `hasValue`, identity
templates), a string that is exactly one expression takes the expression's JSON
value. `"${now+300}"` becomes a number (such as 1790000300), and `"${self.publicJwk}"`
becomes an object. Anywhere else the value is converted to a string.

**Variables.**

| Variable | Value |
|---|---|
| `target.baseUrl` | The storage URL the target is registered with in `targets.yaml`. |
| `run.root` | The run root container (section 4.1). |
| `test.container` | The test's own container (section 4.2): empty when step 1 runs. |
| `uuid` | A fresh random UUID, lower case, each time it is evaluated. |
| `now`, `now+N`, `now-N` | Current time in whole seconds since the epoch, offset by N seconds. |
| `storage` | Derived: the target of the `Link` whose rel is `https://www.w3.org/ns/lws#storage` on a GET of `${test.container}` as alice with `Accept: application/lws+json`, resolved against that URL. |
| `as.uri`, `as.realm` | Derived: from an anonymous GET of `${test.container}`. The response must be 401 with a `Bearer` challenge carrying `as_uri` and `realm`; these are their values. |
| `as.metadataUrl` | Derived from `as.uri` by RFC 8414 section 3.1: insert `/.well-known/lws-configuration` between the authority and the path. `https://as.example` gives `https://as.example/.well-known/lws-configuration`; `https://as.example/tenant` gives `https://as.example/.well-known/lws-configuration/tenant` (README.md, "Open questions"). |
| `as.issuer`, `as.tokenEndpoint`, `as.jwksUri` | Derived: members `issuer`, `token_endpoint`, `jwks_uri` of the JSON document at `as.metadataUrl`, fetched anonymously with `Accept: application/json`. |
| `service.<Type>` | Derived: the `serviceEndpoint`, resolved against `${storage}`, of the first entry of the storage description's `service` array whose `type` has the value `<Type>`. The description is fetched as alice with `Accept: application/lws+cid`. |
| `identity.<name>.webid` | The identity's agent IRI (section 5). |
| `credential.<name>` | The subject credential minted for a SubjectCredential identity (section 5.3). |
| `fixtures.baseUrl` | The harness fixture host as the target reaches it; from target configuration, ending in `/`. Defined only when the target declares ReachableFixtures. |
| `self.webid`, `self.kid`, `self.publicJwk` | Inside identity templates only: the identity being minted. |
| *captured* | Bound by a prerequisite entry (section 4.3), or by `capture` in an earlier step of the same test, or earlier in the same step for `jwt`. Names match `^[a-z][A-Za-z0-9]*$`. A capture never rebinds a name. |

Derived variables are computed on first use and cached. `storage`, `as.*` and the
storage description are cached per run; the rest per test.

**Unresolvable variables.** When a variable cannot be resolved, the test's outcome depends on why:

- *inapplicable*:
  - `service.*`, when the storage advertises no such service;
  - `as.*`, when the anonymous probe is not a 401, i.e. the target does not enforce authentication;
  - `identity.*` and `credential.*`, when the identity cannot be produced for this target (section 5);
  - `fixtures.baseUrl`, when the target does not declare it.
- *cantTell*, with the failure named: every other case, such as a missing `lws#storage` link, metadata without `token_endpoint`, or a capture that never happened. The tests that own those behaviours report the failure itself.

## 4. Running a test

### 4.1 The run

Before any test, the engine creates the run root. It POSTs to `${target.baseUrl}` as
alice with `Link: <https://www.w3.org/ns/lws#Container>; rel="type"`, with no body and no
Content-Type. The result is `${run.root}`. The engine MAY also send a `Slug` header, as
an unstandardised hint that makes run roots recognisable. That is operations, not
conformance (D-0040). If creation fails, the run stops with exit code 2; no test was run.

### 4.2 A test

1. **Capabilities.** If the target lacks a capability the test `requires`, or one the
   identities it uses require, the outcome is *inapplicable*, no request is sent, and
   the report names the missing capability.
2. **Test container.** POST to `${run.root}` as alice, in the same way as the run root.
   The Location becomes `${test.container}`. A failure is *cantTell*.
3. **Prerequisites**, if the test declares any (section 4.3).
4. **Steps** run strictly in order, each fully evaluated before the next is sent. The
   first failing expectation ends the test:
   - In a step marked `precondition: true`, the outcome is *inapplicable*, citing the
     step label.
   - In any other step, the outcome is *failed*, citing the step, the expectation, and
     the expected and actual values. This holds even when the same response also leaves a
     capture unmade: a refused create has no Location, and the failed expectation is the
     finding (D-0049).
5. If every step passes, the outcome is *passed*.
6. **Cleanup** runs whatever the outcome (section 10).

**Short form.** A test that has `request` and `response` directly on it, instead of
`steps`, is a test of exactly one step, labelled with the test's `label`. Its identity is
the test's `as`, else alice. There is no other difference.

Tests are independent and MAY run in parallel. Within a test nothing runs in parallel
except the fetches of one `connegEquivalent`. Every request has a 30 s timeout, unless
the target configuration sets another. Redirects are never followed: a 3xx is the
response under test. Nothing is retried.

### 4.3 Prerequisites

`prereqs.hierarchy` declares the resources a test needs before its first step, and the
access to grant on them. The engine realises them, so the test's steps are only the
exchanges it examines. Entries are processed in order, always as alice:

1. **Parent.** The parent is `${<in>}` when `in` is given, else `${test.container}`.
2. **Absent entry.** With `absent: true`, nothing is created: the variable is bound to
   the parent's URI followed by `touchstone-absent-` and a fresh `${uuid}`, plus a
   trailing `/` for a container. Nothing can exist there, since the parent is new.
3. **Create.** POST to the parent:
   - `container`: with `Link: <https://www.w3.org/ns/lws#Container>; rel="type"`, no
     body and no Content-Type;
   - `dataResource`: with `Content-Type` from `contentType`, and the body from `body`,
     `bodyURL` or `bodyJSON` as in section 6.

   The response must be 201 with a `Location`, which, resolved against the request URL,
   is bound to the variable that `container` or `dataResource` names. Anything else
   ends the test as *cantTell*, naming the entry. Creation is what other tests examine;
   here it is setup, and a setup failure is not a finding.
4. **Grant.** For `authorization`, the engine groups the actions by assignee, in the
   order read, modify, create, delete. For each assignee it POSTs to
   `${service.AccessGrantService}` with Content-Type `application/lws+json`:

   ```json
   {"@context": ["https://www.w3.org/ns/lws/v1"], "type": ["AccessGrant"], "storage": "${storage}",
    "access": [{"type": ["AccessPolicy"], "action": ["<actions>"], "assignee": "<assignee>",
                "target": {"type": "StorageResource", "value": ["<the entry's URI>"]}}]}
   ```

   The assignee is `http://xmlns.com/foaf/0.1/Agent` for `anonymous`, and
   `${identity.<name>.webid}` for any other identity. A 201 with a `Location` registers
   the grant for cleanup (section 10).

   When the storage advertises no access grant service, the engine asks the target's
   provisioning adapter to grant the same access out of band, as lws-test-suite leaves
   to its harness. When neither can, or the webid cannot be produced, or the service
   answers anything but 201, the test is *inapplicable*, naming the entry. Public access
   is a MAY (WD section 11.3.3), and whom to grant access to is the target's policy.

Prerequisite resources live inside `${test.container}` and are deleted with it. alice
creates them, so she needs no grant. The actions are the draft's four (WD section
11.3.2); there is no `write`, `append` or `control`.

## 5. Identities and credentials

The registry is `lws10/identities.yamlld`. A step's identity is its own `as`, else the
test's `as`, else `alice`. Prerequisites are always created, and access granted, as alice
(section 4.3).

### 5.1 NoCredential

`anonymous` sends no Authorization header.

### 5.2 StorageAccessToken

Requests made as the identity carry `Authorization: Bearer <access token>`. Where the
token comes from depends on the target configuration:

1. **HarnessIssuedTokens.** The harness is the storage's authorization server, as in the
   touchstone reference scenario. It mints an RFC 9068 JWT:
   - header: `typ at+jwt`, alg `RS256` or `ES256`, `kid` of its current key;
   - claims: `iss ${as.issuer}`, `aud [${as.realm}]`, `sub` the identity's webid,
     `client_id` the harness client, `iat now`, `exp now+300`, and a fresh `jti`.
2. **Configured subject credential.** Otherwise, if the target configuration gives the
   identity a subject credential (for example a did:key private JWK), the engine exchanges
   it at `${as.tokenEndpoint}` exactly as the token-exchange tests do.
3. **Static token.** Otherwise `token.<name>` from the target's properties, or the
   environment variable `TOUCHSTONE_TOKEN_<NAME>` (upper case, `-` becomes `_`).
4. **Open target.** On a target that does not enforce authentication, alice and bob
   send nothing.

`identity.alice.webid` and `identity.bob.webid` come from the target configuration
(`webid.<name>` property or `TOUCHSTONE_WEBID_<NAME>`).

A **fault identity** starts from its basis identity's token and applies its `fault`
exactly as `vocab.yamlld` defines it.
- **Derivable from a real token:** SignatureCorrupted, AlgNone and UnknownKeyId need no
  signing key.
- **Need the AS signing key:** every other fault needs HarnessIssuedTokens.
- **Expired** has a fallback: without that capability, the engine holds a real token
  until its `exp` plus 60 s has passed, provided that is at most 600 s away.

An identity that cannot be produced makes the test *inapplicable*.

### 5.3 SubjectCredential

`${credential.<name>}` is minted when first used in a test, with its audience taken from
`${as.issuer}`. It is the exact string presented as `subject_token`.

- **did:key.** A P-256 key pair per run, unless the target pins one (for an
  authorization server that only issues tokens to pre-registered agents). The
  identifier is `did:key:z` + base58btc(varint 0x1200 ‖ compressed point). The
  credential is a compact JWS built from the identity's `credentialHeader` and
  `credentialClaims` templates, signed ES256.
- **cid.** A P-256 key pair per run.
  - `self.kid` is a fresh short identifier, and `self.publicJwk` is the public JWK,
    carrying that kid and `alg ES256`.
  - The harness serves the rendered `identityDocument` at `${identity.cid.webid}` as
    `application/ld+json`.
  - The JWS header `kid` is `self.kid`, and the document's verification method id is
    `${self.webid}#${self.kid}`.
- **oidc.** The harness runs an OpenID Provider at `${fixtures.baseUrl}op`.
  - It serves OpenID Connect Discovery at `…/op/.well-known/openid-configuration` and a
    JWKS at the `jwks_uri` that document names.
  - It serves the subject's `identityDocument` at `${identity.oidc.webid}`.
  - The ID Token is built from the templates and signed ES256 by the provider's key.
- **saml.** The harness IdP issues a `saml:Assertion` from the `samlAssertion` fields.
  Instants are xsd:dateTime in UTC. It is signed with enveloped XML-DSig using the
  identity's algorithm, and the serialized Assertion is base64url-encoded.

A **fault identity** mints its basis credential with its `fault` applied, re-signing
where the fault says "validly signed". UntrustedIssuer is minted by a second provider at
`${fixtures.baseUrl}rogue-op`, which serves its own discovery and JWKS but is named by no
identity document.

## 6. Building a request

1. **URL.** Expand the `url` template. A relative reference resolves against
   `${target.baseUrl}`.
2. **Headers.** Send only these:
   - `Accept` from `accept`, and `Content-Type` from `contentType`;
   - `If-Match` from `ifMatch`, where the value `current` means the engine first HEADs
     the URL as the same identity and sends the ETag it returns, or no If-Match if there
     is none;
   - `Link` from `linkHeaders`, with each entry serialized as
     `<href>; rel="rel"[; type="mediaType"]`, joined by `, ` into one field;
   - each `otherHeaders` entry in order;
   - `Authorization` per the identity (section 5), which is a definition error if an
     `otherHeaders` entry also sets it and the identity is not anonymous;
   - plus `Host`, `Content-Length` and `User-Agent: touchstone/<version>`.
3. **Body.** At most one of these:
   - `body`: the expanded template, as UTF-8.
   - `bodyURL`: the fixture file's bytes, unchanged.
   - `bodyJSON`: the expanded value, serialized compactly as UTF-8 JSON; Content-Type
     defaults to `application/json`.
   - `bodyForm`: the expanded members, form-urlencoded in document order; Content-Type
     defaults to `application/x-www-form-urlencoded`.

## 7. Evaluating a response

Expectations are checked in this order; a capture is bound as soon as its expectation
passes:

1. **`statusCode`.** Passes if the status equals any listed integer, or falls in any
   listed class (`"4xx"` means 400–499).
2. **`contentType`.** Compares the response Content-Type's essence (type/subtype, lower
   case, parameters dropped) with the expected value.
3. **`location`.** `Location` must be present. Its value, resolved against the request
   URL, is captured. With `cleanup: true` it is also registered for deletion (section 10).
4. **`linkHeaders`.** Parse every `Link` field line per RFC 8288 section 3:
   comma-separated link-values, quoted parameters, space-separated relation lists.
   Resolve targets against the request URL, and ignore values whose `anchor` does not
   resolve to the request URL. For each expectation, a *matching link* is one that:
   - has `rel` among its relation types (registered names compared case-insensitively,
     URIs exactly); and
   - equals `href` after resolution, if `href` is given; and
   - has a `type` parameter equal to `mediaType` (case-insensitive), if `mediaType` is
     given.

   With `absent: true` there must be no matching link; otherwise there must be at least
   one. `capture` binds the first match's resolved target.
5. **`otherHeaders`.** The header name compares case-insensitively. The field lines are
   the header's values in order; the combined value is those lines joined by `, `.
   - `headerValue`: some line or the combined value equals it exactly, after trimming
     outer whitespace.
   - `present: true`: the header is present; `present: false`: it is absent.
   - `differsFrom`: the header is present and no line equals the value.
   - `matches`: the pattern finds a match in some line or in the combined value.
   - `capture`: binds the combined value.
6. **`authenticationChallenge`.** Parse every `WWW-Authenticate` line into challenges
   per RFC 9110 section 11.6.1. auth-params may be tokens or quoted strings (unquote the
   latter), and their order is irrelevant. The expectation passes if some challenge has
   the scheme `wwwAuthenticate` names (case-insensitive) and satisfies every `params`
   entry. `asUri` and `realm` are shorthand for `params` entries naming `as_uri` and
   `realm`: a string is the exact value (`paramValue`), and an object carries `present`,
   `matches` or `capture`. Each `params` entry is checked as follows:
   - the name matches case-insensitively;
   - `present: true` means the parameter is there;
   - `paramValue` means its value equals the expanded template;
   - `matches` means the pattern finds a match in its value.

   Captures come from that challenge.
7. **Body.**
   - `bodyEmpty`: zero bytes of content were received.
   - `bodyMatches`: the body, decoded as UTF-8, contains a match.
   - `bodyURL`: the body's bytes equal the fixture's bytes exactly.
8. **`json`.** Parse the body as JSON; if it does not parse, every `json` expectation
   fails. Each expectation selects a value with its RFC 6901 pointer, and its operators
   are then checked in the order they appear:
   - If the pointer selects nothing, the expectation fails. With `optional: true` it
     passes vacuously instead. `exists: false` passes precisely when nothing is
     selected.
   - `equals`: deep JSON equality with the expanded value; numbers compare numerically,
     objects ignore member order.
   - `equalsIri`: the selected string, resolved against the request URL, equals the
     expanded value.
   - `hasValue`: the selected value equals the expected value, or is an array
     containing an element equal to it.
   - `matches`: the pattern finds a match in the string. A value that is not a string is
     matched as its compact JSON text.
   - `count`: the array's length, or the object's number of members.
   - `jsonType`: one of string, number, boolean, null, array, object.
   - `some`, `every`, `none`: apply the nested expectations to each array element, with
     pointers relative to the element; the selected value must be an array. `some`
     needs one element satisfying all nested expectations; `every` needs all elements to;
     `none` needs no element to.
   - `capture`: binds the selected value (a string as-is, otherwise its compact JSON).
9. **`jwt`.** Expand `token` and decode the compact JWS header and payload; a
   malformed token fails.
   - With `jwks`: fetch the JWKS anonymously and select the key named by the header's
     `kid` (or the only key, if there is one and no kid). Verify the signature. A
     header `alg` of `none` always fails.
   - Then check the `header` and `claims` expectations as in item 8.
10. **`connegEquivalent`.** Fetch the step's URL once per listed media type, in order,
    as the same identity and with that type as `Accept`.
    - Each response must be 200, with a Content-Type whose essence equals the requested
      type.
    - All bodies must be byte-identical. When they are not, the report says whether
      their JSON-LD graphs are still isomorphic, since "re-serialized" and "different
      content" are different defects (D-0044).

## 8. Matching rules

- **Regular expressions** use the dialect common to java.util.regex, Python `re` and
  PCRE2.
  - Allowed constructs:
    - literals, `.`, character classes (including `\d`, `\s`, `\w` and their negations);
    - anchors `^` and `$`, and `\b`;
    - groups `(...)` and `(?:...)`, and alternation;
    - the greedy quantifiers `* + ? {m,n}`;
    - lookahead `(?=...)` and `(?!...)`;
    - a leading `(?i)` for case-insensitive matching.
  - Not allowed: lookbehind, backreferences, named groups, lazy or possessive
    quantifiers, and other inline flags. An ECMAScript engine translates the leading
    `(?i)` into the `i` flag.
  - Matching is an unanchored search.
- **IRIs** are resolved by RFC 3986 section 5 and compared as strings after resolution
  (no case or percent-encoding normalization).
- **Media types** compare by essence: type/subtype in lower case, parameters ignored.

## 9. Outcomes, verdict and EARL

| Outcome | When | EARL |
|---|---|---|
| passed | every step passed | `earl:passed` |
| failed | a non-precondition expectation failed | `earl:failed` |
| inapplicable | a capability, identity, service or precondition is absent | `earl:inapplicable` |
| cantTell | the engine could not decide: transport error, timeout, setup failure, or an unresolvable variable that is not an optional feature | `earl:cantTell` |
| untested | not run (deselected) | `earl:untested` |

- **Verdict.** The target conforms when no MUST test *failed* and no MUST test ended
  *cantTell*. Failures of SHOULD and MAY tests are advisory. The report lists
  inapplicable MUST tests separately, with their reasons: they are coverage the run did
  not have, not evidence either way.
- **EARL.** Each assertion's `earl:test` is the test's IRI, and the test case carries
  `touchstone:verifies` for each `requirements` IRI (as EarlReport does today).
- **Exit codes** are as in docs/distribution.md: 0 conformant, 1 non-conformant, 2
  harness or definition error.

## 10. Cleanup, redaction and safety

- **At test end.** DELETE every location registered with `cleanup: true`, and every grant
  a prerequisite created, in reverse order, as the identity that created it. Then DELETE `${test.container}` with
  `Depth: infinity` and `If-Match: current`.
- **At run end.** Do the same for `${run.root}`. If the server refuses recursive delete
  (it is a MAY), delete bottom-up by listing. Cleanup failures are logged with the URL
  left behind and never change an outcome (D-0027).
- **Redaction.** Traces keep method, URL, status and headers. Strip `Authorization`,
  `Cookie`, `Set-Cookie`, `DPoP` and `Proxy-Authorization` values, and every
  `subject_token`, `access_token`, `refresh_token` and `id_token` in bodies, forms and
  query strings, before anything leaves the engine. Keep `WWW-Authenticate`, which is
  the evidence a 401 test exists to capture (D-0044).
- **Untrusted input.** Target responses are untrusted input. JSON-LD contexts named in
  responses are resolved only from the bundled set, never fetched (D-0026). Only
  pre-registered targets are addressed (DESIGN.md 7.1). The fixture host serves only the
  documents this section's identities define.
