# LWS test definitions (YAML-LD)

**Status: Proposed, format version 0.2.0 — not frozen.** The context, vocabulary, schema
and `EXECUTION.md` are a proposal awaiting review (see "Review gate"). Every test is
`status: Proposed`.

0.2.0 merges this format with the best of lws-test-suite's own. A test that is one
request and one response is written as exactly that, and the state a test needs is
declared in `prereqs` instead of scripted. `COMPARISON.md` sets out, with evidence from
its files, why the merged format is the stronger design.

These are Touchstone's test definitions for the Linked Web Storage Protocol 1.0 and its
four authentication suites, written in [YAML-LD](https://www.w3.org/TR/yaml-ld/). They
exist for three reasons:

1. **Mirror lws-test-suite.** The LWS test group's suite (`lws-contrib/lws-test-suite`) is
   standardizing on JSON-LD test manifests and is meant to become the authoritative suite.
   Every one of its 27 tests has a counterpart here. Where one of its tests contradicts
   the current draft, the counterpart is corrected rather than copied (COVERAGE.md,
   table 1).
2. **Go further than it.** There are 101 definitions against its 27, covering the
   authorization server, access grants, conditional requests, linksets, byte ranges, the
   access-token negative matrix, and the did:key, OpenID Connect, CID and SAML suites.
3. **Flow back as JSON-LD.** YAML-LD is JSON-LD in YAML syntax, so exporting these
   definitions for contribution is a mechanical conversion (see "Export"). Nothing is
   contributed yet; that is a later, deliberate step.

The engine that runs them is to be generated from these definitions and `EXECUTION.md`.
Until it exists, `touchstone run` keeps executing `manifests/` unchanged.

## Layout

```
definitions/
  README.md                 this file
  EXECUTION.md              the contract an engine must implement (variables, matching, outcomes)
  COMPARISON.md             this format against lws-test-suite's: what was merged, and why it is stronger
  COVERAGE.md               generated: lws-test-suite and manifests/ mapping, every test by module
  schema/
    definitions.schema.json JSON Schema (2020-12) for manifests and the identity registry
  lws10/                    mirrors lws-test-suite's lws10/ tree, so export is file-for-file
    context.jsonld          the test vocabulary's JSON-LD context (proposed for lws-test-suite)
    touchstone.jsonld       touchstone-only terms (catalog links, traceability); dropped on export
    vocab.yamlld            RDFS definitions of every lwst: term
    identities.yamlld       the abstract identities tests name, and how their credentials are made
    manifest.yamlld         root manifest; includes the modules below
    core/                   discovery, containers, data_resources, conditional_requests, linksets,
                            storage_authorization, authorization_server, access_grants, notifications
    auth/                   did_key, oidc, cid, saml — one manifest per authentication suite
    fixtures/               request and expected-body fixtures (byte-exact; .gitattributes keeps LF)
```

Directory and file names use underscores, as lws-test-suite does (its 8fa9cc4: some
frameworks reject hyphens in embedded resource names). Test names keep lws-test-suite's
spelling for mirrored tests and use kebab-case for new ones.

## A test, briefly

A test that is one exchange is written as one: `request` and `response` directly on the
test, which is lws-test-suite's shape.

```yaml
  - id: "#getContainer-private-unauthorized"   # IRI fragment; always "#" + name
    type: NegativeTest                          # the point is a refusal
    name: getContainer-private-unauthorized
    label: An anonymous request for a protected container is refused with 401 and a conforming challenge
    status: Proposed
    level: MUST                 # one level per test; SHOULD/MAY checks get their own test
    source:                     # dated spec snapshot + anchor (or an RFC section)
      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#authorization-server-discovery
    traits: [Get, Container, Private, Authn]
    requires: [Authentication]  # on an open target the test is inapplicable, not failed
    as: anonymous               # who sends the request; never a credential
    request:
      method: GET
      url: "${test.container}"
      accept: application/lws+json
    response:
      statusCode: 401
      authenticationChallenge:  # parsed per RFC 9110: any order, token or quoted values
        wwwAuthenticate: Bearer
        asUri: {matches: '^https?://'}
        realm: {matches: '^https?://'}
```

What a test needs but does not examine is declared, and the engine creates it (EXECUTION.md
section 4.3). Ordered `steps` are for flows, such as a delete followed by checks of its
effect:

```yaml
  - id: "#deleteDataResource"
    ...
    prereqs:
      hierarchy:
        - dataResource: created        # the server picks the URI; it becomes ${created}
          contentType: text/plain
          body: short-lived resource
          # authorization: {read: [anonymous]} would also make it public, by access grant
    steps:
      - label: delete it
        request: {method: DELETE, url: "${created}", ifMatch: current}
        response: {statusCode: 204}
      - label: it is gone
        request: {method: GET, url: "${created}"}
        response: {statusCode: [404, 410]}
```

The term names are lws-test-suite's wherever their meaning holds: `request`, `response`,
`method`, `url`, `contentType`, `linkHeaders`/`rel`/`href`/`mediaType`,
`otherHeaders`/`headerName`/`headerValue`, `body`/`bodyURL`, `statusCode`,
`authenticationChallenge`/`wwwAuthenticate`/`asUri`/`realm`, `prereqs`/`hierarchy`/
`authorization`, `traits`, `status`, `source`, `name`, `entries` and `include`. Its two
shapes are kept too: the one-exchange test and declared prerequisites. The additions are
what its reviewers asked for (w3c/lws-protocol PR #145) and what its tests needed but
could not say:
- ordered `steps` with `capture`d values;
- a template language;
- explicit matching (`json` pointers with `some`/`every`/`none`, parsed challenges,
  `connegEquivalent`, `jwt`);
- one `level` per test;
- `precondition` steps and `requires` capabilities, so optional features are inapplicable
  rather than failed;
- abstract identities in place of credentials.

## Authoring rules

1. **YAML-LD as the W3C draft defines it.** UTF-8, one YAML document per file, extension
   `.yamlld`. No anchors, aliases, tags or tabs.
2. **The portable subset.** Every file must read identically under the YAML 1.2 Core
   Schema (which YAML-LD requires) and under YAML 1.1 (which many libraries still
   implement). Quote:
   - templates: `"${test.container}"`;
   - `"@context"`, and ids that start with `#`;
   - anything that would otherwise become a number, boolean, null or date: `"0123456789"`,
     `"47"`, `yes`, `on`, `2026-09-21`.

   Put regular expressions in single quotes.
3. **Names.** `id` is `#` + `name`, and `name` is unique across the whole suite.
4. **Levels.** Exactly one `level` per test. A SHOULD or MAY check never sits inside a
   MUST test: a missing `Vary` header must not make a server non-conformant.
5. **Sources.** Every `source` is a dated snapshot URL with an anchor that exists in that
   snapshot, or an RFC section.
6. **Discover, never assume.** Created URIs come from `Location`, linksets from
   `rel="linkset"`, the storage from `rel="…lws#storage"`, and the authorization server
   from the 401 challenge. The draft defines no `Slug`, no `.meta` convention and no
   token endpoint path.
7. **Isolation.** A test creates what it needs inside `${test.container}` and depends on
   no other test.
8. **Conditional writes.** A write that could meet a server requiring conditional
   requests sends `ifMatch: current`, because the draft lets a server demand one.
9. **Negative tests prove the refusal.** After the 4xx, the test checks the refused
   request had no effect.
10. **Optional features never fail a server.** A feature the draft makes optional is
    gated with a `precondition` step, a `${service.*}` variable, or `requires`.
11. **Catalog citations.** `requirements` cites a catalog IRI only when its clause still
    holds in the snapshot the test's `source` names. Otherwise, add a `note` explaining
    why it is not cited.
12. **One exchange, one request.** A test that sends one request uses `request` and
    `response` directly. `steps` are for flows.
13. **Declare what the test does not examine.** A resource a test needs goes in
    `prereqs`, and the access it needs goes in `authorization`. Create it in a step only
    when creation is the point, in which case the test's `traits` include `Post`. A failed
    step is a finding; a failed prerequisite is not.
14. **No example hosts.** `storage.example` and its kind can never match a live server.
    Executable values use templates and discovered URLs, and the lint rejects the rest.

## Validating

The definitions are data, so the checks are data checks. CI should run:
1. YAML 1.2 Core Schema parse, and equivalence with a YAML 1.1 reading (rule 2 above).
2. JSON Schema validation against `schema/definitions.schema.json` in strict mode.
3. JSON-LD `toRDF` of every document with an offline loader, in safe mode, where a
   dropped term is an error.
4. The lint of EXECUTION.md section 2.5:
   - names are unique;
   - each test has steps or the short form, not both;
   - variables are bound, by a prerequisite or an earlier capture;
   - identities exist, and grants name agents other than alice;
   - no executable value names an example host;
   - catalog IRIs exist and none has drifted;
   - `source` anchors resolve;
   - fixtures exist.
5. `vocab.yamlld` defines exactly the `lwst:` terms of `context.jsonld`.
6. `COVERAGE.md` regenerates without a diff.

For this version all six passed:
- 16 documents and 5,933 triples;
- 101 tests with no lint errors, and all 20 schema negative controls rejected;
- all 27 lws-test-suite tests accounted for;
- 32 of 33 `manifests/` superseded, with the other one retired.

The move from 0.1.0 was mechanical, and checked:
- 30 tests took the short form, 25 declare prerequisites, and 13 use the challenge
  shorthand.
- Every test, desugared back into 0.1.0 steps, equals its 0.1.0 text. The exception is
  `getContainer-public-read`, rewritten by hand to declare its public container as
  lws-test-suite's `getContainer` does.
- The JSON-LD export trial still produces identical graphs for all 16 documents.

Implementing these checks as project code is part of the engine work.

## Export to lws-test-suite (JSON-LD)

When contributing, the conversion is mechanical and loses no meaning:

1. Parse each `lws10/**/*.yamlld` with a YAML 1.2 Core Schema parser.
2. Remove `touchstone.jsonld` from every `@context`. Remove the keys it defines:
   `requirements`, `mirrors`, `supersedes` and `note`.
3. Rewrite each `include` value from `.yamlld` to `.jsonld`.
4. Write the result as UTF-8, LF, 2-space indented JSON, at the same relative path with
   the extension `.jsonld`. Copy `context.jsonld`, `fixtures/`, and
   `vocab.yamlld`/`identities.yamlld` (converted the same way).
5. Check the result: JSON-LD `toRDF` with only `context.jsonld` must succeed in safe
   mode, and the graph must equal the YAML-LD graph minus the `touchstone:` triples.

Test names are the stable key between the two syntaxes: an IRI differs only in the file
extension, and a result maps to `<manifest path without extension>#<name>`.

Contributing is also a vocabulary proposal. Adopting these files changes
lws-test-suite's context in ways its test group must agree to:
- the real `mf:` namespace;
- `rdft:approval` status values;
- `dcterms:source`;
- `steps`;
- templates in place of fixed hosts;
- no `@vocab` fallback;
- `location` as a capture rather than an IRI resolved against the manifest;
- prerequisites that name resources instead of placing them, with the draft's four
  access actions instead of `write`, `append` and `control`;
- challenge values as expectations rather than fixed IRIs.

`COMPARISON.md` gives the reason for each.

## Relationship to the rest of Touchstone

- **`manifests/`** (schema 1-1-0) is what `touchstone run` executes today. Nothing here
  changes it. Once the YAML-LD engine exists, `manifests/` is superseded. COVERAGE.md
  table 2 maps each manifest to its successor; that decision is recorded as proposed in
  DECISIONS.md.
- **`catalog/`** is baselined on the 21 August 2026 core draft. The 21 September draft
  changed 14 catalogued clauses; `tools/extractor/check_drift.py` reports them. Four of
  those changes affect `manifests/`:
  - the 428-on-unconditional-PUT MUST is gone;
  - conditional-request support dropped from MUST to SHOULD;
  - the MUST that a container's ETag change after a member is deleted is gone;
  - so is the SHOULD for a new ETag after a PUT.

  The definitions already follow the new text, and cite none of the seven affected
  catalog entries. Re-baselining the catalog rewrites Approved entries, so it waits for
  review, as D-0037 did.

## Open questions

These affect how tests are written, and are worth raising with the WG:

1. **Storage link on 401 responses.** Section 6.1.2 requires it on all GET/HEAD
   responses, but the section 9.2 notes make it a SHOULD on 401s. The definitions test
   it as SHOULD.
2. **`/.well-known/lws-configuration` for an authorization server whose issuer has a
   path.** RFC 8414 inserts the well-known segment before the path; the draft says "a
   URL with the path /.well-known/lws-configuration". The definitions follow RFC 8414,
   as the draft cites it.
3. **Conditional requests.** The draft says SHOULD, while RFC 9110 makes evaluating a
   received precondition a MUST for any origin server. The definitions test the
   positive 304 as SHOULD, and "never a 304 for a non-matching validator" and "a stale
   If-Match never writes" as MUST.
4. **Merge Patch on linksets.** A patch replaces the `linkset` array wholesale, and
   servers MAY restrict links. How that combines with the MUST to support PATCH is
   unclear, so the happy path is tested as SHOULD.
5. **Grant scope.** Does an access grant on a container reach its members? The
   public-read tests target each resource directly.
6. **CID suite `kid`.** Is it a fragment or a full verification-method IRI? The
   identities use the fragment, as the draft's example does.
7. **The LWS JSON-LD context.** `https://www.w3.org/ns/lws/v1` still returns 404, and no
   published context defines `format`, `storage` or `StorageRoot`. Graph and SHACL
   assertions are therefore left out of the vocabulary until one exists; JSON pointers
   read responses as sent.
8. **The `lwst:` namespace.** `https://www.w3.org/ns/lws-tests/v1#` (lws-test-suite's
   choice) is not published and needs W3C allocation. The context is the only place to
   change it.
9. **Token issuance policy.** The did:key tests assume an authorization server issues
   tokens to any validly authenticated agent for a storage it knows, and leaves access
   to the storage's policy. A server that only serves pre-registered agents needs the
   target to pin the did:key (EXECUTION.md 5.3).

## Not yet defined

- **Notification subscriptions and delivery.** The notification suites that define
  subscription types are unpublished. Delivery needs a sink the target can reach, and
  the three authorization MUSTs deserve a negative matrix (D-0041). Only discovery is
  defined.
- **Pagination.** The threshold is server-chosen, so a deterministic test needs the
  target to declare a page size.
- **Access-profile constraints** (client, format, type, purpose, dateTime) and access
  notifications (section 11.6).
- **Optional behaviours:** RFC 9457 problem details (SHOULD), `Prefer: set-linkset`
  (optional), `lws#PreferLinkRelations` (MAY).
- **Key rotation mid-session.** It needs the harness to be the authorization server, so
  it stays a fixture-level test in `harness-fixtures` for now.

## Review gate

Freezing format version 0.2.0 is a review decision, the analogue of Gate 2 (D-0013). It
covers `context.jsonld`, `vocab.yamlld`, `schema/definitions.schema.json` and
`EXECUTION.md`. Engine generation should start from the frozen format, and any later
change bumps the schema `$id` with a DECISIONS.md entry.
