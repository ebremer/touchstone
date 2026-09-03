# DECISIONS.md — deviations and clarifications vs DESIGN.md

Per the brief: decisions in DESIGN.md are settled; anything that changed on contact
with reality is recorded here, dated, with rationale. When the spec and the brief
disagree, the spec wins.

Gate status:
- **Gate 1: CLOSED — Erich approved the 15 seed requirements on 2026-07-16; mass extraction authorized (D-0013).**
- **Gate 2: CLOSED — manifest JSON Schema v1 frozen on 2026-07-16 (D-0013).**

## 2026-07-16

### D-0001 — Maven/package coordinates: `com.ebremer.touchstone`
The brief is silent on coordinates; §10 flags the Touchstone name collision (HL7/AEGIS)
for *public* publication only. Chosen: groupId + package root `com.ebremer.touchstone`
(owner's domain), artifactIds = module directory names, version `0.1.0-SNAPSHOT`.
The display name stays in one constant (`Touchstone.NAME`) per §3. Catalog/vocab IRIs
stay under `https://example.org/touchstone/` until a permanent namespace is chosen —
they are relocatable by design (one `@prefix` per file).

### D-0002 — Spec baseline is the 22 June 2026 Working Draft (not the March FPWD)
DESIGN.md §2 cites the FPWD (March 2026). The live TR is a newer WD:
**"Linked Web Storage Protocol 1.0", W3C Working Draft 22 June 2026**,
this-version <https://www.w3.org/TR/2026/WD-lws10-core-20260622/>.
The catalog derives from this dated snapshot (archived under `catalog/sources/`);
section anchors are recorded against <https://www.w3.org/TR/lws10-core/>. The
undated editor's draft at w3c.github.io is a ReSpec *source* page (client-side
rendered), unusable for direct extraction; it is reserved for drift checks.
Extraction stats from the snapshot: **163 rfc2119-marked normative blocks**
(operations 47, access-requests-and-grants 36, authorization 25, lws-media-type 14,
discovery 12, logical-resource-organization 9, authentication 8, containers 7, other 5).

### D-0003 — Dependency pins verified 2026-07-16 against repo1.maven.org
search.maven.org's solrsearch API served stale data (it claimed Spring AI 1.1.x did
not exist, JUnit latest = a May-2025 milestone). Pins were therefore taken from
authoritative `maven-metadata.xml` on repo1.maven.org (and build.shibboleth.net for
OpenSAML):

| Artifact | Pin | Note |
|---|---|---|
| Apache Jena (jena-bom) | 6.1.0 | current major; 5.x ended at 5.6.0 |
| Titanium JSON-LD | 1.7.0 | 2.0 still milestones |
| Jackson (jackson-bom) | 2.22.1 | |
| JUnit (junit-bom) | 6.1.2 | see D-0005 |
| AssertJ | 3.27.7 | 4.0 still milestones |
| Awaitility | 4.3.0 | |
| picocli | 4.7.7 | |
| Jetty (jetty-bom) | 12.1.11 | brief's "Jetty 12"; 12.1 is the stable line |
| Nimbus JOSE+JWT | 10.9.1 | |
| Nimbus OAuth2/OIDC SDK | 11.38.1 | |
| BouncyCastle (bcprov-jdk18on) | 1.85 | |
| SLF4J | 2.0.18 | 2.1 still alpha |
| Logback | 1.5.38 | |
| FreeMarker | 2.3.34 | |
| Spring Boot | 4.0.7 | see D-0004 |
| Spring AI | 2.0.0 | see D-0004 |
| maven-compiler / surefire / shade / enforcer | 3.15.0 / 3.5.6 / 3.6.2 / 3.6.3 | latest stable (4.x/3.6 lines are pre-release) |
| Maven wrapper | 3.3.4, distribution Maven 3.9.16 | matches local install |

### D-0004 — Spring stack: Spring AI 2.0.0 GA + Spring Boot 4.0.7 (brief said "latest 1.1.x GA")
Brief §4 pinned "latest Spring AI 1.1.x GA" because "Spring AI 2.0 is in milestones
… schedule the 2.0 bump, don't build on milestones." That premise is obsolete as of
today: **Spring AI 2.0.0 is GA** (1.1.x line ended at 1.1.8), the current reference
docs cover 2.0, and the Boot 3.x generation it would tie us to is at the end of its
OSS window. harness-mcp is a Phase 5 deliverable — no MCP code exists yet — so
building greenfield on the GA 2.0 line *is* the brief's scheduled bump, minus a
pointless migration. Verified against the 2.0 docs: starter
`spring-ai-starter-mcp-server-webmvc` (servlet stack, required for the Jetty swap),
`spring.ai.mcp.server.protocol=STREAMABLE` / `type=SYNC`, `@McpTool`/`@McpToolParam`
annotations — i.e. §6's configuration baseline remains valid as written.
**Flagged at the Gate-1 review for veto; reverting to 1.1.8 + Boot 3.5.x is a
two-property change in the root POM today.**

### D-0005 — JUnit Jupiter 6.1.2 (brief said "JUnit 5")
JUnit 6 is the same Jupiter programming model the brief's §5 design relies on
(`@TestFactory`/`DynamicTest`, `org.junit.jupiter` packages, JUnit XML), with the
5.13.x line now feature-frozen. Greenfield code sees no difference except staying
on the maintained line. **Flagged at the Gate-1 review for veto.**

### D-0006 — The LWS WG has an active test-suite effort (align, don't fork)
§10 asked to check — it exists:
- w3c/lws-protocol **#102 "LWS Test Suite"** (2026-03-17): NLnet-grant-funded effort,
  ODI involvement, plan is to *update the Karate-based Solid CTH*.
- w3c/lws-protocol **PR #145** (2026-04-27): strawman `lws10-test-suite/` with a
  JSON-LD manifest — `ValidationTest`, `traits`, `status`, `source` (spec section
  anchor), `prereqs`, declarative `request`/`response` pairs. Reviewer concerns:
  needs multi-step scenarios; readability.

Alignment taken now: Touchstone manifests reference requirements via spec section
anchors (same idiom as their `source`), EARL stays the report format, and our
declarative request/expect shape is a superset of the strawman (multi-step, graph
isomorphism, SHACL — exactly the gap their reviewers flagged), so a JSON-LD export
in their vocabulary is a mechanical transform (candidate Phase 3 deliverable).
The §2 no-Karate decision stands. **Contribution posture (join #102 vs parallel
effort) is Erich's call — raised at the Gate-1 review.**

### D-0007 — Phase 0 acceptance "green CI on main": local-green, remote pending
No GitHub remote exists yet. The workflow (`.github/workflows/ci.yml`) is committed
and its exact commands run green locally (`./mvnw -B -ntp verify` + CLI smoke test).
Literal green CI on GitHub requires Erich to create the remote and push.

### D-0008 — clauseHash normalization rule
`clauseHash = "sha256-" + lowercase-hex(sha256(utf8(text)))` where `text` is the
clause sentence(s) exactly as stored in `touchstone:clauseText`, normalized as:
Unicode NFC → every whitespace run collapsed to a single space → trimmed.
Implemented in `tools/extractor/` (the drift-check foundation for Phase 1).

### D-0009 — Deferred pins (recorded now, wired when their phase arrives)
- **WireMock**: 4.x is still beta (4.0.0-beta.38); if used at all (optional per §4),
  pin the stable 3.x line at Phase 4.
- **Testcontainers 2.0.5** and **networknt json-schema-validator 3.0.6** are new
  majors relative to the design era — re-verify API shape at first use (Phase 2).
- **OpenSAML 5.2.3** — v5 artifacts are `opensaml-*-api`/`-impl` on
  `build.shibboleth.net/maven/releases` (NOT Maven Central); harness-fixtures needs
  that repository added at Phase 6.
- **GitHub Actions**: checkout@v7, setup-java@v5 (latest majors as of today).

### D-0013 — Gate review outcomes (Erich, 2026-07-16)
All four decisions taken as recommended: **Gate 1 approved** (seed status flips to
Approved; mass extraction proceeds), **Gate 2 frozen** (schema `$id`
`https://example.org/touchstone/schema/manifest/1-0-0` is fixed; changes bump the
version with a decision entry), **version pins D-0004/D-0005/D-0011 all kept**, and
**WG posture: build independently, stay format-aligned** with w3c/lws-protocol#102
(EARL, spec-anchor requirement links, exportable manifests; no outreach for now).
GitHub remote still pending (D-0007).

### D-0022 — Distribution: build-from-source Docker image + composite GitHub Action
The Phase 6 gate ("a third-party repo adds one workflow file and gets a conformance report")
is delivered by a multi-stage `Dockerfile` (build the CLI with the Maven wrapper on
`eclipse-temurin:21-jdk`, ship on `eclipse-temurin:21-jre` with the shaded jar + catalog +
manifests) and a composite action `.github/actions/lws-conformance` that builds the image,
runs the harness against a target URL, uploads the EARL/HTML/JUnit/JSON report, and fails
the job on non-conformance. A consumer copies one workflow file
(`docs/ci/example-conformance-workflow.yml`). Notes:
- The image build uses `-pl harness-cli -am -Dmaven.test.skip=true` — `maven.test.skip`
  (not `skipTests`) so the CLI's fixtures-dependent test sources aren't compiled in the
  cut-down reactor. All four module POMs are copied so the aggregator parses; only
  core+cli sources are.
- **Verified end-to-end locally**: `docker build` then the container running `--module core`
  against a host-side reference server via `host.docker.internal` produced the report
  (12→13 passed). Docker Desktop's Linux engine must be running for the local build.
- The Action generates the target registry (`sut → target-url`); tools still take an id
  only (§7.1). Base images and `actions/*` pinned per §10 (checkout@v7, setup-java@v5,
  upload-artifact@v4, temurin 21).

### D-0023 — did:key + CID suites: real Ed25519 self-issued-credential fixtures + negative matrix
did:key and CID are both **self-signed JWT** credentials (`sub = iss = client_id`, one URI;
`alg ≠ none`; `exp` in the future; signature verified against a key derived from the
identifier). Built for real over BouncyCastle (§4's hand-roll guidance): `DidKey` (Ed25519
keygen + the multibase-`z`/multicodec-`ed25519-pub` did:key encoding and decode),
`SelfIssuedJwt` (hand-built EdDSA compact JWS — avoids needing a JOSE Ed25519/Tink
provider — plus an `alg=none` variant), `SelfIssuedCredentials` (valid + one-fault-each
broken), `IdentityDocumentHost` (serves CID documents the verifier dereferences), and
`SelfIssuedVerifier` (validates per suite; did:key key from the identifier, CID key from
the dereferenced document selected by `kid`). `SelfIssuedNegativeMatrixTest` asserts a
valid credential verifies and each broken variant (alg-none, bad signature, expired,
mismatched claims, wrong audience) is rejected — for both suites, with genuine crypto.
The credential→token-exchange→storage wiring is the §5.3 provisioning seam (already proven
for access tokens in Phase 4); the suite-specific substance is credential validation, which
this tests directly.

### D-0024 — SAML suite: catalog now, OpenSAML fixture deferred as a documented seam
The SAML catalog module is complete (`catalog/lws10-authn-saml.ttl`, 7 clauses). The
fixture is deferred: unlike did:key/CID (JWT, reusing existing machinery), SAML needs
OpenSAML 5 — a large dependency from `build.shibboleth.net` (NOT Maven Central, D-0009) plus
XML-DSig assertion building/validation. Per §2's "harvest, don't over-build" and to avoid a
shallow fake of XML/SAML, it is a recorded seam: add the Shibboleth repository + OpenSAML,
then a `SamlAssertions` fixture mirroring `AccessTokens`/`SelfIssuedCredentials` (valid +
broken: unsigned, wrong audience, bad signature, expired conditions) and a negative-matrix
test. The catalog and the abstract-identity model already accommodate it.

### D-0025 — Harvest: core manifests are the ported corpus; method documented
The twelve original `core/` manifests are LWS-adapted ports of Solid-0.11-descended
scenarios (the corpus is `solid-contrib/specification-tests`). `docs/harvest.md` records
the Karate→manifest mapping and what is deliberately not ported (PUT-creates-intermediate,
WAC/ACP, SPARQL-Update PATCH — Solid-specific or in still-draft LWS modules).
`core/post-to-non-container-405.yaml` is one concrete, attributed port.

### D-0019 — `-parameters` compiler flag is required (Spring AI derives tool arg names by reflection)
Spring AI's `@McpTool` uses reflected method parameter names as the JSON-schema property
names for tool arguments (`@McpToolParam` has no `name()` member). Without javac
`-parameters`, every argument becomes `arg0`, `arg1`, … and clients calling with real
names fail input validation. We don't inherit `spring-boot-starter-parent` (BOM import
only), so the flag is set explicitly in the root POM's `maven-compiler-plugin` config —
applied to all modules (harmless elsewhere; picocli benefits too). Symptom when missing:
`required property 'arg0' not found`.

### D-0020 — MCP progress: get_run polling is the reliable watch; notifications are best-effort
DESIGN §6 wants `start_run` to return a run_id immediately *and* emit progress
notifications. With streamable HTTP, a tool call that returns immediately closes its
response stream, so notifications the async job sends afterward may not route back to a
detached client. Resolution: `start_run` emits an initial progress notification
**synchronously** (while the request stream is live — this one reliably reaches the
client) and the async per-test notifications follow best-effort; the **guaranteed**
progress mechanism is polling `get_run` (which reports completed/total and live counts —
that is literally what the §6 tool is for). The end-to-end test asserts both: get_run
watched to COMPLETE (hard) and a progress notification received with the caller's token.
MCP endpoint is the streamable default `/mcp`.

### D-0021 — Run orchestration + report bundling extracted to harness-core (Harness, Reports)
Per §3 ("if adding the MCP layer requires touching the executor, the layering is wrong"),
the run loop (provision → parallel virtual-thread execution → collect) and the report
bundle writer moved out of the CLI into `core.exec.Harness` and `core.report.Reports`, so
CLI and MCP are thin over identical orchestration. `RunResult` gained `targetBaseUrl` and
`startedAt`; `Requirement` gained `clauseText` (get_requirement / the requirement resource
need the verbatim clause). The MCP target registry stays the file-based `TargetRegistry`
referenced by a configured path (`touchstone.targets`); tools accept only a target *id*,
never a URL — the §7.1 SSRF boundary holds whether the registry is inline `@ConfigurationProperties`
or a path to the same file the CLI uses.

### D-0017 — Phase 4 shape: harness owns the AS; negative matrix proven via the self-test loop
The brief's §4/§5.4 negative matrix (expired / wrong-audience / bad-signature / rotated
key …) is fundamentally about the **access tokens the storage validates** — RFC 9068
JWTs the storage checks against the authorization server's `jwks_uri` (core WD
authorization §, clauses `authz-token-validation-*`, `authz-401-www-authenticate-challenge`,
`authz-jwt-signature-jwks-rotation`, `authz-invalid-token-401-error-param`). So the harness
owns an **OIDC issuer / authorization server fixture** (Nimbus): it publishes discovery +
JWKS over ephemeral Jetty and mints valid *and* deliberately broken tokens. This is why
fixtures are library-level fakes, not Keycloak (§5.4) — a real IdP will not mint corrupted
credentials on demand.
- **RefLwsServer gains three auth modes** (`AuthMode`): `OPEN` (no auth — the Phase 2/3
  core suite still runs against it unchanged), `SECURED` (validates Bearer tokens against
  the AS jwks_uri; 401 + conforming WWW-Authenticate challenge on missing/invalid, 403 on
  a valid non-owner, owner policy = the identity that created the run root), and `BROKEN`
  (auth theater: never challenges, never forbids — the deliberately broken twin).
- **Acceptance is the self-test loop** (§8): the `auth-oidc` manifests assert the *correct*
  secured behavior, so they PASS against `SECURED` and the negative ones FAIL against
  `BROKEN` — exactly "negative tests demonstrably distinguish a compliant reference from a
  deliberately broken stub." Both directions are asserted in
  `OidcNegativeMatrixTest`.
- **CLI auth targets** use the existing `env` provisioning adapter with pre-issued static
  tokens (`token.<identity>` properties) — fine for a third-party SUT the operator has real
  tokens for. Generating *broken* variants requires the harness to hold the AS signing keys
  (i.e. to BE the AS), which is only true for the bundled reference scenario; wiring a
  harness-owned AS that an external SUT already trusts is provisioning-adapter territory
  (§5.3, out of spec scope, a documented seam), not faked here.
- **Coverage vs §5.4 list**: expired, not-yet-valid, wrong-audience, wrong-issuer,
  bad-signature (foreign key), unknown-key (kid not in JWKS), `alg=none`, missing-subject,
  and **key-rotated-mid-session** (the AS retires the signing key; a previously valid token
  stops validating) are all covered. **Replayed proof (DPoP) is deferred**: the current
  OIDC editor's draft defines no sender-constrained/DPoP binding (D-0010), so there is no
  proof to replay yet; the seam stays for when the suite adds one.

### D-0018 — auth-oidc catalog module from the OIDC editor's draft
`catalog/lws10-authn-openid.ttl`: 8 MUST-level clauses hand-curated from the
`lws10-authn-openid` editor's draft (snapshot `catalog/sources/ED-lws10-authn-openid.html`;
an "unofficial proposal" ED with no dated /TR/, per D-0010, so `sourceDraft` points at the
editor's-draft URL). These are the ID-Token-as-subject-token rules (`sub`/`iss`/`azp`/`aud`
claim mapping, `alg≠none`, CID dereferencing, token-type URI). The *storage-side* negative
matrix references the already-catalogued **core** authorization requirements; auth-oidc
manifests cite both modules' IRIs.

### D-0016 — json-schema-validator: 3.0.6, consumed only through its string API
D-0009 flagged the new major for re-verification at first use; the verification had
two rounds. networknt 3.x moved to the Jackson 3 generation (`tools.jackson.*` types
in its node-based API), so the first instinct was to pin the Jackson-2-era 1.5.x line
— but that downgrade broke harness-mcp: **the MCP Java SDK itself requires networknt
3.x** (`NoClassDefFoundError: com.networknt.schema.dialect.Dialects` in
`McpSyncServer`), and harness-mcp inevitably has both the SDK and harness-core on one
classpath. Resolution: pin **3.0.6** everywhere and have the manifest loader use only
the string-based API (`SchemaRegistry.getSchema(InputStream)`,
`Schema.validate(json, InputFormat.JSON)` → `List<Error>`), so validation parses
internally with the library's own Jackson 3 while all Touchstone code stays on
Jackson 2 (`com.fasterxml`) — the two Jackson generations coexist by design
(different package namespaces).

### D-0015 — Phase 2 reference target: built-in `RefLwsServer` fixture (not CSS)
§8 suggested Community Solid Server as a stand-in "until LWS implementations exist —
confirm current best option online." Confirmed 2026-07-16: the WG's Implementations.md
is still an unmerged PR (404 on main) and no public LWS server implementation is
identifiable; CSS remains a Solid Protocol implementation with no LWS support, so it
would fail the WD-specific assertions our manifests make (`items` listings,
`application/lws+json`, `Link rel="up"`, 428 on unconditional PUT). Decision: the
Phase 2 reference target is an **in-memory reference LWS server in harness-fixtures**
(embedded Jetty, WD happy-path semantics, no auth) — deterministic, Docker-free in CI,
and it is the compliant half of the compliant-vs-broken stub pair Phase 4 requires
anyway. CSS/Testcontainers stays on the roadmap for Solid-compat scenario harvesting.
Two fidelity notes: (1) **https://www.w3.org/ns/lws/v1 — the WD's normative JSON-LD
context — is itself a 404 as of today**; the ref server therefore emits an equivalent
inline `@context` so JSON-LD parsing works offline; swap to the published context (with
a cached local document loader) once W3C serves it. Candidate WG feedback item.
(2) The ref server implements the subset our manifests exercise (no linkset resources,
no range requests yet); it grows with the suite.

### D-0014 — Mass-extraction conventions (Phase 1)
- **Granularity:** one Requirement per normative spec block (the draft's own
  paragraph/list-item granularity as extracted); finer splitting happens later only
  where a test needs it.
- **Skipped:** exactly one block — the BCP 14 boilerplate sentence (§2.3), which is
  definitional, not a conformance clause.
- **Level rule:** strongest BCP 14 keyword in the block (MUST-family > SHOULD-family
  > MAY); the exact keywords stay visible in clauseText.
- **Status:** generated entries are `touchstone:Draft` pending batch review; only the
  15 Gate-1 seeds are `Approved`. clauseText is the full block text (seeds may be
  trimmed to the normative sentences).
- **Drift detection is containment-based** (a stored clauseText must appear inside
  some re-extracted block after normalization), so trimmed seeds and repeated
  fragment clauses ("This property is REQUIRED.") behave correctly; identical
  fragments share hashes by design.
- **The curation file** (`catalog/sources/*.curation.json`) is the human-review
  record. `emit_candidates.py` refuses unaccounted blocks, duplicate slugs, and
  literal-unsafe text, and regenerates idempotently from a marker line.

### D-0011 — Tomcat ban carve-out: `tomcat-embed-el`
Boot 4's `spring-boot-starter-jetty` itself ships `org.apache.tomcat.embed:tomcat-embed-el`
— the Jakarta Expression Language implementation, not a servlet container (Jetty bundles
no EL). The enforcer `bannedDependencies` rule (stronger than §4's one-off
`dependency:tree | grep -i tomcat` — it fails every build on regression) bans all Tomcat
coordinates *except* exactly that jar, and a boot smoke test asserts the embedded server
is a `JettyWebServer` instance. `dependency:tree` confirms tomcat-embed-el is the only
Tomcat-groupId artifact on harness-mcp's classpath.

### D-0012 — Core-draft clause drift vs the brief's §2 "known testable clauses"
Checked over the full WD-20260622 extraction:
- *"Last-Modified MUST be generated on GET/HEAD"* — **gone**. The WD mandates **ETags on
  all GET/HEAD responses** plus conditional requests (304). Successor seeds:
  `etag-on-get-head-conditional-304`, `get-data-resource-content-range-etag`.
- *"PATCH insertion formulae MUST NOT contain blank nodes"* — **gone**. The PATCH baseline
  is **JSON Merge Patch** (`application/merge-patch+json`); no RDF-patch format exists in
  the core WD.
- *"Failed credential validation MUST return 401 + WWW-Authenticate"* — **survives,
  strengthened**, in the authorization/token sections: any 401 MUST carry a conforming
  WWW-Authenticate challenge; failed validation MUST yield 401 with an error parameter
  (`invalid_token`, …). Not in the seed set (seeds are Operations/Containers per §11.3);
  queued for mass extraction.
- Broader model shift: **no `ldp:` vocabulary anywhere in the WD.** Containment surfaces
  as the container representation (`items`, `totalItems`) plus `Link rel="up"` /
  `rel="linkset"` headers; container-representation conneg baseline is
  `application/lws+json` / `application/ld+json` / `application/json` (Turtle is only MAY).
  Consequence: the brief's §5.2 example (an `ldp#contains` graph assertion) is re-expressed
  against the WD model in the manifest-schema example, and the manifest schema gains a
  first-class JSON-pointer assertion block alongside the graph/SHACL assertions (JSON-LD
  is still RDF; both views stay supported).

### D-0010 — Auth-suite drafts are early "unofficial proposal" editor's drafts
The four suites live in-repo as `lws10-authn-{openid,saml,ssi-cid,ssi-did-key}`
(brief's module names auth-oidc/-saml/-cid/-didkey map 1:1). The OIDC draft is thin:
claim-mapping clauses (`sub`/`iss`/`azp`/`aud`, "MUST NOT use 'none'"), no
401/WWW-Authenticate text — that clause lives in core §4 Authentication. No impact
on phase order; auth catalogs will be extracted per-suite at Phase 4+.

## 2026-08-19

### D-0026 — JSON-LD contexts ship with the harness; the parser never dereferences one
First run against a third-party SUT (Halcyon at `vulcan.bmi.stonybrook.edu/alpha/`) failed
`core/container-conneg` and `core/container-containment-after-post` with Jena's
`Unexpected response code [404]`. Cause: a real server sends the context by IRI —
`"@context": "https://www.w3.org/ns/lws/v1"`, which core WD §12.1.1 *requires* container
representations to include — and **W3C has not published that document** (404 today;
`lws10-core/jsonld-context.md` still carries the open TODO of w3c/lws-protocol#216 to add
its digest "once the context document is finalized"). Titanium tried to fetch it mid-parse
and failed. `RefLwsServer` emits an **inline** `@context` object, so the self-test loop
never exercised the remote-IRI path and the gap stayed invisible for six phases.

This was not a cosmetic failure: both tests carried their requirement IRIs into `earl.ttl`,
so the harness recorded the SUT as failing six MUSTs (all five `conneg-*` plus
`containment-create-atomic-items`) that it demonstrably satisfies — a false non-conformance
claim in the artifact intended for W3C implementation reports.

**Decision:** `harness-core` bundles the context documents it understands and
`Graphs` routes every parse (response bodies, isomorphism fixtures, SHACL shapes) through
an offline Titanium `DocumentLoader` (`JsonLdContexts`). A context IRI that is not bundled
is a hard, explicit failure — never a network fetch. Rationale, in order of weight:
- the context IRI comes out of the **SUT's response body**, and SUT responses are untrusted
  input (DESIGN.md §7.3): a dereferencing parser lets any target steer harness requests and
  redefine the term mappings a verdict is computed from;
- a conformance verdict must not depend on third-party infrastructure being reachable;
- the core draft says so itself: "Production systems are advised not to fetch remote JSON-LD
  context documents at runtime. Bundling or caching contexts locally ... prevents context
  manipulation attacks."

`touchstone/context/lws-v1.jsonld` is copied **verbatim** from the pinned baseline draft
(WD-lws10-core-20260622 §12.1.1 "Normative JSON-LD Context", the block introduced by "The
context is defined as follows"), so the mapping the harness computes triples from is the
one the spec normatively defines, not a reconstruction. sha256 of the bundled file:
`364cc0859fe6e161a2d6c43401dc39718402b9120ac0c88383d33aae3aa78336`.

**Follow-ups:** when W3C publishes `https://www.w3.org/ns/lws/v1`, re-copy it and check it
against the digest table that #216 will fill in; extend `BUNDLED` deliberately (one entry
per context the suite must understand) rather than reopening network access. Verified: the
same run went from 6/13 to 8/13 with no change to any manifest.

### D-0027 — run-root cleanup sends `If-Match` and reports failure
`RunContext.close()` sent an unconditional `DELETE`. A target may require conditional
writes — Halcyon answers an unconditional DELETE with `428 Precondition Required` — so
cleanup failed on every run and, because the failure was swallowed by design ("cleanup is
advisory"), left a `touchstone-run-*` container behind on the SUT with nothing said about
it. Cleanup now HEADs the run root for its current ETag (re-read at close time: the listing
changes as tests create resources, so the validator captured at creation is stale) and
sends it as `If-Match`, keeping `Depth: infinity`. When the target issues no ETag the DELETE
stays unconditional, so servers like `RefLwsServer` are unaffected.

Cleanup stays advisory — it is still never thrown — but it is no longer silent: a
non-2xx status or an exception logs a warning naming the run root that was left behind.
**Known limitation:** recursive delete is a MAY in the WD
(`delete-non-empty-container-409-depth`), so a target that does not implement
`Depth: infinity` will answer 409 and the run root will persist. That is now visible in the
log instead of invisible; a client-side recursive sweep is the fix if a real target needs it.

### D-0028 — the target may supply a default identity for manifests that declare none
The core manifests declare no `as:` — correctly, since the operations they test are not about
authentication — and the loader turned that absence into the literal identity `anonymous`. So
the whole core suite was unauthenticated by construction and could only run against a
world-readable storage. The moment a real SUT (vulcan `/alpha/`) restricted access to named
identities, all 13 core tests would have failed on 401 with no way to configure otherwise.

`ManifestLoader` now records an absent `as:` as **null** ("undeclared") instead of collapsing
it to `anonymous`, and `Executor.identityFor` resolves in order: the step's `as`, then the
manifest's `as`, then the target's `defaultIdentity` property, then `anonymous`. The
distinction between *undeclared* and *explicitly `anonymous`* is the point: an explicit
`as: anonymous` still wins over the target default, so
`auth-oidc/anonymous-request-401-challenge` keeps testing what its name says even when the
target authenticates everything else.

This is target configuration, not a manifest change — the manifests stay spec-shaped and
portable, and the same suite runs against an open reference server and a locked-down third
party. The manifest schema is unchanged in validation terms; only the `as` annotation was
reworded (its `default: "anonymous"` was an annotation, never a constraint), so Gate 2's
freeze is intact.

### D-0029 — an MCP progress token is `string | number`, so the parameter is `Object`
`start_run` was unusable from Claude Code: every call failed with
`argument type mismatch` before a line of the method ran, while all ten other tools
worked. It was the only tool declaring `@McpProgressToken String progressToken`.

The MCP schema types a progress token as `string | number`, and the SDK agrees at both
ends — `CallToolRequest.progressToken()` returns `Object`, and `ProgressNotification`'s
first component is `Object`. Spring AI 2.0.0 binds the annotated parameter by passing
that `Object` **straight through with no conversion** (verified in
`AbstractMcpToolMethodCallback.buildMethodArguments`). A client that sends a numeric
token — Claude Code does — therefore handed an `Integer` to a `String` parameter and
`Method.invoke` threw. Declaring the parameter `Object` is not a widening for
convenience; it is the type the protocol and the SDK both specify. It also round-trips
the token unchanged, which is what lets a client correlate notifications: a token echoed
back as `"3"` when it was sent as `3` matches nothing.

`progressSink` takes `Object` for the same reason. String tokens are unaffected.

### D-0030 — provisioning uses the target's `defaultIdentity` too
D-0028 taught the **executor** to run undeclared-`as` steps as the target's
`defaultIdentity`, so the suite could face a locked-down SUT. Provisioning kept its own
hardcoded `anonymous` fallback, and the two disagreed.

The result was a target that looked configured and still could not start. Every step
would have authenticated correctly, but the run root they all live under was still
requested anonymously, so `Containers.create` got 401 and the run died at provisioning
with **0 of 13 tests executed** — the same total failure D-0028 set out to prevent, one
layer down. `provisioner` now falls back to `defaultIdentity` before `anonymous`. It
stays a separate property because the two can legitimately differ: a suite may want the
run root owned by one agent and the tests driven by another.

`targets.yaml` names a `defaultIdentity` per target. The credential is never stored in
that file: it is checked in, and the file is the SSRF/abuse boundary. It comes from the
environment as `TOUCHSTONE_TOKEN_<IDENTITY>` and, for an LWS-OIDC target, must be the
**ID Token** (not the access token) from the OpenID Provider the identity's WebID names in
its controlled identifier document, with `sub` equal to that WebID — the chain the resource
server dereferences to decide whether to trust the issuer. Which OP, which OAuth client and
which grant are deployment facts and stay in the operator's environment; note only that a
realm's default client may refuse the direct-access grant, so the client is a thing to
configure, not to assume.

**Known limitation:** that is a static bearer token, per DESIGN.md §5.3's CTH-style
minimum, and Keycloak's default ID Token lifetime is 5 minutes. A core run takes ~60s, so
one token covers a run, but an operator must mint a fresh one per session. Teaching the
adapter to obtain and refresh tokens from a configured OP (client-credentials or password
grant, secret from the environment) is the durable fix and is not yet done.

### D-0031 — a `sub` with a leading whitespace character refuses every credential
With the identity configured, a correctly minted ID Token still got
`401 the access token is not valid` from the SUT on both GET and POST.

The token's `sub` carried a **leading space** before the WebID — one character, invisible
in an OP's admin console and easily carried in on a paste. An LWS-OIDC verifier tests
whether the subject starts with `http://` or `https://` before claiming the credential;
a subject failing that is declined as "not an LWS-OIDC credential", so the chain moves on
rather than reporting it malformed. Where no other verifier stands behind it, the chain
ends empty and answers `invalid_token`. Nothing anywhere names the space, which is what
made it expensive: the OP issues a token correct in every visible respect and every
resource server refuses it.

The origin was the OP-side protocol mapper returning its configured user attribute
verbatim. Fixed upstream (trim before it becomes the claim; blank-after-trim treated as
unset), and correctable without a redeploy by fixing the attribute value itself.

The lesson for this harness: an authentication failure that reports only `invalid_token`
is worth inspecting the credential's own claims for, byte by byte, before assuming the
target's authorization is at fault.

### D-0032 — run bundles are stamped, and the report ships as JSON and PDF too
`runs/` was a list of hashes. A run id sorts arbitrarily because it is one, so a season of
runs told you nothing about when any of them happened and finding the latest meant opening
them. A bundle is now `runs/<xsd-dateTime>-<runId>/`, e.g.
`2026-08-21T19:50:07Z-bda9ae4f`: the run's own `startedAt`, truncated to the second, in UTC,
so lexical order is chronological order. The id stays, and stays last, because it is what
everything else addresses a run by.

`RunDirs.locate` resolves an id to its directory and is what `RunStore.loadPersisted` and the
`report://{runId}/earl` MCP resource now use. It tries the bare id first, so the seven
bundles written before this change are still found — verified after a restart, by loading
`b8910c47` from disk. Nothing is renamed; old runs keep their old names.

**The colons are deliberate.** An XSD `dateTime` has them and Windows filenames cannot, so
this format is portable to POSIX and to WSL's drvfs (tested) but not to a native Windows
host. `RunDirs.stamp` is the single place to change if that day comes — dropping the colons
gives `2026-08-21T193247Z`, still ISO 8601 and still correctly ordered.

The bundle gains `report.json`, `report.md` and `report.pdf` beside `report.html`. All four
render `HtmlReport.model`, the map that already decided what the run found, so they cannot
drift into disagreeing about whether a run conformed — there is one computation of
conformance, not four. `report.json` is deliberately not `run.json`: that file is the evidence (every
step, every redacted exchange, ~80 KB) while this one is the finding (totals, per-level
coverage, the 203-row requirement matrix with verdicts, the tests), which is what a
dashboard or a CI gate actually reads.

PDF is drawn with **PDFBox 3.0.8**, not rendered from the HTML. The maintained HTML-to-PDF
renderers want XHTML and `report.ftl` is HTML5; `com.openhtmltopdf` is dormant at 1.0.10 and
the only newer build is a third-party fork. Drawing from the model needs no parser, adds one
Apache dependency instead of two, and cannot disagree with the other two formats. Text is
filtered to what CP1252 can encode — the Standard 14 fonts are WinAnsi and PDFBox throws on
a character with no glyph, so one em dash in one catalog summary would otherwise have taken
down the whole report.

`report.md` is for where a conformance result is actually read — a pull request, an issue, a
wiki, a terminal. It keeps the one thing the PDF cannot: every requirement in the matrix is a
link to its section of the specification, which is what turns "this MUST failed" into
something a reader can act on without going to look the clause up. Cell content is escaped,
since a pipe or a newline in a catalog summary would otherwise end the cell and break the
table.

Verified end to end: a 13/13 run against a live SUT produced a 5-page PDF whose text carries
the verdict banner, the totals, all 13 tests and the matrix; a `report.json` with 203
requirement rows; and a `report.md` whose 203 matrix rows are all spec links, with no
malformed rows.

**A build trap worth knowing.** `mvn install` alone did not repackage `harness-mcp` after an
earlier `verify` had aborted mid-reactor: the fat jar kept an embedded `harness-core` from
two builds previously, so a freshly written reporter silently did not run. The symptom was
`report.pdf` present and `report.md` absent — impossible from the source, which writes md
first. `mvn clean install` fixed it. Check the artifact, not the source, when a change seems
not to have taken.

### D-0033 — no deployment specifics in files that ship with the harness
D-0030 and D-0031 had grown a particular deployment into the repository: an OpenID
Provider's URL, an operator's WebID, the OAuth client that happened to allow the
direct-access grant. `targets.yaml` carried the same in its comments. None of it is secret —
every one of those URLs answers an unauthenticated GET — but the harness is distributed as a
Docker image and a GitHub Action for third parties to run against their own servers, and a
file that ships with it should describe the mechanism, not one person's Keycloak.

Both are now written generically: a target names a `defaultIdentity`, the credential comes
from `TOUCHSTONE_TOKEN_<IDENTITY>` in the operator's environment, and for an LWS-OIDC target
it must be an ID Token whose `sub` is the WebID that nominates the issuing OP. Which OP,
which client, which grant are deployment facts and stay in the environment.

The `vulcan` target entry itself stays: a pre-registered target is what `targets.yaml` is
for, and the SSRF boundary depends on ids resolving to URLs here rather than being passed in.

Credentials were never committed — checked across all three repositories, in tracked content,
in full history and in commit messages. `runs/` is git-ignored, so no run bundle has ever
been committed either.

### D-0034 — get_report, and markdown is the default
The report bundle has six renderings and the MCP surface exposed one of them, as the
`report://{runId}/earl` resource. An agent that wanted to read what a run found had to
reconstruct it from `get_run` and `get_failures`.

`get_report(runId, format)` returns one rendering: `markdown` by default, or `json`, `html`,
`earl`, `junit`, `pdf`. Common aliases are accepted (`md`, `ttl`, `turtle`, `xml`) rather than
rejected, and an unknown format is refused by naming the ones that exist.

Markdown is the default because it is the one an agent can actually read: 25 KB against
JSON's 107 KB and HTML's 80 KB for the same run, and every requirement in its matrix carries
a link to its clause. The tool description says so, so a caller asking for `json` is choosing
to spend four times the tokens rather than discovering it afterwards. Nothing is truncated —
half a JSON report is not a JSON report — so the size warning is the honest control.

`pdf` returns its path and size with `content` null. An agent cannot read a PDF as text and
base64 of one would spend thousands of tokens to say nothing; the path is what lets it hand
the file to something that can open it.

Resolution goes through `RunStore.reportDir`, which owns the runs directory, rather than
injecting the properties into the tool layer.

### D-0035 — coverage for the three Approved MUSTs that had none, and what blocked the third
Of the 15 Approved requirements (Gate 1), 12 had tests and three did not. The other 129
uncovered MUSTs are `status: Draft` from the mass extraction and are waiting on batch review,
so they are not a test backlog yet — they are a review backlog. These three were the whole of
the actionable work.

Two are now covered and both pass against the reference server and a live SUT:

- `core/patch-merge-patch-baseline` — a merge patch that replaces one member, adds another and
  removes a third with null, which is the part of RFC 7386 a server can get wrong while
  looking correct.
- `core/contained-resource-type-values` — each container in the test holds exactly one member,
  so the assertion names a determinate item instead of depending on listing order.

**The third is blocked at Gate 2 and is not committed.** `linkset-accept-patch-advertised`
needs the linkset's URI, and LWS says a linkset is found through `rel="linkset"` (RFC 9264),
not at any particular path. Binding that relation needs a `link:<rel>` bind extractor, and the
frozen manifest schema pins bind to `^(header:…|status|body)$`. Widening it is a schema change
and CLAUDE.md rule 4 makes that a hard stop. The engine half is implemented and unit-tested
(`LinkHeaders`, RFC 8288: separate fields or one comma-separated field, quoted parameters,
relation lists, case-insensitivity, relative resolution) and the manifest is drafted; both
wait on approval to widen the pattern. The alternative — hard-coding `{resource}.meta` —
would test one server's convention rather than the specification, so it was not taken.

**The reference server grew PATCH.** Its javadoc says outright that unimplemented areas "grow
with the suite", and a test for an Approved MUST that the reference implementation answers 405
would have made the self-test loop unrunnable. It now implements JSON Merge Patch on data
resources. `If-Match` is honoured when sent but not demanded: the spec requires the conditional
for PUT and for a linkset PUT/PATCH and is silent for a data resource, and a fixture that
invented the requirement would fail a conforming server.

**`matches` now applies to non-value nodes.** It required `isValueNode()`, so an assertion
could only test one shape of "X, or an array containing X" — the phrasing the spec uses for a
contained resource's `type` — and would have called a server conforming in the other way
non-conforming. A non-value node is matched against its JSON text; value nodes keep `asText()`,
so nothing that passed before changes.

Per DESIGN.md §7.4 this lands on a branch for review rather than on master.

### D-0036 — six tests for the negative half of already-covered requirements
Coverage counted a requirement as covered when any test referenced it, and the tests referenced
happy paths. Six MUSTs were covered in that sense and still could not fail a broken server.

The sharpest was concurrency. Two tests existed — correct entity tag succeeds, absent entity tag
is 428 — and **no manifest asserted 412 at all**. A server implementing "no If-Match → 428, else
write" passes both and loses every concurrent update, which is precisely the failure the
mechanism exists to prevent. `conditional-if-match-mismatch-412` sends a tag that has gone stale
and then reads the resource back, because a 412 that wrote anyway is worse than no check.

The others follow the same shape: a conditional GET was tested only where it returns 304, so a
server answering 304 to everything passed while starving its clients; ETags were checked on GET
of a data resource, though the clause says "all GET/HEAD responses" and names container listings;
`rel="linkset"` sat in the creation clause beside `rel="up"` with only the latter asserted;
range requests are an unqualified MUST that nothing exercised; and a parent's ETag was never
checked after a deletion, the one case where a surviving tag actively harms — every cache holding
it is told a stale listing is current.

All six map to requirements already Approved, so none needed the Draft batch review.

**The reference server grew what the tests require**, as with PATCH before: single-range requests
(`bytes=a-b`, `bytes=a-`, suffix `bytes=-n`, 416 when unsatisfiable) and a derived per-resource
linkset. The linkset is *served*, not merely advertised — a relation pointing at a 404 is worse
than none, because a client cannot distinguish "no metadata" from "metadata I failed to reach".

**A harness inconsistency surfaced and is fixed.** A header assertion's `contains` tested every
value of the field while `matches` tested only the first. `Link` is legally repeated (RFC 8288),
so a response carrying `rel="up"`, `rel="type"` and `rel="linkset"` as three fields answered a
regex for the third by examining only the first — reporting no match while printing the matching
text in `actual`, which is the worst way to be wrong. `matches` now tests any value, as `contains`
always did.

All 21 core manifests pass against the reference server and against a live SUT. As expected they
found no defect: their value is that the suite can no longer certify a server that gets these
wrong, which until now it could.

## 2026-09-02

### D-0037 — the spec baseline moved: WD 21 August 2026 supersedes the 22 June snapshot
D-0002 pinned the catalog to "Linked Web Storage Protocol 1.0", W3C Working Draft
**22 June 2026**. The live `/TR/lws10-core/` is now the **21 August 2026** WD
(`https://www.w3.org/TR/2026/WD-lws10-core-20260821/`), and all four authentication suites
have dated `/TR/` versions too (3 August, and 21 August for ssi-cid) — so D-0010's
"unofficial-proposal editor's drafts with no dated /TR/" is obsolete as well.

`tools/extractor/check_drift.py` against the new draft: **192 normative blocks** (was 163),
**20 catalogued clauses gone**, **50 uncatalogued**. This is the "spec moved" alarm firing
exactly as DESIGN.md §8 intended; it is recorded here rather than acted on silently.

What changed that the harness asserts on, in order of weight:
- **§10 Notifications is new** — 29 normative blocks (discovery via a `NotificationService`
  in the storage description, a subscription protocol, envelope/Activity Streams data model,
  and three authorization MUSTs about not delivering what a subscriber may not read). The
  brief filed notifications under "future modules"; they are core now.
- **Storage description** — `rel="…lws#storageDescription"` became `rel="…lws#storage"` and
  now points at the *canonical storage URI*; the media type became `application/lws+cid`
  (a CID document specialization) with a two-entry `@context` array starting
  `https://www.w3.org/ns/cid/v1`; a `StorageRoot` service is required.
- **`mediaType` → `format`** on contained resource descriptions. The bundled context defines
  no `format` term, so a conforming server's value is dropped during expansion.
- **Content Negotiation** (four clauses) collapsed into one **Media Type Equivalence** clause
  in §12.1.1, plus a new `Vary: Accept` SHOULD. `core/container-conneg` cites five requirement
  IRIs, all five of which the re-baseline retires.
- **`Slug` is gone** from the draft entirely (0 occurrences; 6 in the June one).
- **§13 no longer carries the normative JSON-LD context inline** — only the (still-404) URL
  and the open digest TODO of w3c/lws-protocol#216. The verbatim copy D-0026 bundled now has
  no source in the current draft.

**Nothing has been re-extracted or re-curated yet.** Re-baselining rewrites `Approved`
Gate-1 entries, so it waits on Erich (CLAUDE.md rule 4). The full itemized plan, with the
independent code defects the same review turned up, is in `TODO.md`; this entry records the
changed premise so the next session does not read `catalog/lws10-core.ttl` as current.

### D-0038 — the run bundle's name is filesystem-safe, and a name can never cost a run its evidence
D-0032 named run bundles `<xsd-dateTime>-<runId>` and recorded the colons as deliberate:
"portable to POSIX and to WSL's drvfs (tested) but not to a native Windows host …
`RunDirs.stamp` is the single place to change if that day comes." The cost of that day was
underestimated. `Path.resolve` throws `InvalidPathException` **before** `Reports.writeAll`
enters its `try`, so on Windows a run wrote **nothing** — no `earl.ttl`, no `report.md`, no
`run.json` — and picocli turned the escaping exception into exit 1. A run of 21 core tests
that passed all 21 reported itself non-conformant and left no evidence of anything. CI is
Linux, so nothing caught it; the repository's own development host is Windows.

Two changes, and the second matters more than the first:
- The stamp is ISO 8601 **basic** form, `2026-08-21T193247Z` — exactly the fallback D-0032
  nominated. Fixed width, so lexical order is still chronological order; the date keeps its
  hyphens because the name is meant to be read. It is not an XSD `dateTime` any more, and
  nothing consumed it as one: `RunDirs.locate` finds a bundle by matching `-<runId>`, so the
  bundles already written with colons are still found and nothing is renamed.
- `RunDirs.resolve` catches `InvalidPathException` and falls back to the bare run id. A
  naming preference must never again be able to destroy a run's results — the bundle is the
  only durable record a run produces, and degrading to a worse name beats producing nothing.

`RunDirsTest` covers the stamp's character set, truncation and UTC, lexical ordering, both
fallbacks, `locate` against stamped and pre-stamp bundles, and that `writeAll` produces all
seven files — all platform-independently, so this cannot return on one person's machine.
Verified end to end on Windows: `clean verify` green across five modules (106 tests), and
`touchstone run --target ref --module core` exits 0 with a complete bundle at
`runs/2026-09-03T013153Z-b9e22538/`.

### D-0039 — a run refuses to start when a declared requirement is not in the catalog
Five `auth-oidc` manifests declared
`…/req/lws10-core/authz-token-validation-verification`. No such requirement exists; the
catalog entry is `authz-token-validation-checklist`. Nothing anywhere checked, and the
failure was silent in three places at once: `earl.ttl` — the artifact intended for W3C
implementation reports — carried a `touchstone:verifies` triple pointing at a requirement
that does not exist; the coverage matrix never counted those five tests; and
`RunTools.strongestLevel` degrades an uncatalogued IRI to `UNCLASSIFIED`, so a failing MUST
can stop deciding conformance. A misspelling silently downgrades a verdict.

The IRIs are fixed, and `core.catalog.RequirementRefs` now resolves every manifest's
declared requirements against the loaded catalog **before** a run starts:
- `touchstone run` prints each offending (manifest, IRI) pair and exits **2** — the code for
  a misconfigured harness, not 1 for a non-conformant server, because the target was never
  asked anything;
- the MCP `start_run` and `run_one` tools decline with the same message;
- `coverage` warns instead of failing: it is a report, not a gate;
- an **empty** catalog yields no findings, since "no catalog configured" is a different
  condition from "this IRI is not in the catalog" and must not masquerade as one.

`RequirementRefsTest` checks the shipped manifests against the shipped catalog on every
build, so the next typo fails in CI rather than in a conformance report. Coverage moved
36 → 38 of 203 as a result: the corrected IRI now counts. This lands before the D-0037
re-baseline deliberately — retiring catalog entries will invalidate more manifest IRIs
(all five cited by `core/container-conneg` among them), and today those would have gone
unnoticed.

### D-0040 — the core catalog is re-baselined on WD-lws10-core-20260821
D-0037 recorded that the spec had moved and left the work for Erich, who authorised it.
The catalog now derives from the 21 August 2026 Working Draft:
`catalog/sources/WD-lws10-core-20260821.{html,clauses.json,curation.json}`. It holds
**191 requirements** (was 162): 147 MUST, 22 SHOULD, 22 MAY.

**How entries were carried across.** Each existing entry's stored `clauseText` was matched
into the new extraction by containment — the same criterion `check_drift.py` uses, so an
entry survives exactly when the drift alarm says it does. 142 of 162 matched one-to-one and
kept their slug, hash and section; 20 did not. Of those 20, six kept their slug with the
draft's new words (`conformance-client-class`, `storage-description-id-required`,
`storage-description-link-header`, `uri-independent-of-hierarchy`,
`create-server-managed-metadata-protected`, `access-endpoints-jsonld-payloads` — two of them
purely editorial: "A LWS Client" became "An LWS Client", and a cross-reference renumbered
from 10.4.1 to 11.4.1); thirteen were replaced by successors under new slugs; and one,
`create-slug-honored-if-no-conflict`, has no successor because Slug is gone. 49 clauses are
new, 29 of them the Notifications section (D-0041).

Retired entries are **removed, not marked**. The catalog states what the current draft
requires; keeping a clause the spec no longer contains would inflate the coverage
denominator and let a test claim to verify something nobody requires. Provenance lives in
the archived snapshot and in git.

**One Approved seed was affected.** `container-representation-conneg` (Gate 1, 2026-07-16)
stated content negotiation from the Operations section; the 21 August draft moved it into
12.1.1 Media Type Equivalence and restated it — same three media types, same body, the
Content-Type echoing the request. It is replaced by a hand-written seed
`conneg-media-type-equivalence` that **carries the Approved status over**, on the reasoning
that the obligation was reviewed and approved and only its wording changed. That is a
judgement about an approved item and is flagged for Erich: demoting it to Draft instead is a
one-line change. The other fourteen seeds matched unchanged.

**What this changed outside the catalog.**
- `core/container-conneg` cited five requirement IRIs, all five retired. It now cites the
  single successor, plus the new `Vary: Accept` SHOULD and the container media-type clause.
  The D-0039 gate is what made this visible rather than silent.
- Two manifests are new: `core/storage-description-discovery` (seven requirements: the
  `lws#storage` relation on GET and HEAD, the storage URI answering `application/lws+cid`,
  and the description's `@context` array, `id`, `Storage` type and `StorageRoot` service) and
  `core/contained-resource-format` (`format`, which replaced `mediaType`).
- `Graphs.langFor` learned `application/lws+cid` and `application/linkset+json`; without the
  first, every storage-description assertion would have failed as "no RDF reader".
- `RefLwsServer` grew what those tests require: a storage description at the storage URI with
  `application/lws+cid` as its default representation, the `lws#storage` link on every
  response, `format` in place of `mediaType`, and `Vary: Accept` on container responses.
- **The reference server now names its context by IRI** rather than inlining one. That was
  the six-phase blind spot of D-0026: with an inline context the self-test loop never
  exercised the path a real response takes. It does now, through the offline loader.
- A `${target.baseUrl}` template variable was added, so a discovery test has a URI that is
  the storage rather than a container inside it. This is not a schema change — `template` is
  an unconstrained string and the variable list was always annotation — but both schema
  copies document it.
- `check_drift.py` now also fails when a `touchstone:section` anchor no longer resolves to an
  id in the draft. Five entries pointed at `#content-negotiation` and
  `#normative-json-ld-context`, which the new draft does not have; a report's spec links are
  the part a reader is meant to follow, so a dead one is a silent regression.

**The JSON-LD context is now orphaned, and `format` is not testable as a graph.** Section 13
of the 21 August draft no longer carries the context inline — it gives the URL, still a 404,
and the unfilled digest of w3c/lws-protocol#216 — so there is nothing newer to copy than the
22 June block D-0026 bundled. That copy defines `mediaType`, not `format`, so a conforming
server's `format` expands to nothing. The obvious repair, adding the term ourselves, is
exactly what D-0026 refused: a verdict must not be computed from a mapping nobody published.
Checked before deciding: the **Linked Web Storage Vocabulary** Group Note
(<https://www.w3.org/TR/2026/NOTE-lws10-vocab-20260714/>, 14 July 2026) also defines
`mediaType` (as `as:mediaType`) and lists it in the `lws/v1` term list, with a
`StorageDescription` class and a `storageDescription` property — i.e. the vocabulary is one
draft behind the protocol on precisely the terms that changed. So no published artefact
defines `format`, `storage` or `StorageRoot` yet. `core/contained-resource-format` therefore
asserts through JSON pointers, which read the response as it was sent, and
`JsonLdContextsTest` pins the absence so it stays a decision rather than a surprise. Good WG
feedback item, alongside #216.

**`https://www.w3.org/ns/cid/v1` is now bundled too.** A storage description's `@context`
must be an array starting with it, and unlike the LWS context it resolves (200,
`application/ld+json`, 3248 bytes, sha256
`ea216ecc1cb02cd39b693dba2250141e270ba0bf95890be107dd9a9e8e43de85`). Without it the offline
loader — correctly — refuses every storage description.

**Slug.** The 21 August draft contains the word nowhere (the June draft had six occurrences);
what remains is "servers ... MAY incorporate client hints". Manifests are specification
documents, so all nineteen stopped sending it. `Containers.create` still sends one, because a
run root an operator can recognise in their own storage is worth having and a server is free
to ignore an unknown header — provisioning is operations, not conformance. Nothing has ever
depended on it: created URIs are read from `Location`.

Verified: `clause_hash.py --check` passes on all 191; `check_drift.py` reports no drift and
no dead anchor; `clean verify` is green across five modules; the 23-manifest core suite
passes against the reference server.

### D-0041 — Notifications: catalogued now, the fixture is a documented seam
Section 10 of the 21 August draft is new and normative: 29 clauses, plus three more in the
Security and Privacy Considerations. All are catalogued (`notification-*`, `subscription-*`,
`notificationservice-*`, and the two considerations entries), so coverage counts them and
`list_requirements` and the report matrix show them.

No manifests and no reference-server support, for the reason D-0024 gave for SAML: a test the
reference implementation cannot exercise proves nothing. Notifications need a subscription
endpoint, a stored subscription, and a delivery transport before a test could distinguish a
conforming server from one that answers 404 — and the interesting clauses are not the shapes
but the three authorization MUSTs: a server must reject a subscription whose `topic` includes
anything the subscriber cannot read, must never deliver a notification for a resource
unreadable at event time, and must stop delivering when access is revoked. Those deserve a
negative matrix built the way the OIDC one was (D-0017), not a happy-path shape check.

Discovery is the one part that is nearly free — a `NotificationService` in the storage
description — but notifications are a MAY, so the manifest would be capability-gated and
would skip against the reference server, which is the same thing as untested.

The seam is small and named: `RefLwsServer` already serves a storage description to advertise
the service in, and the subscription protocol is ordinary authenticated POST with an
`application/lws+json` body.

### D-0042 — the authentication suites move onto their published documents
D-0010 recorded the four suites as "unofficial proposal" editor's drafts with no dated `/TR/`
snapshot. All four now have one: openid, saml and ssi-did-key as W3C Working Drafts of
3 August 2026, ssi-cid of 21 August 2026. `touchstone:section` points at the latest-version
`/TR/lws10-authn-*/` URL and `sourceDraft` at the dated one — the same split the core module
uses — and the snapshots in `catalog/sources/` are the published documents.

**Clause text is re-taken from the rendered document.** The July extraction read the ReSpec
*source* page, so stored text carried unrendered macros: `…controlled identifier document
[[!CID-1.0]] with an id value…` where the specification says `[CID-1.0]`. That text is what
`get_requirement`, the `requirement://` resource and every report matrix put in front of a
reader, and it made `check_drift.py` report six false drifts against the authoritative
document while reporting none against the source page. Every clause matched after expanding
the macros, so nothing normative changed — the suites' only movement since July is added
prose in the informative Security and Privacy Considerations sections. All 41 hashes were
recomputed.

`catalog/sources/` now keeps exactly one snapshot per module, the draft that module is
baselined on; the superseded June core snapshot and the four editor's drafts were removed
rather than left alongside, since two snapshots would make it ambiguous which one a hash came
from. Git holds them.

### D-0043 — the key-rotation test grepped a JWKS for a two-character key id
`OidcIssuerTest.rotationReplacesThePublishedKey` asserted `doesNotContain(beforeKid)`
against the raw JWKS document. Key ids in this fixture are `k1` and `k2`; an RSA modulus
is around 340 characters of base64url, so the literal pair `k1` turns up inside an
unrelated key's `n` value about one run in twelve. It surfaced on the merge to master and
had nothing to do with the merge: the fixture had simply generated a modulus containing
`k1`, and the test read that as "the retired key is still published".

The mirror direction was worse and silent: `contains(afterKid)` would have passed on a
JWKS that published no such key at all, as long as those two characters appeared anywhere
in it. A test that can pass for the wrong reason is not evidence of anything.

The assertion now parses the JWKS and compares key ids — `containsExactly(afterKid)`,
which also says the thing the test is named for: rotation *replaces* the key rather than
appending to it. Run ten times in a row before committing.

### D-0044 — the Jetty pin now governs, and the build fails if it stops
`<jetty.version>12.1.11</jetty.version>` had no effect anywhere. `harness-core`,
`-fixtures` and `-cli` resolved **12.1.8**; `harness-mcp` resolved **12.1.10**. An imported
BOM carries the dependencyManagement it inherits, and `org.apache.jena:jena` — jena-bom's
parent — imports `jetty-bom` at 12.1.8. Maven resolves imports first-declared-first, and
jena-bom was declared above jetty-bom, so the property read like a decision while Jena made
the real one. The same leak had already been found once, for `logback-core`, and pinned
around with an explicit entry; Jetty was missed.

Three changes:
- `jetty-bom` is imported **first** in the root POM.
- `harness-mcp` imports `jetty-bom` and `jetty-ee11-bom` ahead of `spring-boot-dependencies`.
  A child's own dependencyManagement is consulted before the parent's, so the root import
  does not reach that module, and Boot 4.0.7 would otherwise decide it (12.1.10). `jetty-bom`
  alone was not enough: the servlet and websocket layers Boot's Jetty starter pulls live
  under `org.eclipse.jetty.ee11` and need their own BOM. Jetty releases the two in lockstep,
  so 12.1.11 core over a 12.1.10 servlet layer is a combination nobody upstream tests.
- An enforcer `bannedDependencies` rule bans `org.eclipse.jetty*:*:(,${jetty.version})`
  transitively, so a future BOM winning the argument fails the build instead of the pin going
  quietly stale. It earned its keep immediately: it is what caught the ee11 half.

`dependency:tree` now reports a single Jetty version, 12.1.11, in every module.

**CI never ran on the default branch.** `.github/workflows/ci.yml` triggered on
`push: branches: [main]`; the default branch is `master`. Pull requests ran, pushes did not,
so D-0007's "green CI on main" acceptance was never actually being met on the branch it
names. One word.

**Two assertion-engine corrections.**
- Conneg equivalence asserted graph **isomorphism**, which is weaker than the clause: "the
  response body is the same JSON-LD document ... and only the Content-Type response header
  varies". A server free to re-serialize per media type passed. It now compares the bytes,
  and when they differ it reports whether the graphs still match, because "re-serialized" and
  "different content" are different defects and a report should say which one it found. It
  also asserts the Content-Type echoes the requested type on each variant, which the clause
  requires and which the assertion was already fetching the evidence for.
- A header `equals` tested only the field's **first** value while `contains` and `matches`
  tested any. D-0036 fixed that disagreement for `matches`; this was the third spelling, and
  for a legally repeated field like `Link` it decides the verdict. All three now agree.

**Redaction reaches bodies and URLs.** `Redaction` stripped six header names and nothing
else, which held only because no test yet exercised a flow carrying a credential elsewhere.
Core 5.2.3 token exchange puts a `subject_token` in a request body and an `access_token` in
the response, and OAuth has always permitted a token in a query string; any of those would
have landed verbatim in `run.json`, `report.html` and every MCP `get_trace`. The scrub is
name-based over the well-known credential parameter names, applied to JSON members, form
fields and query parameters, and the header list gained `dpop-nonce`, `api-key`,
`authentication-info` and `proxy-authenticate`. It cannot catch a credential under a name
nobody standardised, so it is a floor rather than a guarantee — which is the other reason
bodies stay truncated. `RedactionTest` covers the token-exchange request and response shapes
and asserts that a `WWW-Authenticate` challenge, which is the evidence a 401 test exists to
capture, is *not* redacted.

**An unclassifiable failure no longer reads as conformant.** `RunTools.strongestLevel`
returned `UNCLASSIFIED` for a requirement the catalog does not hold, and `mustFailures`
counted only `MUST`, so a run against an unconfigured catalog could report every failure and
still say `conformant: true`. D-0039 makes a dangling IRI stop the run, so the remaining path
here is a missing catalog — which now logs, and whose failures count. Failing safe is the
only defensible direction: a missing catalog must not look like a passing server.

### D-0045 — manifest schema 1-1-0: a test may follow a link relation
Gate 2 froze the manifest schema at `$id …/manifest/1-0-0` with the rule that a change bumps
the version and is recorded (D-0013). This is that bump: `$id` is now
`…/manifest/1-1-0` and `bind` accepts `link:<rel>` beside `header:<Name>`, `status` and
`body`. `schemaVersion` stays `1` — the addition is backward compatible and every manifest
written against 1-0-0 validates unchanged.

The engine half has existed since D-0035: `LinkHeaders` implements RFC 8288 (separate or
comma-joined fields, quoted parameters, relation lists, case-insensitivity, relative
resolution) and `Executor.bind` has resolved `link:` all along. Only the schema forbade it,
so the code was unreachable and the tests that needed it could not be written. D-0035
recorded one Approved MUST blocked on this; by the 21 August re-baseline it was six, because
storage-description discovery has the same shape — LWS says a linkset is found through
`rel="linkset"` and a storage through `rel="…lws#storage"`, at no particular path. A test that
guesses a URI tests one server's convention; a test that follows the relation tests the
specification.

Two tests follow from it:
- `core/linkset-discovery-and-patch-advertisement` finally covers
  `linkset-accept-patch-advertised`, the Approved MUST D-0035 had to leave uncovered, along
  with the standalone-linkset, media-type and `Allow` clauses. A client that cannot learn
  the linkset takes PATCH, or in which format, has to guess and handle the 405 or 415 it gets
  wrong. The reference server grew the `Allow` header the clause requires.
- `core/storage-description-discovery` now fetches the URI the server **advertises** rather
  than the one the target was registered as, and checks the two agree. The gap that manifest
  documented is closed.

**A stray backslash proved the schema needed a guard.** The first edit wrote `link:\S+`,
where JSON requires `link:\\S+`; the schema stopped parsing, and because `ManifestLoader`
compiles it in a static initialiser the whole class failed to initialise and every test that
loads a manifest died with `ExceptionInInitializerError`, saying nothing about the cause.
`SchemaSyncTest` compared the two copies for equality but never asked whether either was
valid JSON — two identical broken files passed. It now parses the schema, checks the `$id` is
the version claimed, and exercises the `bind` pattern against real extractor strings.
