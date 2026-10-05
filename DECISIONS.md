# DECISIONS.md — deviations and clarifications vs DESIGN.md

Per the brief: decisions in DESIGN.md are settled; anything that changed on contact
with reality is recorded here, dated, with rationale. When the spec and the brief
disagree, the spec wins.

Gate status:
- **Gate 1: CLOSED — Erich approved the 15 seed requirements on 2026-07-16; mass extraction authorized (D-0013).**
- **Gate 2: CLOSED — manifest JSON Schema v1 frozen on 2026-07-16 (D-0013).**
- **Gate C: CLOSED — Erich approved the client rule format on 2026-10-05; format 0.8.0 frozen (D-0079).**

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

### D-0046 — dependency refresh, and the distribution hardened for people who are not us
P3 of the 2026-09-02 review. Four unrelated things, all about the harness as something a
third party runs rather than as something we run here.

**Dependencies**, re-verified against `repo1.maven.org` metadata on 2026-09-02 and taken in
two steps so a break would name itself. Patch and minor bumps first: Jena 6.1.0 → 6.2.0,
Jetty 12.1.11 → 12.1.12, Jackson 2.22.1 → 2.22.2, JUnit 6.1.2 → 6.1.3, FreeMarker
2.3.34 → 2.3.35, networknt json-schema-validator 3.0.6 → 3.0.7, oauth2-oidc-sdk
11.38.1 → 11.38.2, BouncyCastle 1.85 → 1.85.2, Spring AI 2.0.0 → 2.0.1. Then the two
minor-line moves, each verified on its own: Logback 1.5.38 → **1.6.3** and Spring Boot
4.0.7 → **4.1.1**. Unchanged, and still correct: Nimbus JOSE+JWT 10.9.1, picocli 4.7.7,
PDFBox 3.0.8, SLF4J 2.0.18 (2.1 is alpha), AssertJ 3.27.7 and Titanium 1.7.0 (both have
only milestones above them). D-0044's enforcer rule was checked afterwards: one Jetty,
12.1.12, in every module, and `tomcat-embed-el` still the only Tomcat-groupId jar.

**The Action interpolated a caller's input into the script that writes the SSRF boundary.**
`targets.yaml` is what makes a target addressable at all (DESIGN.md 7.1), and it was written
by a heredoc with `${{ inputs.target-url }}` expanded into it — GitHub's documented
script-injection shape, where a newline in the input appends further target entries or shell.
Inputs now reach the step through `env:`, the heredoc is gone in favour of `printf`, and the
URL is checked to be an absolute http(s) URL with no whitespace or quotes before it is
written.

**The Action reported a misconfigured workflow as a non-conformant server.** It failed the
job on any non-zero exit, and the CLI already used 2 for "the harness cannot run" — unknown
target, missing registry, and since D-0039 a manifest naming a requirement the catalog does
not hold. A server implementer reading that saw their server blamed for their own workflow.
Exit 2 now fails the job with a message saying which of the two it is, and the exit-code
table is in `docs/distribution.md`.

**The image no longer runs as root.** It needs read access to its own jar, catalog and
manifests, and nothing else. The wrinkle is the bind mount: a fixed uid inside the container
cannot write a workspace owned by the runner, so a non-root image alone would have broken the
Phase 6 acceptance path. The image therefore ships a non-root default *and* the Action runs it
with `--user "$(id -u):$(id -g)"`, which is also what the documented `docker run` example now
does. `WORKDIR` deliberately stays `/opt/touchstone`, because the CLI's `--catalog` and
`--manifests` defaults are relative and resolve against it.

Verified end to end, not merely built: the image runs as uid 10001 by default, `coverage`
works under an arbitrary `--user 1001:1001`, and a containerised `run` against a host-side
reference server through `host.docker.internal` scored 24/24, exit 0, writing all seven report
files into a host-owned bind mount owned afterwards by the invoking user.

**The MCP server binds loopback by default.** Spring Boot's default is every interface, and
this process drives pre-registered targets with deliberately malformed traffic behind an
unauthenticated tool surface that can start runs. DESIGN.md 7.5 asks for a Spring Security
OAuth2 resource server *if* the endpoint is hosted; until that exists, `server.address:
127.0.0.1` is the honest default, and an operator who means to expose it sets the address
explicitly and fronts it with something that authenticates. The stdio profile is unaffected —
it starts no web server at all.

## 2026-09-23

### D-0047 — YAML-LD definitions mirror lws-test-suite, extend it, and flow back as JSON-LD
The premise of D-0006 and D-0013 changed. D-0006 recorded lws-test-suite as w3c/lws-protocol
PR #145's strawman, and D-0013 chose "build independently, stay format-aligned". The LWS test
group now means to make that suite authoritative, standardized on JSON-LD test manifests.
Erich's direction: Touchstone authors its tests in YAML-LD, mirrors lws-test-suite and goes
further, and will later convert to JSON-LD and contribute them. It does not contribute yet. The
engine for the new format is to be generated from the definitions.

**What landed** is `definitions/`, with an `lws10/` tree laid out like lws-test-suite's so that
export is file-for-file:
- 101 tests: 84 MUST, 15 SHOULD, 2 MAY;
- a JSON-LD context proposed as the successor to lws-test-suite's, and a touchstone-only
  context (catalog links, traceability) that export drops;
- an RDFS vocabulary for every `lwst:` term, and an identity registry;
- a JSON Schema at proposal `0-1-0`;
- `EXECUTION.md`, the contract the generated engine must meet.

All 27 lws-test-suite tests have a counterpart, and 32 of the 33 `manifests/` are superseded;
`definitions/COVERAGE.md` maps both. They follow the W3C YAML-LD draft: YAML 1.2 Core Schema,
which rules out SnakeYAML 1.x and jackson-dataformat-yaml for the engine. They are also written
so that a YAML 1.1 reading is identical.

**Against lws-test-suite, the counterparts correct:**
- DELETE expects 204, the draft's MUST;
- PUT accepts 200 or 204;
- `rel="…lws#storage"` and `application/lws+cid` for discovery;
- the did:key suite's `jwt` token type;
- discovered URLs in place of `.meta`, `Slug`-derived Locations, `/alice/description` and
  `/token`;
- identities in place of credentials embedded in tests.

**Against `manifests/`, they fix what the 2026-09-23 review found:**
- *SHOULD checks decided conformance.* A SHOULD or MAY check inside a MUST test did so, because
  `HtmlReport` fails every requirement a failing test cites. This affected `Vary` in
  container-conneg, `Accept-Ranges`/416 in the range test, and recursive delete (a MAY) in the
  409 test. Each is now its own test at its own level.
- *Order-dependent assertions.* Listing items were asserted at `/items/0`, the storage
  description required StorageRoot at `/service/0`, and the challenge regex required `as_uri`
  before `realm`. Matching is now by content, and challenges are parsed per RFC 9110.
- *Nominal OIDC coverage.* The storage-side token tests cited `lws10-authn-openid` ID-token
  clauses while sending RFC 9068 access tokens, so the "2 of 8" OIDC coverage was nominal. They
  now cite the core clauses only; the OIDC suite has its own authorization-server tests.
- *Unused variants.* `notYetValid()` and `missingSubject()` existed in `AccessTokens` but no test
  used them, whatever D-0017 said. Definitions now exist for not-yet-valid, iat-in-future and
  multiple-audience tokens.

**The spec moved again.** The core WD of 21 September 2026 supersedes the 21 August baseline.
`check_drift.py` reports 14 changed clauses, most of them editorial. The substantive changes:
- the 428-on-unconditional-PUT MUST is gone ("Clients SHOULD use conditional requests");
- conditional-request support dropped from MUST to SHOULD;
- the MUST that a container's ETag change after a member is deleted is gone;
- the SHOULD for a new ETag after a PUT is gone.

`manifests/core/put-unconditional-428` therefore fails conforming servers; the definitions retire
it. The CID suite also has a 21 September WD, with no normative drift. The catalog is **not**
re-baselined here: that rewrites Approved entries, so it waits for review, as in D-0037. The
definitions cite none of the seven affected entries.

**What did not change.** `manifests/`, schema `1-1-0` and `touchstone run` are untouched.
Superseding `manifests/` is proposed, not decided; it follows once a generated engine runs
`definitions/` green against the reference server.

**Gate.** Format 0.1.0 (context, vocabulary, schema, EXECUTION.md) is Proposed. Freezing it is a
review gate, the analogue of Gate 2 (D-0013), and engine generation should start from the frozen
format. This lands on the branch `feat/yaml-ld-definitions` rather than master (DESIGN.md 7.4, as
in D-0035).

### D-0048 — exit code 2 means "no verdict", the stdio profile answers, and `-Dexec.mainClass` chooses the launcher
Writing the documentation site meant running every command before documenting it. That
turned up three defects the build does not catch.

**The CLI exited 1 when it had no verdict.** D-0046 split the exit codes: 1 for a
non-conformant server, 2 for a misconfigured harness. The split covered the checks
`RunCommand` makes itself: registry, target, module and catalog. It did not cover what
escapes as an exception:
- a manifest the schema rejects (`InvalidManifestException`);
- a target that is unreachable, or that refuses to create the run root
  (`ProvisioningException`);
- in `diff`, a run record that cannot be read.

picocli exits 1 for an escaped exception, so each of these reported a broken workflow as a
non-conformant server. That is the confusion D-0046 set out to end, and it includes the
commonest real misconfiguration: no identity with write access, so the run root gets a 401.

`TouchstoneCli.HARNESS_ERROR` now states the contract in one place. Exit code 1 is a verdict:
a non-conformant run, or a diff with regressions. Exit code 2 is no verdict.
- `run` catches the two exceptions and prints the reason and its causes, without a stack
  trace.
- `diff` does the same for an unreadable run, and `coverage` for an invalid manifest.
- Every command declares `exitCodeOnExecutionException = 2`. An exception nobody
  anticipated, such as a catalog that does not parse, therefore cannot exit with the
  verdict's code. It keeps its stack trace, since it is a bug.
- `--help` lists each command's exit codes, and the Action's error message covers the new
  cases.

Five tests cover it: an invalid manifest, an unreachable target, a secured target refusing
the run root, an unreadable run, and an unparseable catalog. Each exits 2, and each exited 1
before.

This widens what 2 means for an external server. A server that is down, or that cannot create
a container, now yields "no verdict" rather than "non-conformant". That is the accurate
reading: no test ran, and no report exists to support a verdict.

**The stdio profile started no MCP server.** `application-stdio.yml` set
`spring.ai.mcp.server.protocol: STDIO`. Spring AI 2.0's `ServerProtocol` has only `SSE`,
`STREAMABLE` and `STATELESS`, and `McpServerAutoConfiguration` requires one of the first
two, so it matched nothing. The process started, logged that it had, found no transport to
serve, and exited. Nothing tested the profile.

The line is gone, so the protocol is the `STREAMABLE` inherited from `application.yml`. With
no web server, stdio is the only transport. `TouchstoneMcpStdioTest` runs the profile as a
separate process, as a client would. It requires the first line on stdout to be the reply to
`initialize`, which also guards the rule that nothing else may write there, and then calls a
tool. It failed before the fix.

**`-Dexec.mainClass` could not choose a launcher.** `harness-fixtures/pom.xml` set the exec
plugin's `<mainClass>` directly, and configuration in the POM beats a user property. So
`SecuredRefScenarioMain`'s own documented command started `RefLwsServerMain`, which parsed
the file argument as a port. The main class is now the `exec.mainClass` property, which `-D`
overrides. Verified both ways:
- the documented command writes `targets-secured.yaml`, and `auth-oidc` passes 9/9 against
  it;
- plain `exec:java -Dexec.args=4711` still starts the reference server.

`docs/` drops the workarounds it carried for the last two, and its exit-code tables describe
the new contract. The other findings from the same pass remain open in TODO.md.

### D-0049 — a failed assertion decides a test's outcome, and the MCP tools say what they touch
Two more findings from the documentation pass (D-0048).

**A failed assertion now outranks the error it causes.** A step can fail an assertion and
then fail to bind a value, and the second failure usually follows from the first: a create
refused with 401 has no `Location` to capture. `Executor` checked the step's error first,
so such a test was an `ERROR`. EARL recorded that as `cantTell` ("the harness could not
tell"), for a server that had answered, and answered wrongly. Against a secured server with
no identity configured, the core suite reported 21 of its 24 tests that way.

The failed assertion is the finding, so it now decides: `FAILED`, `earl:failed`. The bind
error stays on the step's record, and `Results.describe` and MCP `get_failures` now lead with
the assertion that decided. `ERROR` keeps its meaning for everything else, including a bind
error after every assertion in the step held. In that case the server met what the step
asked of it, and the harness cannot go on. A manifest that depends on a value the
specification requires should assert it (`Location: { present: true }`), so its absence
fails the test.

This changes per-test outcomes, not the conformance verdict. `FAILED` and `ERROR` count the
same way against conformance, in the reports, the exit code and `get_run`, so no run
changes between conformant and not. What changes is what the record says about the server:
`failed` rather than `cantTell` in EARL, a failure rather than an error in the JUnit XML,
and the counts in the summary. `definitions/EXECUTION.md` states the same rule for the
engine to come. `StepOutcomeTest` covers both halves.

**The MCP tools declare their hints.** Spring AI emits every tool's annotations, and with
none set, each tool advertised the protocol's worst case: not read-only, destructive,
open-world. Clients that honour the hints would ask before `coverage` or `get_report`. The
nine tools that read only the catalog, the manifests and recorded runs are now read-only,
idempotent and closed-world. `start_run` and `run_one` send deliberately malformed traffic
to a target, and create and delete resources on it, so they keep the cautious values, now
set explicitly: not read-only, destructive, not idempotent, open-world. The end-to-end test
checks all eleven as a client sees them.

### D-0050 — `mvnw` is executable in git, so CI and the Action's image build can run it
`mvnw` has been committed as mode `100644` since the Phase 0 scaffold (c3695dd). A Windows
checkout never notices. A Linux one does: `./mvnw` exits 126, "Permission denied".

**CI has never passed on GitHub.** D-0044 found that CI never ran on pushes to master. Once
it did, all three runs failed at "Build and verify", the `./mvnw -B -ntp verify` step. The
acceptance that D-0007 recorded as "local-green, remote pending" was never met remotely.

**The GitHub Action could not build its image either.** An Ubuntu runner's checkout keeps
the `644` mode, and `COPY` preserves it, so `RUN ./mvnw ... package` is refused. The step
before it hides the first refusal behind `|| true`. D-0046's end-to-end check was real but
ran on Windows, where Docker Desktop marks every file in the build context executable. A
`docker build` fed a `git archive` tarball keeps git's modes, which reproduces the runner
exactly: before this change it failed with `./mvnw: Permission denied`.

The fix is the mode bit and nothing else. With it, both checks were verified on Linux:
- the build: a clean `git archive` of merged master, on `eclipse-temurin:21-jdk`, ran
  `./mvnw -B -ntp verify` green with all 129 tests, plus the CLI smoke test;
- the Action's image: built from the same kind of tarball, then `--version` run in it.

No other tracked file has a shebang. The Python tools run as `python <script>`.

### D-0051 — definitions format 0.2.0 merges lws-test-suite's design (proposed)
Erich asked for a single YAML-LD design that combines Touchstone's format with
lws-test-suite's. 0.2.0 is that merge. It is proposed, not frozen, and lives on a branch
until the freeze review (DESIGN.md 7.4, as in D-0047).

**Taken from lws-test-suite:**
- **One request, one response.** A test that is one exchange carries `request` and
  `response` directly. This is pure shorthand for a single step.
- **Declared prerequisites.** `prereqs.hierarchy` with `authorization`: the state a test
  needs is declared and the engine establishes it, so a test's steps are only the exchanges
  it examines.
- **Its challenge terms.** `wwwAuthenticate`, `asUri` and `realm` replace `scheme` and two
  entries of `params`.

**Corrected in what was taken:**
- **Resources are named, not placed.** A prerequisite is `container: notes` or
  `dataResource: list`, bound to the URI the server assigns (WD section 9.2). Fixed paths
  are gone.
- **Access uses the draft's model.** The four actions are those of section 11.3.2, not the
  Solid modes `write`, `append` and `control`. Assignees are identities: `anonymous` means
  `foaf:Agent`. `Role-Authenticated` has no representation in the draft, since section
  11.3.3 requires a URI.
- **Challenge values are expectations.** Values such as `https://authorization.example`
  can never match a live server.

**Three defaults were chosen, because the decision was delegated.** Each is open at the
freeze review.
1. Access the storage's grant service cannot set up goes through the provisioning adapter.
   If neither can, the test is inapplicable.
2. The short form is pure shorthand.
3. Literal values are allowed, but the lint rejects RFC 2606 and RFC 6761 example hosts in
   executable values.

**Prerequisite failures are setup failures, not findings.** A failed create is *cantTell*,
and a grant the target cannot make is *inapplicable* (EXECUTION.md section 4.3). A test
whose traits include `Post` therefore keeps its create as a step, because there creation is
the finding.

**The migration was mechanical and proven.**
- **Scope:** 30 tests took the short form, 25 declare prerequisites, and 13 use the
  challenge shorthand.
- **Equivalence:** every test, desugared back into 0.1.0 steps under the 0.2.0 rules, equals
  its master text. The exception is `getContainer-public-read`, rewritten by hand to declare
  a public container as lws-test-suite's `getContainer` does.
- **Checks:** all six pass, with 16 documents and 5,933 triples. 20 schema negative controls
  are rejected, and the new lint rules fired on injected violations. COVERAGE.md regenerates
  unchanged, and the JSON-LD export trial is identical for 16 of 16 documents.

`definitions/COMPARISON.md` argues the merged design's superiority from measurements of
lws-test-suite's files at b8cb134. `docs/definitions.md` summarises it.

### D-0052 — the definitions checks live in tools/definitions and run in CI
The six checks of `definitions/README.md` and the JSON-LD export trial had run only from a
working directory outside the repository. So had the generators for `vocab.yamlld` and
`COVERAGE.md`. A generated file whose generator is not in the repository cannot be
maintained, and "CI should run" was a promise, not a fact.

**What moved.** They now live in `tools/definitions/`:
- the scripts: `validate_ld.js`, `portable_yaml.py`, `validate_schema.js`,
  `lint_definitions.py`, `gen_vocab.py`, `gen_coverage.py` and `export_dryrun.js`;
- a single entry point, `check.py`, and `fetch_anchors.py`.

The scripts find everything relative to the repository, and write intermediate output to a
git-ignored `build/`. Each generator has a `--check` mode, so CI fails when a generated file
is stale. The one-time scripts stayed out: the 0.2.0 migration, its equivalence proof, and
the generator that first wrote the authentication suites. D-0051 records their results.

**Anchors are data with a regenerator.** The lint checks every `source` anchor against the
section and heading ids of its dated snapshot, recorded in `anchors.json`.
`fetch_anchors.py` rebuilds that file from w3.org for every snapshot the definitions cite.
Snapshots are immutable, so it is refreshed only when a new one is cited.

**lws-test-suite is pinned.** The `mirrors` check and `COVERAGE.md` read that repository's
files. The CI job `definitions` therefore checks it out at
b8cb134fd2a4d18e8f4272532cf3715c95180dba, the commit `definitions/COMPARISON.md` was measured
on, and moving the pin means re-measuring. Its authentication manifests sit behind links a
checkout may not create, so the scripts fall back to their physical path. Locally, a
sibling checkout is found automatically; without one, the lint skips `mirrors` and says so.

**Versions**, checked against the registries on 2026-09-23:
- Node: `ajv` 8.20.0, `ajv-formats` 3.0.1, `jsonld` 9.0.0 and `yaml` 2.9.1, with a
  `package-lock.json`;
- Python: PyYAML 6.0.3 and rdflib 7.6.0;
- CI: Node 24 (the current LTS), Python 3.12, and `setup-node@v7` and `setup-python@v7`.

**`jsonld` 9 needed one change.** Its canonicalizer, `rdf-canonize` 5, caps deep comparisons
at n^maxWorkFactor, with a default of 1, as a guard against hostile input. The export trial
exceeded the cap on documents with repeated step structures. These are the repository's
own files, so the trial passes `maxWorkFactor: 2`. A negative control confirmed the trial
still reports a corrupted export, and reports only that document.

### D-0053 — definitions format 0.2.0 is frozen, with the three defaults accepted
Erich reviewed format 0.2.0 and asked for the three remaining steps, in this order:
freeze the format, build the engine that runs it, and retire `manifests/`. This entry is
the first. `definitions/README.md` said the engine should start from the frozen format,
so the freeze lands before any engine code.

**What is frozen:** `definitions/lws10/context.jsonld`, `vocab.yamlld`,
`schema/definitions.schema.json` (`$id` `…/definitions/0-2-0`) and `EXECUTION.md`. A
later change to any of them bumps the schema `$id` and needs its own entry. Test content
is not format: adding, correcting or retiring a definition needs neither, and every test
stays `status: Proposed`.

**The three defaults D-0051 left open are accepted as written:**
1. Access that the storage's grant service cannot set up goes through the target's
   provisioning adapter. When neither can set it up, the test is inapplicable
   (EXECUTION.md section 4.3).
2. The short form is pure shorthand for a test of one step (section 4.2).
3. Literal values are allowed, and the lint rejects RFC 2606 and RFC 6761 example hosts
   in executable values (section 2.5).

This closes the review gate D-0047 opened, the analogue of Gate 2 (D-0013).

### D-0054 — the YAML-LD engine runs the definitions, against a reference deployment of the 21 September draft
The second of the three steps (D-0053). `harness-core` now has an engine that implements
`definitions/EXECUTION.md` (format 0.2.0), and the CLI, the MCP server, the Docker image, the
GitHub Action and the self-test loop all run the definitions through it. The manifest executor
is gone (D-0055).

**The engine** follows the contract section by section:
- *Loading (section 2):* YAML 1.2 Core Schema with snakeyaml-engine 3.1.1 (Maven Central,
  checked 2026-09-23), refusing extra documents, duplicate keys, tags, anchors and tabs; the
  JSON Schema (a bundled copy, kept identical to `definitions/schema/` by a test); JSON-LD
  expansion with only the repository's two contexts, in safe mode through Titanium's
  undefined-terms policy; and the lint of section 2.5, plus 6.2's rule that a step setting
  Authorization itself runs as anonymous. The schema `$id` must be `…/0-2-0`.
- *Running (sections 3, 4, 6, 7, 10):* templates and derived variables, prerequisites and
  grants, the RFC 8288 and RFC 9110 parsers (strict: an unquoted URI is not a token, so it is
  not an auth-param), the expectations in the contract's order, cleanup bottom-up when
  recursive delete is refused.
- *Identities (section 5):* harness-issued access tokens (`as.signingKey`), tokens exchanged
  for a configured did:key (`didkey.jwk.<name>`), static tokens, open targets; every fault; the
  did:key, CID, OpenID Connect and SAML subject credentials; and a fixture host on the JDK's
  HTTP server, serving only the identity documents and the two OpenID Providers.

**Where the contract leaves a choice, the engine makes this one:**
1. Minting alice's token to create the run root needs `as.issuer` and `as.realm` before any
   test container exists, so provisioning probes the base URL for the challenge. Either way
   the values are derived once per run.
2. Tests run 16 at a time (`parallelism`). With all 101 in flight, the reference server's
   accept queue refused connections, and the run reported them as cantTell.
3. `service.<Type>` also matches a type given as the full LWS IRI.
4. Checks nested in `some`, `every` and `none` capture nothing: which element's value a
   capture would take is not something a definition can rely on.

**One verdict.** TODO's "Three verdicts" item is settled by the frozen format: each test has
one level, and section 9's verdict (no MUST test failed or ended cantTell) is the report's,
the exit code's and `get_run`'s alike. A run whose only failures are SHOULD or MAY tests now
exits 0, not 1. Outcomes are EARL's names (`passed`, `failed`, `cantTell`, `inapplicable`);
run records with the old `ERROR` and `SKIPPED`, and `manifestId`, still load.

**The reference deployment** (`harness-fixtures`):
- `RefAuthorizationServer` replaces `OidcIssuer`. It serves RFC 8414 metadata and a JWKS, and
  exchanges subject tokens (RFC 8693) after validating each the way its suite says: the
  did:key key decoded from the identifier (P-256, or Ed25519 through the existing fixtures),
  the CID key found by `kid` in the dereferenced document, the ID Token's provider found in the
  subject's document, and the SAML signature, audience and validity. This code is written
  apart from the engine's, so a shared bug cannot cancel out. Its broken twin exchanges
  anything.
- `RefLwsServer` follows WD-20260921: no 428 on an unconditional PUT; linksets stored with
  their own ETag, merge-patchable, 405 on PUT, removed with their resource; access grants and
  access requests as containers, and authorization by ownership or grant; member `size` and
  `modified`; the storage link on a 401; access tokens refused when they name two audiences
  or were issued in the future.
- `ReferenceScenario` wires them up and says how the harness is configured for them.
  `DefinitionsSelfTest` runs every definition four ways: secured (100 passed, and the
  notification test inapplicable), open (exactly the tests that need Authentication
  inapplicable), a broken authorization server (the 19 credential tests and the
  unknown-resource test fail, and nothing else), and a broken storage (its 14 access-control
  tests fail). The whole loop takes about three seconds.
- Notifications stay unimplemented (D-0041), so the reference advertises no notification
  service, and the test that checks one is inapplicable against it. A reference that
  advertised a service it does not provide would lie to that test.

**SAML without OpenSAML.** DESIGN.md section 4 pinned OpenSAML 5 for SAML, and D-0024 deferred
it because it comes from `build.shibboleth.net`, not Maven Central. The harness only has to
build, sign and alter assertions, and the reference only to verify one, so both use the JDK's
own XML Signature API (JSR 105): enveloped signature, exclusive canonicalisation,
RSA-SHA256, secure validation, and a check that the signature references the assertion
itself. The unused `opensaml.version` pin is removed.

**Dependencies.** snakeyaml-engine 3.1.1 is new; `nimbus-jose-jwt` (already pinned) is now also
a `harness-core` dependency; `jena-shacl` leaves `harness-core`, since the format has no SHACL
assertions (D-0055).

**Found while verifying, and fixed:**
- *Credentials in run.json.* A token-exchange test checks the access token it was issued,
  and the checked value went into the expectation's `actual`: 18 live tokens in one run.json.
  Expectation values are now redacted as traces are (DESIGN.md 7.2): a check on a credential
  member records that it held, and any JWT is blanked by its shape.
- *A registry that does not parse* ended in a stack trace; it is now one line and exit 2.
- *A stale shaded jar.* An incremental build re-shaded the previous fat jar and kept its old
  classes. CI builds clean; the docs say `clean install`.

**What changed for people who run it:** `run --module` defaults to `all` and takes a module, a
manifest or a test; `--manifests` is now `--definitions`; the `defaultIdentity` and
`provisioner` properties are gone, since alice does both, so the `vulcan` target's token now
comes from `TOUCHSTONE_TOKEN_ALICE`; the Action's `module` defaults to `all`, it gains a
`capabilities` input, and it passes `TOUCHSTONE_TOKEN_ALICE`/`_BOB` and
`TOUCHSTONE_WEBID_ALICE`/`_BOB` through by name; the MCP tools take the same selectors.

### D-0055 — `manifests/` is retired
The third step (D-0053). With the engine in place (D-0054), the YAML test manifests are
removed: `manifests/`, the frozen schema 1-1-0 and its documentation (`docs/manifest-schema/`),
the manifest loader and executor, and the graph and SHACL assertion engine.

- **Nothing is lost.** 32 of the 33 manifests have a successor among the definitions
  (`definitions/COVERAGE.md`, table 2). The 33rd, `core/put-unconditional-428`, tested a MUST
  the 21 September draft removed, so it failed conforming servers and has no successor.
- **Their ids stay meaningful.** `supersedes` still names them, and
  `tools/definitions/retired-manifests.txt` keeps the list the lint and `COVERAGE.md` check
  against. The files are in git history at 76256e4.
- **Graph and SHACL assertions leave with the format that had them.** DESIGN.md section 5.2
  lists graph isomorphism and SHACL shapes among the assertions the executor must support.
  Format 0.2.0 has neither, deliberately: no published LWS JSON-LD context defines the terms
  they would test (definitions/README.md, open question 7), so JSON pointers read responses
  as sent. They come back as a new format version once the context is published. JSON-LD
  parsing remains for one purpose: saying whether negotiated representations that differ in
  bytes carry the same graph.
- **The gate moves.** Gate 2 (D-0013) froze the manifest schema before test #1. Its
  successor is D-0053, which froze format 0.2.0 before the engine.

### D-0056 — Touchstone is W3C LWS only: the Solid references are gone
Erich's direction: no Solid mentions; Touchstone is purely a W3C LWS harness.

- **DESIGN.md** no longer lists the Solid Protocol among its inputs, or the Solid test
  harness and test corpus as prior art. Its prior art is the LWS test group's own suite,
  which Touchstone mirrors (D-0047). The self-test loop is described as it is built:
  against a reference LWS server and its broken twins (D-0054), not a Solid server. The
  no-DSL decision stands as it was, without the Solid framing.
- **`docs/harvest.md`**, the page on porting Solid tests, is removed, so D-0025's method
  page no longer exists. The test that was ported that way,
  `core/data_resources#post-to-non-container-405`, stands on the LWS draft and RFC 9110,
  and its comment says only that.
- **Comparisons with lws-test-suite** say what its access modes are, not where they come
  from: `write`, `append` and `control` are not actions the LWS draft defines, which is the
  point (COMPARISON.md, `docs/definitions.md`, a negative-control label).

**Left as they are:** earlier entries of this log that mention Solid (D-0006, D-0015,
D-0025, D-0030, D-0051) record what was true when they were decided, and the copies of the
W3C drafts in `catalog/sources/` are verbatim: the core draft itself says it draws on the
Solid Protocol, and the catalog's clause hashes depend on the text as published. The frozen
format files never mentioned Solid, so format 0.2.0 is unchanged.

### D-0057 — the catalog follows the drafts published as of 28 September 2026, and CI now watches /TR/
Erich asked for Touchstone to be compliant with the specifications current as of
28 September 2026. On that date W3C's `/TR/` served:

| Document | Latest version on 28 September |
|---|---|
| LWS Protocol 1.0 | WD 21 September 2026 |
| Authentication suite: CID | WD 21 September 2026 |
| Authentication suites: OpenID Connect, SAML 2.0, did:key | WD 3 August 2026 |

The editor's drafts had no normative change after the 21 September snapshots: the later
w3c/lws-protocol commits (to 9b03b32, 28 September) touch the did:key snapshot, READMEs and
the wiki. `lws10-notifications-webhook` and `lws10-index` exist only as editor's drafts, and
the LWS Vocabulary on `/TR/` is still the 14 July Note draft, so none of them is a baseline.
`https://www.w3.org/ns/lws/v1` is still a 404 (D-0040). lws-test-suite has no commit after
the pinned b8cb134 (D-0052).

The definitions and the reference deployment already followed the 21 September draft
(D-0047, D-0054). The catalog did not, which is the TODO item this entry closes.

**The core catalog is re-baselined on WD-lws10-core-20260921.** It holds **190
requirements** (was 191): 144 MUST, 24 SHOULD, 22 MAY. The 178 normative blocks whose text
did not change keep their entries: they were aligned with the new extraction by position,
so repeated blocks such as "This property is OPTIONAL." map one-to-one. Of the 13 blocks
that changed:
- *Five keep their slug with the new words:* `authz-metadata-subject-token-types`,
  `read-container-etag-type-links`, `update-content-vs-metadata-prefer-set-linkset`,
  `status-204-410-deletions` and `notification-activity-types-supported`. Three of these
  changes are editorial: a curly apostrophe, a citation, and "LWS resource" becoming
  "Storage Resource" (w3c/lws-protocol#234). The subject-token-types clause was reworded
  around its OPTIONAL metadata member, and the container-ETag entry no longer says the tag
  changes on membership modifications.
- *Two are renamed for what they now require:* `linkset-concurrency-controls` becomes
  `linkset-etag-get-head` (the MUST is now an ETag on GET and HEAD of a linkset), and
  `linkset-if-match-412-428` becomes `linkset-precondition-failed-412` (If-Match is no
  longer required, and 428 is gone; a failed precondition still MUST yield 412).
- *One is new:* `authz-metadata-subject-identifier-types` (SHOULD, w3c/lws-protocol#227).
- *Three are Approved seeds:*
  - `get-data-resource-content-range-etag` and `head-parity-with-get` keep their slug and
    their **Approved** status. The first now says "as defined in [RFC9110]"; the second
    drops ETag from its illustrative list, and "the same headers as GET" is unchanged.
  - `delete-updates-parent-listing-etag` becomes `delete-removes-from-parent-listing`,
    **still Approved**: the draft deleted "its ETag MUST be updated to reflect the change"
    and kept the rest word for word, so what remains is a subset of what was approved.
- *Two retired seeds have Draft successors.* `etag-on-get-head-conditional-304` was a MUST;
  its successor `conditional-requests-supported` is a SHOULD without the ETag MUST.
  `put-unconditional-rejected-428` is gone; the paragraph that held it now says only
  "Clients SHOULD use conditional requests", catalogued as the Draft entry
  `put-clients-use-conditional-requests`. Both successors state a different obligation from
  the one Gate 1 approved, so neither inherits Approved.

Two entries are retired with no successor, because their clauses lost every BCP 14 keyword:
`update-success-new-etag` (the SHOULD for a new ETag after an update) and
`status-201-etag-link-headers` (the MUST for an ETag on a 201). As in D-0040, retired
entries are removed, not marked.

**The Approved judgements are flagged for Erich**, as D-0040's was. Carrying Approved over
three seeds follows D-0040's rule (the reviewed obligation survives, only its wording
changed or a part was deleted); demoting any of them to Draft is a one-line change.

**The CID catalog moves to WD-lws10-authn-ssi-cid-20260921.** Its normative blocks are
identical to the 21 August draft's; the new text is a note that the suite also serves DID
subjects, because DID documents extend controlled identifier documents
(w3c/lws-protocol#233). Only the snapshot, the header and the `sourceDraft` IRIs change.

**The definitions now cite what they waited for.** Four tests carried notes saying they
would cite their entries once the catalog was re-baselined. They now do:
`conditional-get-304` cites `conditional-requests-supported`, `linkset-conditional-412`
cites `linkset-precondition-failed-412`, `delete-updates-parent-strong-etag` adds
`delete-removes-from-parent-listing` (the tag check still rests on RFC 9110), and
`authz-metadata-subject-identifier-types` cites its new entry. `getLinkset`, which already
checked the linkset's ETag, adds `linkset-etag-get-head`. Coverage is 132 of 231 (was 127
of 232). No definition asserts a clause the draft removed.

**Tooling.**
- `emit_candidates.py` took the dated draft URL as a constant (the 21 August draft). It now
  derives it from the extraction's file name, and an entry's own `created` overrides the
  curation file's, so a re-baseline keeps the date of every entry it does not change.
- **A weekly CI job, `Spec drift`,** closes the other open TODO item. D-0047 found the
  21 September draft only because someone looked. `tools/extractor/check_published.py`
  reads each catalog's `this-version` header, fetches the document's latest version from
  `/TR/`, and fails when W3C serves a newer dated version (including a change of maturity)
  or when `check_drift.py` finds a changed clause or a dead anchor. It is stdlib-only
  Python, like the rest of the extractor.

**Open: did:key was discontinued on 29 September.** The WG merged the discontinuation on 18
September (w3c/lws-protocol#229) and W3C published
`https://www.w3.org/TR/2026/DISC-lws10-authn-ssi-did-key-20260929/` the day after the date
Erich set. It says the CID suite "subsumes this specification by specifying a generalization
of the mechanism", and that it is "inappropriate to cite this document as other than
abandoned work". As of 28 September the 3 August WD was current, so the did:key catalog
(12 MUST) and the `auth/did_key` definitions stay as they are. Consequences if they are
retired or rebased on the Discontinued Draft:
- the definitions schema accepts only `WD-` URLs in `specification` and `source`, so citing
  a `DISC-` document is a format change (D-0053);
- did:key subjects remain reachable through the CID suite, whose 21 September note covers
  DID URIs, and through the core `subject_identifier_types_supported` metadata.

Until that is decided, the new `Spec drift` job fails on did:key, and it should: it reports
exactly this.

Verified: `clause_hash.py --check` passes on all five catalogs; `check_drift.py` reports no
drift and no dead anchor against the 21 September core and CID snapshots;
`tools/definitions/check.py` passes 7 of 7; `./mvnw clean verify` is green across five
modules, including the four-way `DefinitionsSelfTest` against the reference deployment.

### D-0058 — the did:key suite is retired; a did:key subject is tested under the CID suite (format 0.3.0)
D-0057 left the did:key suite open: W3C published it as a Discontinued Draft on 29 September
2026 (`https://www.w3.org/TR/2026/DISC-lws10-authn-ssi-did-key-20260929/`), because the CID
suite "subsumes this specification by specifying a generalization of the mechanism".
Erich's direction: retire it, since Touchstone is about compliance with the specification,
not its history.

**What is gone.**
- `catalog/lws10-authn-ssi-did-key.ttl` (12 MUST) and its snapshot and extraction. The
  catalog holds **219 requirements** in four modules: 173 MUST, 24 SHOULD, 22 MAY.
- `definitions/lws10/auth/did_key/`, and the root manifest's `include` and
  `specification` entries for it. No definition cites the did:key suite.
- The `Spec drift` job no longer fails: every catalogued document is current.

**What stays, and why.** did:key *identifiers* are not retired with the suite. The 21
September CID suite says it serves DID subjects as well as HTTPS ones, because DID
documents extend controlled identifier documents (its section 1), and core's
`subject_identifier_types_supported` names `did:key` as an example. A did:key subject also
matters in practice: its DID document is derived from the identifier, so it needs no trust
set up in advance and nothing the server under test must reach. The core token-exchange
tests use it for that reason, and so do real targets through `didkey.jwk.<name>`.

So the eight did:key tests **moved into the CID suite** as `authn-cid-didkey-*` rather
than being deleted. They cite CID requirements and CID source anchors. The valid credential
now also cites `validation-dereference-sub-cid` and `validation-kid-verification-method`,
since the verifier resolves the DID and takes the key `kid` names. It no longer cites the
did:key-only rules "sub must be a did:key URI" and "extract the key from the identifier".
The CID suite gains the three negative checks it lacked (an audience without the
authorization server, no `exp`, no `iat`). The definitions still number 101, and
lws-test-suite's two did:key tests keep their counterparts, so all 27 of its tests stay
accounted for. Moving them breaks file-for-file export for those two tests: lws-test-suite
still files them under `auth/did_key`.

**What a did:key credential is now.** Under the did:key suite, the key came from the
identifier and the JWT carried no `kid`. Under the CID suite, the verifier "MUST use the
kid ... to identify a verification method from the subject's controlled identifier
document". The did:key method derives one verification method, `did:key:z…#z…`. The
`didkey` identity is therefore a CID-suite identity, and its header carries
`kid: ${self.kid}`, the identifier's multibase value.

**This is a format change, so format 0.3.0.** `EXECUTION.md` section 5.3 defined the
credential per suite and had a did:key bullet. D-0053 says any change to the frozen files
bumps the schema `$id`.
- *What changed:* section 5.3 now has a single CID bullet with two cases. An identity with a
  `webid` has an HTTPS subject whose document the harness hosts, as before. An identity with
  no `webid` has a did:key subject: the engine derives it, sets `self.kid` to the
  multibase value, and hosts nothing.
- *What did not:* the schema's rules, the context and the vocabulary. The `$id` is
  `…/0-3-0` in both schema copies, `Definitions.SCHEMA_ID` and `FORMAT_VERSION`. A 0.2.0
  identity naming the did:key suite no longer resolves, which is why this is a minor bump
  and not a patch.
- *Target configuration:* `didkey.jwk.<name>` is unchanged.

**The reference authorization server follows.** `SubjectTokens` validates a did:key
subject the CID way: the `kid` must name the derived verification method, either as
`did:key:z…#z…` or as its fragment, before the signature is checked with the key the
identifier encodes. `SelfIssuedVerifier.verifyDidKey` does the same, and the fixture
negative matrix gains a credential whose `kid` names another key.

**DESIGN.md is left as the brief.** It still lists `auth-didkey` among the modules, as it
still describes `manifests/` (D-0055). The spec wins, and this entry records the deviation.

Verified:
- `./mvnw clean verify` is green across five modules. The four-way `DefinitionsSelfTest`
  still shows each broken twin failing exactly its tests: 19 credential tests against the
  broken authorization server.
- `tools/definitions/check.py` passes 7 of 7, with 27/27 lws-test-suite tests mirrored.
- `check_published.py` exits 0.
- The secured reference deployment passes all 23 `auth` tests over HTTP, from the CLI.
- *Negative control:* with the `kid` removed from the `didkey` identity, the reference
  refuses the credential. Exactly the three tests that exchange a valid did:key credential
  fail: `authn-cid-didkey-valid-credential`, `authz-token-exchange-valid` and
  `authz-issued-token-accepted-by-storage`.

## 2026-10-01

### D-0059 — three tests from langsamu/LWS.net: date validators and the storage root container
langsamu/LWS.net (a .NET runner of the lws-test-suite data, at `80d7beb`) carries three checks this
suite lacked. They are rewritten as definitions, not copied:

- **`core/conditional_requests#conditional-get-if-modified-since-304`** (SHOULD) and
  **`#conditional-get-if-unmodified-since-412`** (SHOULD), from its `ConditionalRequestTests`.
  The draft names date-based validators next to entity tags ("including mechanisms such as
  entity tags (ETags) and date-based validators (like If-Modified-Since headers)",
  `req:conditional-requests-supported`), and every conditional test here used entity tags only.
  LWS.net builds "before" and "after" by clock arithmetic and a two-second sleep; a definition
  has no date arithmetic, so the server's own `Last-Modified` stands for "at" and the Unix epoch
  for "before". No entity-tag validator is sent alongside, so RFC 9110 section 13.2.2 cannot
  let one take precedence.
- **`core/discovery#discovery-storage-root-is-container`** (MUST), from its manifest entry
  `storage-root-is-container`. That entry GETs `/` anonymously with no `Accept` and expects
  `rel="type"` Container, which contradicts its own `storage-content-type-is-lws-cid` (the same
  request must answer with the description) and draws a 401 from any private storage; it passes
  only against a storage run with `lws.dev.open=true`. What it reaches for is
  `req:storage-description-storageroot-service`: "a StorageRoot service whose serviceEndpoint is
  the URI of the storage root container". `discovery-storage-description` checked that the
  service is declared; nothing followed it. The definition follows `${service.StorageRoot}` as
  alice with lws+json. Whether alice may read the root is the deployment's grant, not the
  draft's, so that is a precondition step: a target registered on a sub-container alice cannot
  climb out of is inapplicable, not failed.

LWS.net's third check, `RangeRequestsTests`, is already covered by the three range definitions,
and its added `storage-content-type-is-lws-cid` is `discovery-storage-description-default-media-type`.

**The reference server follows** (the authoring rule: a test nothing can pass proves nothing).
`RefLwsServer` had no `Last-Modified`; it now sends one on 200, 206 and 304, at one-second
precision, and evaluates `If-Unmodified-Since` (412) only without `If-Match` and
`If-Modified-Since` (304) only without `If-None-Match`, ignoring an invalid date and an
`If-Modified-Since` later than now (RFC 9110 sections 13.1.3, 13.1.4, 13.2.2).

**The Python lint** knew three service types for `${service.*}`; `StorageRoot` joins them. The
engine and the Java lint already resolved any type, as EXECUTION.md section 3 says.

No catalog change: both requirements were already in it. The counts pinned in
`DefinitionLoaderTest`, `RunCommandTest` and `TouchstoneMcpEndToEndTest` move from 101 to 104.
Drafted by an agent; per the authoring rules it waits on branch `lws-net-tests` for review.

### D-0060 — coverage: the access data model, constraints, containment, problem details, the LWS JSON-LD profile
Seven definitions for requirements no test cited (catalog coverage 120 → 143 of 219: core MUST 82 → 102, core SHOULD 8 → 11), in two groups.

**The access data model** (`core/access_grants`):
- `access-grant-document-shape` (MUST): a stored grant has an `@context` ordered set including the
  LWS context, `type`, `storage`, and an `access` collection whose every policy has a type including
  `AccessPolicy`, an `action` collection, an `assignee` and a `target` object with `type` and `value`.
- `access-grant-incomplete-refused` (MUST): ten defective grants are each refused with 4xx: no type,
  no storage, no access, an empty access, a policy with no type or without `AccessPolicy`, no action,
  no assignee, a target that is not an object, an inbox that is not a URI.
  *The draft says nothing of the response to such a document*: it makes the properties REQUIRED
  and constrains their values, and the access protocol only says what a successful POST returns.
  A server that accepted one would then serve a grant that does not conform, so refusing it is the
  only conforming outcome; that is the reading this test enforces. It proves the refusals took no
  effect (each would otherwise give bob read access, and after them bob has none) and were not
  vacuous (a complete grant then gives it).
- `access-grant-constraints-all-satisfied` (MUST): two `dateTime` constraints, one met and one not,
  refuse bob; both met, permit him. Gated by a precondition on the grant service's `conformsTo`
  naming `lws#AccessProfile`, because the leftOperand obligation is conditional: "a server
  advertising support for this profile MUST support the following leftOperand values".

**Elsewhere in core**:
- `discovery-storage-description-ids-are-uris` (MUST): optional service and capability ids are URIs
  (a scheme is required, so a bare fragment fails), and `capability`, when present, is an array.
- `containment-hierarchy-consistent` (MUST): two containers deep, each child's `up` names its
  container and each container lists the child, back to the test container. `containment-no-cycles`
  stays uncovered on purpose: the draft defines no operation that moves a resource, so a client
  cannot even attempt a cycle, and a test that could not fail would prove nothing.
- `container-ld-json-lws-profile` (SHOULD): `application/ld+json; profile="https://www.w3.org/ns/lws/v1"`
  yields the container representation. The Content-Type is not asserted: answering lws+json or
  the profiled ld+json both treat the types as equivalent. (Not `connegEquivalent`, whose
  Content-Type check would demand the profiled type back.)
- `error-problem-details` (SHOULD): a 404 for an absent resource is `application/problem+json`, a
  JSON object whose optional `status`, if present, is 404. No new trait: the trait vocabulary is
  part of the frozen format, and `Get, DataResource` describes the exchange.

**The reference server follows.** `RefLwsServer` refuses access documents missing `storage`, with a
policy type lacking `AccessPolicy`, a non-object target or a non-URI inbox; advertises `conformsTo`
`lws#AccessProfile` on both access services; and evaluates constraints, all of which must hold:
`dateTime` against the clock, `client` against the access token's `client_id`, `format` and `type`
against the resource. The draft does not say how a request states its purpose, so a `purpose`
constraint is accepted and never satisfied: fail closed. An unknown leftOperand or operator is
refused at creation. Error responses carry RFC 9457 problem details (never on HEAD).
`TokenValidator.claims` exposes the validated claims for the client.

Pinned counts move from 104 to 111 tests (110 passed, 1 inapplicable against the reference) and
`core/containers` from 14 to 16. `access-grant-incomplete-refused` and
`access-grant-constraints-all-satisfied` join the tests that must catch the broken storage, which
forbids nothing: each fails there at the step where bob, who should be refused, reads with 200. Drafted by an agent; waits on branch `coverage-access-and-misc`.

### D-0061 — coverage: subscriptions, and the access, conditional and linkset MAYs
Nine definitions for requirements no test cited (catalog coverage 143 → 161 of 219), and two
existing tests cite requirements they already exercised.

**Subscriptions** (`core/notifications`), the first notification tests beyond discovery (D-0041
deferred the whole section). They use `WebhookSubscription`, the one subscription type whose request
shape is known (the draft's own example: `type`, `topic`, `inbox`), and each opens with a
precondition that the storage advertises it, so a storage without it is inapplicable, not failed.
- `subscription-create` (MUST): an lws+json POST to the NotificationService with `type`, `topic`
  (the test container) and `inbox` succeeds (any 2xx) with an lws+json body whose `type` equals the
  request's and whose `subscription` is a string. The inbox is `${storage}`: a public HTTPS URL that
  a server's outbound-request guard accepts. The test cancels the subscription before cleanup deletes
  the container, so nothing is delivered. A final DELETE of the subscription URL tidies up and is not
  judged (any 2xx or 4xx), because the draft defines no cancel operation and format 0.3.0 can register
  only a `Location` for cleanup.
- `subscription-missing-type-refused`, `subscription-unadvertised-type-refused`,
  `subscription-missing-topic-refused` (MUST): each is refused with 4xx.
- `subscription-unreadable-topic-refused` (MUST, needs Authentication): bob may read one resource.
  A control step, as a precondition, shows he may subscribe to it. Without that control a server
  that refuses bob everything would pass vacuously. Topics adding, or naming only, alice's private
  test container are then refused with 4xx. Cites the security consideration as well.

**Batch 2**:
- `access-grant-extra-properties-accepted` (MAY): a grant carrying an IRI-named property the draft
  does not define, with neither `inbox` nor `constraint`, is created and gives bob read access.
- `conditional-delete-stale-if-match-412` (SHOULD): a DELETE whose If-Match went stale after a PUT is
  refused with 412, and the resource survives.
- `linkset-ready-at-create` (MUST): the linkset named on the 201 is served at once (atomicity of
  metadata with creation). `linkset-removed-with-resource` now also cites
  `linkset-server-managed-atomic`.
- `linkset-up-not-redirected` (MUST, ValidationTest): a merge patch claiming a different parent
  may be refused or accepted (servers MAY restrict links), but afterwards `rel="up"` still names
  the real container and not the forged one, since containment fixes it.
- `linkset-advertises-patch` now also cites `metadata-capability-advertising`, the umbrella clause
  of the two headers it checks.

**Not defined, on purpose**:
- `policy-target-optional`: a grant without a target has no defined meaning (the whole storage?
  nothing?), so refusing one is defensible and accepting one is a guess.
- `prefer-link-relations-filtering`: the draft gives the Prefer URI but no syntax for naming
  relations.
- `authz-challenge-extra-params` and `read-container-listing-authz-filtered`: permissions a server
  cannot fail.

**The reference server follows.** `RefLwsServer` advertises a NotificationService with
`subscriptionType: ["WebhookSubscription"]` and implements it:
- POST validates the request (415 unless lws+json, 400 for a missing or unadvertised type, a missing
  or empty topic, or a non-URI inbox), refuses with 403 any topic the subscriber cannot read, and
  answers 201 with `Location` and the subscription document.
- The subscriber or the owner may GET or DELETE a subscription; to anyone else it is a 404.
- Nothing is delivered.

The run's residue includes subscriptions. Against the reference all 120 tests pass, including
`notification-service-advertised`, which was inapplicable. Pinned counts move from 111 to 120. The
broken storage subscribes anyone to anything, so `subscription-unreadable-topic-refused` joins the
tests that must catch it. Drafted by an agent; waits on branch `tests/subscriptions-and-metadata`.

### D-0062 — coverage: the core authentication data model, and pagination
Nine definitions and new citations on existing tests (catalog coverage 161 → 174 of 219).

**The core authentication data model.** Core 7.1 makes `sub`, `iss` and `client_id` REQUIRED in
every authentication credential, and requires credentials to be signed.
- `authn-cid-didkey-missing-subject`, `-missing-issuer`, `-missing-client-id` (MUST, `auth/cid`):
  each presents the did:key credential without one of the three claims at the token endpoint and
  expects 400 `invalid_request`. The CID suite's sub = iss = client_id rule refuses them as well; the
  test proves the refusal, whichever rule produces it.
  *No new fault term.* The fault vocabulary is part of the frozen format (0.3.0), so the identities
  `didkey-missing-sub`, `didkey-missing-iss` and `didkey-missing-client-id` are standalone CID
  identities. Their claims are written out in full, minus the one missing claim. They sign with the
  run's did:key key, as `didkey` does.
- `authz-metadata-subject-token-types-are-uris` (MUST): every advertised subject token type is a
  URI, since "each authentication suite MUST be associated with a token type URI".
- Citations only, for clauses those tests already proved:
  - `authn-credential-signed` on the eight bad-signature, `alg: none` and unsigned tests of the
    three suites;
  - `authn-client-claim` on `authn-oidc-missing-azp`;
  - `authn-audience-restriction-recommended` on `authn-cid-didkey-audience-excludes-as`.

**Pagination** (`core/pagination`, a new module). The threshold is the server's, and format 0.3.0
can neither read a page size from the target nor create members in bulk. Each test therefore builds
a container with five members and reaches every page through links the server gave.
- When the first page carries `rel="next"`, the container really spans pages, and four tests apply:
  - `pagination-first-page` (MUST): `first` present, `prev` absent, and `id`/`type` describe the
    container;
  - `pagination-next-page` (MUST): the next page is 200, keeps `first`, describes the same container,
    and does not repeat page one's first member;
  - `pagination-last-page` (MUST, also needs the optional `last`): `first` present, `next` absent;
  - `pagination-totalitems-all-pages` (SHOULD): `totalItems` is 5 on the first and the next page.
- `pagination-single-page` (MUST): a server may present the whole container as one paginated page,
  with `rel="first"` and all five members. That page is first and last, so `next` and `prev` are
  both absent. The first draft of these tests treated `rel="first"` alone as "spans pages" and
  failed Halcyon, which marks every listing with `first`. That reading was wrong, so the gate is
  `next`.
- Not defined: `pagination-over-threshold` (needs the threshold) and `pagination-uris-opaque` (an
  obligation on clients).

**The reference server follows.** `RefLwsServer` pages container listings at four members:
`?page=N`, links `first`, `prev`, `next` and `last`, an entity tag per page, and `totalItems` for
the whole container. No other definition lists more than four members. Against the reference
128 of 129 pass; `pagination-single-page` is inapplicable there by construction. The broken
authorization server exchanges anything, so the three new credential tests join the auth negative
tests that must catch it (20 → 23). Pinned counts move from 120 to 129.

Against our deployments, Halcyon (fixed page size 100) passes `pagination-single-page`. lws-server
(page size 1000) lists the five members without pagination links, so pagination does not apply
to it. Drafted by an agent; waits on branch `tests/authn-and-pagination`.

### D-0063 — the Access Profile's leftOperands, each tested
D-0060 tested only `dateTime`. "A server advertising support for this profile MUST support the
following leftOperand values: client, format, type, purpose, dateTime." Four definitions in
`core/access_grants`, each gated on the grant service's `conformsTo` naming `lws#AccessProfile`:
- `access-grant-left-operands-accepted` (MUST): five grants, each constrained by one leftOperand
  with the operator the draft's example uses for it (client `eq`, format `isAnyOf`, type `eq`,
  purpose `isAnyOf`, dateTime `lteq`), are each created with 201. Refusing one is not supporting
  it. This is the only test for `purpose`: the draft does not say how a request states its
  purpose, so no definition can check that the constraint is enforced.
- `access-grant-constraint-format` (MUST): one grant on a text/plain and a text/csv resource,
  constrained to `isAnyOf [text/plain, application/json]`; bob reads the first, not the second.
- `access-grant-constraint-type` (MUST): one grant on a container and a data resource, constrained
  to `eq lws#DataResource`; bob reads the data resource, not the container.
- `access-grant-constraint-client` (MUST): a grant limited to a client identifier bob's client does
  not have lets him read nothing. An unconstrained grant then lets him read, so the refusal came
  from the constraint and not from bob. The positive half (his own client allowed) is not testable:
  a definition cannot know the client identifier in bob's token.

The reference server already evaluates all five (D-0060). The broken storage forbids nothing, so
the three enforcement tests join those that must catch it. Pinned counts move from 129 to 133.

Halcyon fails three of the four. Its grant service advertises the Access Profile but answers 422
to format, type and purpose constraints ("the enforceable constraints are client-eq and dateTime"):
a MUST failure, which these tests make visible. lws-server passes all four. Drafted by an agent;
waits on branch `tests/access-constraints`.

### D-0064 — deeper tests on requirements already cited
Five definitions that test further into requirements other tests already cite (catalog coverage
174 → 176 of 219):
- `delete-container-recursive-deep` (MAY, `core/containers`): a recursive DELETE of a tree two
  containers deep removes the inner container, the data resource and that resource's linkset, and
  the test container stops listing the tree. `delete-container-recursive` checks only one level.
- `container-conneg-weighted` (MUST, `core/containers`): `Accept: text/html;q=0.9,
  application/ld+json;q=0.5` gets 200 with Content-Type `application/ld+json`. ld+json is among the
  types requested, which the server "MUST honor", so 406 is wrong.
- `create-post-twice-distinct` (SHOULD, `core/data_resources`): the same body POSTed twice creates
  two resources at distinct URIs, and both are readable ("POST is not idempotent"). The draft
  defines no Slug header, so none is sent.
- `linkset-patch-stays-linkset` (MUST, `core/linksets`): a merge patch replacing the `linkset`
  array with a string may be refused or repaired, but the linkset afterwards is still an RFC 9264
  document.
- `subscription-after-revocation-refused` (MUST, `core/notifications`): bob, granted read, may
  subscribe; once alice revokes the grant, a new subscription is refused. Two preconditions keep
  failures attributed to the right test: the first subscription (otherwise the refusal proves
  nothing), and bob losing read access (`access-grant-revoke` owns that).

Considered and not defined:
- **Who may read access requests:** the draft has no normative rule, so a test would be opinion.
- **Concurrent creates:** format 0.3.0 runs a test's steps strictly in sequence.
- **HEAD parity for containers:** already `container-head-parity`.

Pinned counts move from 133 to 138, and `core/containers` from 16 to 18 (11 MUST). The reference
passes all five. The broken storage revokes nothing it never enforced, so
`subscription-after-revocation-refused` is inapplicable there, not failed. Halcyon and lws-server
pass all five. Drafted by an agent; waits on branch `tests/depth-batch2`.

### D-0065 — format 0.4.0: polling and a per-test inbox, for notification delivery
Notification delivery was the last large untested part of lws10-core: about 25 requirements, 16
of them MUSTs, including the two delivery-time authorization MUSTs D-0041 singled out. Two things
blocked it, and format 0.3.0 could not express either:
- a server delivers asynchronously, so a test must wait for something to arrive;
- the test needs a delivery target the server can reach and the harness can read.

The fixture host became reachable from our targets once the harness moved to ebremer.com, which
removed the practical obstacle; this decision removes the format one.

**Format 0.4.0** adds three things and changes nothing a 0.3.0 definition relies on, so every
existing definition is valid unchanged:
1. **`poll: {within, every}` on a step** (EXECUTION.md section 4.4).
   - The step is re-sent every `every` seconds until its expectations hold, or until `within`
     seconds (at most 120) have passed since the first attempt. Then the last attempt is judged
     as any step is.
   - Only the attempt that is judged leaves captures or cleanup registrations behind; the engine
     undoes a failed attempt's before retrying.
   - Polling can wait for something to appear but cannot prove it never will. A test that needs
     an absence polls for a later event that would have to follow it, then asserts the absence
     with `none`.
   - Considered and rejected: engine-side polling with no format change. It is behaviour another
     implementation of the format could not know about, and the contract would no longer say what
     a step does.
2. **`${test.inbox}`**, a per-test URL on the fixture host, defined only with ReachableFixtures.
3. **The inbox** (section 5.4): POSTs to an open inbox are recorded (202); GET returns them as
   JSON.
   - Each delivery is recorded with its method, its headers (credentials redacted), its body
     parsed as JSON, and `activities`, the body's `activity` normalized to an array. That last one
     spares tests from depending on whether a server batches, which the data model leaves to it.
   - Limits: only running tests' inboxes are open; 1 MiB per body; 100 deliveries per inbox. The
     record is discarded when the test ends.

Schema `$id` `…/0-4-0`, `Definitions.FORMAT_VERSION` 0.4.0. The context and vocabulary gain
`poll`, `within`, `every` and the class `lwst:Polling`.

**Seven definitions** (catalog coverage 176 → 196 of 219):
- `notification-delivered-create` (MUST, ReachableFixtures only): a Create in a subscribed container
  arrives, and its delivery has the full 10.2 data model: envelope `type`, `storage` (equal to the
  storage's URI), `activity`; activity `id`, `type` array, `object.id` and `object.type` array, and
  an RFC 3339 `published`.
- `notification-delivered-update` and `notification-delivered-delete` (MUST): the other two
  activity types a server MUST support.
- `notification-not-delivered-for-unreadable-resource` (MUST, NegativeTest): bob subscribes to a
  container he may read; alice creates a member he may not, then updates one he may. Once the
  Update arrives, nothing may ever have named the unreadable member. A precondition checks that bob
  cannot read the new member, since a server whose container grants reach members gives him it.
- `notification-stops-after-revocation` (MUST, NegativeTest): bob subscribes to two resources, each
  readable through its own grant. An Update to the first arrives; alice revokes that grant and
  deletes the resource, then updates the second. Once the second Update arrives, the Delete must not
  have been delivered. A server that ends the whole subscription instead (a SHOULD NOT) makes the
  test inapplicable rather than failed.
- `notification-actor-omitted` (SHOULD): delivered activities carry no `actor`.
- `access-grant-inbox-notified` (SHOULD, `core/access_grants`): a grant naming an inbox is
  announced there with a well-formed Notification.

Inbox reads go out as `anonymous`, so no storage credential is sent to the fixture host.

**The reference server follows.** `RefLwsServer` POSTs an lws+json Notification to each subscriber
whose topic covers a created, updated or deleted resource (a container topic covers what is inside
it), if the subscriber may read the resource at the event (before removal, for a Delete). It sends
no actor, and also announces a new grant at the grant's inbox. Delivery is one attempt, off the
request thread.

**A new broken twin, `BROKEN_NOTIFICATIONS`.** The two delivery-time authorization tests cannot
fail against the broken storage: it lets bob read everything, so their "bob cannot read it"
preconditions do not hold, and they end inapplicable. `RefLwsServer.startLeakingNotifications` is
the compliant storage with one defect, delivery without the read check. The self-test requires
exactly those two tests to fail against it.

Pinned counts move from 138 to 145; against the reference 144 pass and `pagination-single-page`
stays inapplicable.

**Against our deployments** (run from ebremer.com):
- **Halcyon** passes all seven.
- **lws-server** first failed the Update and revocation tests, which was the definitions' fault:
  their PUTs were unconditional, and lws-server answers those 428. The draft dropped that MUST, but
  "clients SHOULD use conditional requests", and every other definition's PUT already sent
  `ifMatch: current`; now these do too. With that, lws-server passes six.
  `notification-not-delivered-for-unreadable-resource` is inapplicable there by design: lws-server's
  read access on a container reaches its members.

Drafted by an agent; waits on branch `format-0.4.0-delivery`.

### D-0066 — the webhook notification suite: catalogued from an editor's draft, tested through the inbox (format 0.5.0)
`lws10-notifications-webhook` defines the one subscription type our servers implement, and how a
delivery is sent and signed. It is an editor's draft, "an unofficial proposal", which W3C has not
published (`/TR/lws10-notifications-webhook/` is 404 on 2026-10-02).

**The catalog** (`catalog/lws10-notifications-webhook.ttl`): 20 requirements, 14 MUST, 1 SHOULD,
5 MAY, all Draft. The catalog grows from 219 to 239.
- *Snapshot.* `catalog/sources/ED-lws10-notifications-webhook-20261002.html` is the editor's draft
  rendered by W3C's spec generator. That renderer expands the ReSpec macros as publication would,
  which D-0042 requires of catalog text. Its normative sentences are those of `w3c/lws-protocol`
  `4e9481c` (2026-09-21), checked against the local source.
- *Extraction.* `extract_clauses.py` found 20 blocks. The BCP 14 boilerplate is skipped, and the
  block that pairs the server's SHOULD sign with the inbox's MUST verify is split into two entries.
  Three entries are receiver or subscriber obligations, and no server test cites them except as
  context: `inbox-verifies-signature`, `receiver-verification-steps` and
  `per-subscription-inbox-urls`.
- *Drift.* The header names `editors-draft:` and `shortname:` instead of a `this-version`.
  `check_published.py` now reports such a catalog as moved the moment `/TR/<shortname>/` exists,
  and otherwise re-renders the editor's draft and runs `check_drift.py` against it.
- *Sources.* Definitions cite `https://w3c.github.io/lws-protocol/lws10-notifications-webhook/#…`.
  The schema's `source` and `specification` patterns admit that form, and `anchors.json` records
  its anchors from the snapshot.

**Format 0.5.0.** Two additions, neither changing anything a 0.4.0 definition relies on:
1. The inbox record (EXECUTION.md section 5.4) gains `contentDigest` and `signature`: what the
   fixture host finds in the delivery's Content-Digest (RFC 9530) and HTTP Message Signature
   (RFC 9421).
   - The signature fields: label, covered components, `created`, `keyid` and `alg`; whether the
     keyid's key is published and referenced from `authentication`; whether the description's
     `id` matches; and whether the signature verifies.
   - The signature base is rebuilt from `${test.inbox}` as the server was given it, not from the
     proxied request.
   - The fixture host fetches a storage description only from the target's own host, since a
     keyid is whatever the sender wrote (section 10).
   - It records facts and judges none, so tests assert on them with ordinary `json`
     expectations, and no new expectation type was needed.
   - Algorithms: `ecdsa-p256-sha256` (Halcyon), `ed25519` (lws-server), `rsa-pss-sha512` and
     `rsa-v1_5-sha256`.
2. The schema admits the editor's-draft URL form in `source` and `specification`.

They were first folded into 0.4.0, which D-0065 had frozen the same day and which had not been
pushed when this work began; it was pushed while the work was in progress, so the additions
became 0.5.0 before they left this machine: schema `$id` `…/0-5-0`, `FORMAT_VERSION` 0.5.0.

**Nine definitions** (`notifications/webhook/manifest`):
- `webhook-subscription-response`, `webhook-subscription-expires-supported`,
  `webhook-subscription-listed` and `webhook-subscription-get-delete` (MUST);
- `webhook-delivery-lws-json` (MUST);
- `webhook-delivery-signed` (SHOULD);
- `webhook-signature-components`, `webhook-signing-key-published` and
  `webhook-signature-verifies` (MUST). These three apply only to a signed delivery: signing is a
  SHOULD, and the MUSTs bind a server that signs.

**The reference server follows.**
- It signs every delivery with ES256 over the six required components, with `created` and
  `keyid` `<storage>#notify-key`, and publishes that key as a JsonWebKey verification method
  referenced from `authentication`.
- It lists a caller's subscriptions as an LWS container at the NotificationService endpoint, and
  accepts and echoes `expires`.
- It serves the `application/lws+cid` storage description anonymously when it is asked for
  explicitly, as a third-party receiver must be able to fetch it. Halcyon and lws-server already
  do. The root container's listing, and a request with no Accept, still meet the challenge.
- The BROKEN_NOTIFICATIONS twin also signs with a key it does not publish. The self-test requires
  exactly the two delivery-authorization tests and `webhook-signature-verifies` to fail against it.

Pinned counts move from 145 to 154.

**Against our deployments** (run from ebremer.com, 2026-10-02):
- **Halcyon** passes all nine.
- **lws-server** passed eight, and failed `webhook-signature-verifies` because no delivery
  arrived. Its log says why: "Dropping delivery … already 4 in flight to ebremer.com
  (`lws.webhook.max-in-flight-per-host`)".
  - Touchstone runs tests in parallel, and every inbox is on the one fixture host, so a fifth
    concurrent delivery to it is discarded, not queued.
  - The suite allows retries and deactivation (MAYs), and no clause promises delivery under
    load, so dropping is not itself a conformance failure.
  - But a test cannot tell a dropped delivery from a broken one, and lws-server's ed25519
    signatures verified in the other tests that received one.
  - Left for a decision, not worked around in the definitions: raise the limit on the test
    deployment, or have lws-server queue past the limit instead of dropping.

Drafted by an agent; waits on branch `webhook-suite`.

### D-0067 — the search and type index services: catalogued from an editor's draft (format 0.6.0)
`lws10-index` defines the Type Index Service (a GET listing the types in a storage) and the Type
Search Service (an HTTP QUERY, RFC 10008, whose body is an `application/lws-query+json` filter).
Like the webhook suite (D-0066) it is an editor's draft, "an unofficial proposal", that W3C has
not published. Both of our servers implement it.

**The catalog** (`catalog/lws10-index.ttl`): 57 requirements, 41 MUST, 5 SHOULD, 11 MAY, all
Draft. The catalog grows from 239 to 296.
- *Snapshot.* `catalog/sources/ED-lws10-index-20261002.html` is the editor's draft rendered by
  W3C's spec generator, as for the webhook suite. Its normative sentences are those of
  `w3c/lws-protocol` `3039b37` (2026-09-21), checked against the local source.
- *Extraction.* `extract_clauses.py` found 28 normative blocks besides the BCP 14 boilerplate.
  Most hold several obligations, so entries are sentences or clauses of a block, which
  `check_drift.py` matches by containment. The one OPTIONAL clause (the `type` key) is filed
  under MAY, as the webhook catalog files its OPTIONAL fields. Five entries are client
  obligations and no server test cites them.
- The GET section of the Type Index Service has no BCP 14 keyword, so the `TypeIndex` body
  shape is asserted only alongside cited requirements, never cited on its own.

**Format 0.6.0.** Two additions, neither changing anything a 0.5.0 definition relies on:
1. `QUERY` joins the request methods (EXECUTION.md section 6). It carries a body like POST and
   is safe, so it may be polled.
2. A data-resource prerequisite may carry `linkHeaders`, sent on the POST that creates it
   (section 4.3). The draft says servers SHOULD derive types from `Link rel="type"` and MAY
   derive them from content, so a test resource has to declare its types both ways to be
   typed on any server that does either.

**28 definitions** (`index/manifest`), each applying when the storage advertises the service
it uses:
- discovery: `index-services-advertised`;
- type index: `type-index-lists-readable-types`, `type-index-omits-unreadable-type`,
  `type-index-not-shared`;
- search semantics: `type-search-by-type`, `-and-or`, `-native-classes`, `-no-match`,
  `-duplicate-groups`, `-at-members-ignored`, `-empty-key-absent`, `-unindexed-relation`,
  `-structural-relation-not-indexed`;
- errors: empty group, type not an array, bad element, malformed JSON, relative IRI, missing
  Content-Type (400); unsupported format (415, and the SHOULD that it carries Accept-Query);
  `Accept` excluding lws+json (406); Accept-Query on OPTIONS (SHOULD);
- authorization: `type-search-authorization-filtered` (and `totalItems`),
  `type-search-revoked-not-shown`, `type-search-not-shared`;
- derivation: `type-search-type-from-link-header` (SHOULD), `type-search-type-from-content`
  (MAY).

Design points:
- Types are `${test.container}#Alpha` and the like: absolute IRIs unique to the test, so a
  search finds only the test's own resources and never needs to page.
- Membership "MAY be eventually consistent", so the first wait for a typed resource is
  polled, and it is a precondition. A server that derives no declared type (which the
  SHOULD and the MAY allow) makes the tests that need one inapplicable, not failed. The two
  derivation tests report which route a server lacks.
- Authorization filtering "MUST NOT" be eventually consistent, so the search after a
  revocation is not polled.
- Not tested: the 422 complexity limit (a server chooses its own), expired page links (a test
  cannot make one), and `Vary: Accept` (only binding on a server that negotiates).

**The reference server follows.** It advertises both services and derives types from
`Link rel="type"` and from `<> a <…>` statements in a Turtle body (that one form, not Turtle
at large). Both services are filtered by current authorization on every request, mark their
responses `private, no-store`, index no relations, and answer in one page. The search applies
every error rule above, refusing more than 32 groups with 422. Against BROKEN_STORAGE, which
forbids nothing, `type-index-omits-unreadable-type` and `type-search-authorization-filtered`
fail, as they should.

Pinned counts move from 154 to 182.

**Against our deployments** (run from ebremer.com, 2026-10-02):
- **lws-server** passes all 28.
- **Halcyon** passes the 14 that need no typed resource, and fails both derivation tests.
  Its 12 tests that need a typed resource are inapplicable, because Halcyon surfaces no type
  a client declares:
  - It does not read `Link rel="type"` on create (the SHOULD); it uses that link only to pick
    a container's interaction model.
  - Its metadata scanner gives a stored `text/turtle` document to Jena's RDF reader through a
    blob path with no extension, and Jena refuses it ("Failed to determine the RDF syntax
    (.lang or .base required)"). So types stated in content (the MAY) are never indexed
    either.

Drafted by an agent; waits on branch `index-suite`.

### D-0068 — the index suite, deeper: changes, negotiation, safety, paging
Five more definitions in `index/manifest`, citing requirements D-0067 catalogued:
- `type-search-reflects-update` and `type-search-reflects-delete` (SHOULD,
  `reflect-change-bounded`):
  - a resource of type Alpha is replaced by one of type Beta, declared by both Link and
    content; or it is deleted;
  - a search on the new type comes to find it, and one on the old type comes to omit it. Both
    are polled, since the clause allows a bounded delay.
- `type-search-vary-accept` (MUST, `vary-accept`):
  - it applies when the search answers `Accept: application/ld+json` in that format;
  - then both that response and the default one must list Accept (or `*`) in Vary.
- `type-search-safe` (MAY, `query-safe-idempotent`): the resource and its container keep their
  ETags across two identical searches, and both searches find the resource. The test is
  filed at MAY because that is the catalog level of the clause, whose only keyword is the MAY
  of repetition; the safety half is what is asserted.
- `type-search-next-page` (MUST):
  - five resources share type Alpha; when the result spans pages, the next page (by GET on
    the rel=next URI) is a ContainerPage of Alpha resources that does not repeat page one's
    first item;
  - it applies only when the page holds fewer than five, so it is inapplicable on both of our
    servers (100 a page) and runs against the reference server;
  - it cites the core `pagination-link-first`, since the index draft states the paging
    model without a keyword.

**The reference server follows.** Its search pages at `PAGE_SIZE` (4), with page links of
the form `?q=<base64url filter>&page=N`, dereferenced with GET and holding no state. It
answers `application/ld+json` to a client that asks for it and not for lws+json.

Not written: a cross-check that the type index and the search agree for one client. It
restates `type-index-lists-readable-types` and `type-search-authorization-filtered` without a
clause of its own.

**Against our deployments** (2026-10-02): Halcyon (`c6435d0`) and lws-server (`e65a4dc`) each
pass 32 of the 33 index tests, with `type-search-next-page` inapplicable.

Drafted by an agent; waits on branch `index-followups`.

### D-0069 — the index suite, round three: relations, complexity, page links, grammar
Five more definitions in `index/manifest`, plus one step on an existing one. They cite five
requirements no test cited before.

- **Relations:**
  - `type-search-relation-filter` (MAY, `relations-may-be-indexed`): an Alpha resource whose
    linkset gains `describedby` a shape, through a merge-patch of its linkset, is found by
    `{"type":[Alpha],"describedby":[shape]}`.
  - `type-search-relation-cnf` (MAY): two resources, describedby shape one and shape two;
    `[[one, two]]` finds both and `[one, two]` neither. It applies when describedby is indexed.
    It is filed at MAY because the draft states these semantics inside the MAY that lets a
    server index relations.
  - `type-search-relation-from-link-header` (SHOULD, `relation-sources-identical` with
    `types-from-link-headers`): once the linkset route works, a resource that declared the same
    describedby as a Link header when created should be found too. Relation targets "are
    derived by the server in the same way as types", and deriving types from a create's Link
    headers is a SHOULD.
  - The linkset is the primary route because both servers index relations from it. A first
    draft used Link headers on create and found neither server indexing them (see below).
- **Complexity:** `type-search-too-complex` (MUST, `too-complex-422` and
  `no-silent-narrowing`):
  - type `[Alpha]` followed by 99 types nothing bears must be refused with 422, or answered
    without the Alpha resource;
  - a server that dropped the groups past its limit would return the Alpha resource;
  - both servers cap a filter at 32 groups, so the 422 path runs.
- **Page links:** `type-search-unrecognized-page-link` (MUST, `expired-page-404-410`):
  - a test cannot make a link expire, so it alters a search's rel=first link and expects 404
    or 410;
  - an altered link is one the server "no longer recognizes" in the plainest sense, though it
    was never issued as such. The comment says so.
- **Grammar:** `type-search-bad-element-rejected` gains an object element, the shape an
  extension would take, and cites `grammar-not-extended`.

**The reference server follows.** Its search indexes descriptive relations from Link headers
on create and update, and from the resource's linkset, treated alike. Structural and protocol
relations (up, type, linkset, acl, the paging relations, storage) are never indexed. A
linkset target that is no URI matches nothing; this was found when a malformed draft
definition made it answer 500.

**Against our deployments** (2026-10-02): Halcyon (`c6435d0`) and lws-server (`e65a4dc`) each
pass 36 of the 38 index tests. Each fails `type-search-relation-from-link-header`, and
`type-search-next-page` is inapplicable. Both servers store a create's descriptive Link
headers in the linkset only when the request also says `Prefer: set-linkset`. That is a
deliberate design: lws-server's comment calls it the way a client replaces its whole metadata
document from headers. It records `rel="type"` regardless. So the failure reflects a reading
of the draft, not an accident. If the working group meant only `type` to come from Link
headers, this test should be withdrawn.

Drafted by an agent; waits on branch `index-round3`.

### D-0070 — format 0.7.0: a scripted inbox, for webhook retry and deactivation
The webhook suite's two remaining server MAYs, "Retry: On failure, the server MAY retry
delivery" and "Expiration: After repeated failures, the server MAY deactivate the
subscription", need an inbox that fails on purpose. Until now every inbox answered 202.

**Format 0.7.0.** One addition, changing nothing a 0.6.0 definition relies on:
- A PUT of `{"respond": [s1, s2, ...]}` to `${test.inbox}` scripts the deliveries that follow.
  The next is answered s1, then s2, and the last status answers every later delivery.
  Statuses are integers 200–599, at most 10; anything else is 400, and an inbox not open is
  404 (EXECUTION.md section 5.4).
- Each delivery record gains `status`, the status it was answered with.
- Tests send the PUT as `anonymous`, before they give the server the inbox. A server under test
  could PUT to the inbox it was given, but that only disturbs its own test.

**Two definitions** (`notifications/webhook/manifest`), both MAY:
- `webhook-delivery-retried`: the inbox answers 503 once and 202 after. One update to the
  subscribed resource must reach the inbox twice: answered 503, then 202.
- `webhook-subscription-deactivated`: the inbox answers 410 to everything, and the resource is
  updated five times. The subscription must come to be gone or inactive: 404 or 410 (a problem
  document saying so), or 200 with `"active": false`.
  - The suite does not say how deactivation shows, and our servers differ. Halcyon removes the
    subscription after five consecutive failures; lws-server keeps it with `"active": false`,
    at once on 410.
  - A first draft accepted only 404/410, and so failed lws-server for a deactivation it had
    made.
  - Five updates is Halcyon's threshold. A server with a higher one, lws-server's own default
    of 10 failures for statuses other than 410 for instance, would need more; 410 is what
    makes the test reach it.

**The reference server follows.**
- It retries a 5xx or an unreachable inbox up to three times, a second apart, re-signing each
  attempt.
- It deactivates a subscription at once on 410, or after five consecutive failed deliveries,
  by removing it.

**Against our deployments** (2026-10-02): Halcyon (`c6435d0`) and lws-server (`e65a4dc`) both
pass both tests, and all 11 webhook tests.

Drafted by an agent; waits on branch `webhook-retry`.

### D-0071 — a protected Content-Location for search results; 401 discoverability cited
One definition and two citations (catalog coverage 261 → 263 requirements cited):
- `type-search-content-location-protected` (MUST, `index`). RFC 10008 lets a QUERY response name
  a resource representing its results with Content-Location. lws10-index defines none, but "a
  server that nonetheless exposes one MUST subject it to the same authorization filtering on every
  access and MUST prevent its reuse across clients". alice searches for a type only her private
  resource has. If the response carries Content-Location (otherwise inapplicable), bob fetching it
  must not see her resource. He may be refused, answered empty, or given what he may read.
  *How bob's answer is read.* A refusal may carry any body, or none, so the step does not parse
  JSON (a non-JSON 404 would fail every `json` expectation). Instead `bodyMatches` requires the
  body to lack this test's container path segment, `type-search-content-location-protected/`.
  A precondition makes sure every one of alice's results contains that segment, so the check cannot
  pass vacuously on a server that ignores the container's Slug hint.
- `getContainer-private-unauthorized` now also cites `read-401-www-authenticate-discoverability`
  (SHOULD), whose "WWW-Authenticate ... parameters to guide clients without hardcoded URIs" are the
  `as_uri` and `realm` it already checks. `discovery-unauthorized-response-headers` cites it for the
  clause's "metadata links SHOULD be included", the storage link on a 401.

**The reference server follows.** Its Type Search now names its first page link as the
Content-Location of a QUERY response. That link is stateless, the filter encoded in it, and every
GET re-runs the filter for whoever asks, which is the same filtering on every access. The broken
storage filters nothing, so bob reads alice's resource there, and the new test joins those that
must catch it. Pinned counts move from 194 to 195.

**Not defined:**
- The access notifications: the draft does not say how a grant is associated with the access
  request whose inbox it names, nor where the storage controller's inbox is.
- Notification batching (a MAY a server cannot fail).

The README's "Not yet defined" no longer says the webhook suite is uncatalogued or problem details
undefined. Drafted by an agent; waits on branch `tests/small-batch`.

### D-0072 — webhook-subscription-listed lists bob's own subscriptions, and pages honestly
`webhook-subscription-listed` subscribed as alice, the storage owner, and expected the new
subscription on the first page of the listing. Two things broke it on a server that pages: an
owner's listing can hold every subscriber's subscriptions, and the listing "SHOULD support LWS
Paging". On the SBU lws-server, paging at 4 items, the listing held 19, most of them left behind by
earlier runs, and the test failed a server that was doing nothing wrong.

Now bob subscribes to a resource granted to him, and lists as bob, so the listing holds only his
own subscriptions. A definition cannot walk an unknown number of pages, so a precondition requires
bob's listing to fit on one page (no `rel="next"`). A listing that spans pages makes the test
inapplicable, not failed. It now needs Authentication as well as ReachableFixtures.

**Why subscriptions were left behind.** Every subscribing definition cancels its subscription in
its last step, and a step runs only if every earlier one passed. So a test that fails, or stops at
a precondition after subscribing, leaves its subscription, and so does a run that is killed. A
server need not end a subscription when its topic is deleted. Format 0.7.0 can register only a
`Location` header for cleanup, and neither suite requires a Location on a subscription response, so
the definitions cannot fix this themselves. Deployments sweep instead (the Touchstone runner for our
targets now cancels leftover subscriptions whose topics lie under its container). A format that
could register any captured URL for cleanup would fix it at the source; noted for a later version.

### D-0073 — subscription scope, and filtered listings
Three definitions, the last ones the specification as it stands supports without a format change,
a server change or a working-group answer (catalog coverage 263 → 264 requirements cited).

**Subscription scope** (`core/notifications`). Core 10.3.2: "A subscription to a container is
recursive: the subscriber receives notifications for the container itself and for all resources
transitively contained in that container. A subscription to a data resource applies only to that
individual resource." Every delivery test so far changed the very resource or container it
subscribed to, so neither half was tested.
- `notification-delivered-in-subcontainer` (MUST): subscribe to the test container, create a data
  resource in a subcontainer, and a Create naming it arrives.
- `notification-scope-data-resource-only` (MUST): subscribe to one data resource, update a sibling,
  then update the subscribed one. Polling waits for the subscribed resource's Update; by then a
  sibling delivery would have arrived too, and none may name the sibling.

*No catalog entry for the scope paragraph.* It states the rule without an RFC 2119 keyword, so the
extractor did not catalogue it. The delivery-time authorization MUST leans on it ("all resources
within the scope of a subscription, including resources in subcontainers"). The tests quote it,
cite its section as their source, and cite the delivery requirements they exercise. They do not
invent a requirement the catalog does not hold.

**Filtered listings** (`core/access_grants`):
- `container-listing-filtered-metadata` (MAY): bob may read a container and one of its data
  resources, not the other. His listing shows the one he may read with its type and format, and
  every member it shows has an id and a type. Leaving out the other member is the MAY, and listing it
  is allowed too. "Listings must include core metadata for each member" is lower-case, so it binds
  the shape of what is shown, not whether the hidden member appears. In `core/access_grants`, not
  `core/containers`, because it is about what a grant lets bob see.

The reference server passes all three; no broken twin models a scope defect. Pinned counts move
from 195 to 198. Drafted by an agent; waits on branch `tests/scope-and-listing`.

### D-0074 — CID documents that must not verify, and one that must
Five definitions in `auth/cid`, made possible by what the format already has. Every standalone
identity with a `webid` under the fixture base and an `identityDocument` is served by the fixture
host, and the document may say anything. So each case is an identity whose credential is well formed
and signed with the run's CID key, and whose document breaks one rule of CID 1.0 section 3.3:
- `authn-cid-document-id-mismatch` (MUST): the document at the subject URL names another `id`
  (validation-dereference-sub-cid);
- `authn-cid-key-not-for-authentication` (MUST): the key is a `verificationMethod` referenced only
  from `assertionMethod`;
- `authn-cid-key-revoked` (MUST): the authentication method was `revoked` in 2000;
- `authn-cid-foreign-controller` (MUST): the method's `controller` is another document;
- `authn-cid-referenced-method` (MUST, positive): `authentication` refers to the method by id and
  `verificationMethod` defines it, the reference form beside embedding. `authn-cid-valid-credential`
  covers only embedding.

The refusals expect 400 `invalid_request`, as every other refused subject token does. Sources cite
the CID suite's validation section only: CID 1.0 is not in `anchors.json` (no dated snapshot), so the
comments quote it instead.

**Not defined.** A published private key and a Multikey document: the identity templates expose the
key only as `self.publicJwk`, a whole JWK, with no private member and no multibase form. A did:web
subject: the fixture host serves a document at the identity's own URL, and a did:web document lives at
a URL derived from the DID. Each needs an engine addition, noted for later.

**The reference authorization server follows.** It searched `verificationMethod` as well as
`authentication`, and ignored `controller`, `revoked` and `expires`. It now retrieves the method by
CID 1.0 section 3.3: the method must be embedded in `authentication` or referenced from it and
defined in the document, controlled by the subject, and not revoked or expired. The broken
authorization server exchanges anything, so the four refusals join the auth negative tests that must
catch it (23 → 27). Pinned counts move from 198 to 203, `auth` from 26 to 31. Drafted by an agent;
waits on branch `tests/cid-documents`.

## 2026-10-05

### D-0075 — testing LWS clients: the developer drives, Touchstone plays the server
The drafts define an LWS Client conformance class (`conformance-client-class`). The catalog already
holds its obligations, untagged among the server clauses. About twenty of them show in the requests
a server receives. Touchstone will test clients. [CLIENT-TESTING.md](CLIENT-TESTING.md) is the
brief, and DESIGN.md section 1 points to it. Proposed, not begun.

**The developer runs the client; Touchstone never drives it.** Touchstone gives a session:
- a private storage, identities, and a checklist of tasks, some of which arm a fault;
- a web page that judges each request against the catalog and explains every finding;
- an HTTP API, so the developer's own CI can create a session, run the client's tests against
  it, and fail on MUST findings.

Rejected: an adapter contract that would let Touchstone drive clients (per-language bindings, or an
MCP-controlled client harness). It puts the per-language cost on Touchstone, needs browser automation
for web clients, and tests the adapter as much as the client. Inverting control leaves HTTP as the
only interface, which every client already speaks. The session API gives the automation the adapter
was for, written in the developer's language.

**Touchstone implements the server side itself, from `RefLwsServer` and `RefAuthorizationServer`. It
does not depend on lws-server.**
- *Neutrality:* Touchstone grades lws-server. If lws-server's behaviour were also the yardstick for
  clients, its readings of the spec and its bugs would become expectations, and its releases would
  move client verdicts.
- *Control:* client tests need behaviour no production server should have:
  - faults armed for one request;
  - traps: opaque page URLs, URIs that don't mirror containment, a decoy challenge with a foreign
    realm;
  - per-session recording.
- *Lockstep:* the reference servers change in the same commit as the definitions, and the self-test
  holds them to it.
- *Weight:* lws-server is about 22,000 lines on Spring Boot, Jena TDB2 and pac4j. The reference
  deployment is about 3,900 lines on plain Jetty.

The real work is making `RefLwsServer` a public, multi-session service (state split from lifecycle,
quotas, expiry), not reimplementing LWS. lws-server keeps two roles. It is a cross-check: where it
and the reference servers both pass and still differ, the spec is ambiguous or a test is missing.
And it can sit behind a later, optional proxy mode that records clients against real servers.
Targets there come only from `targets.yaml`, per DESIGN.md section 7.1.

**Consequences.**
- Client rules are YAML-LD definitions of a new type (provisionally `ObservationTest`) in
  `definitions/lws10/clients/`, citing catalog requirements. A failure always cites a clause.
- Outcomes add `untested` (`earl:untested`) for rules the session never triggered. EARL runs record
  `earl:mode earl:semiAuto`.
- The catalog gains a conformance-class tag per requirement (phase C0).
- New security invariants cover a public service that accepts strangers' requests and delivers to
  their inboxes: capability-protected sessions, quotas, in-memory state only, outbound delivery only
  to public addresses. They sit beside DESIGN.md section 7, which is unchanged.
- Hosting, gating session creation, and OpenID client registration are open questions for Erich.
- **Gate C:** the `ObservationTest` schema waits for Erich's review before the first rule is
  written.

### D-0076 — every requirement names the roles it binds (client testing phase C0)
`touchstone:appliesTo` (catalog vocabulary 0.2.0) names who can break each clause, by role:

| Role | Requirements |
|---|---:|
| `Server` | 223 |
| `Client` | 75 |
| `AuthorizationServer` | 26 |
| `IdentityProvider` | 16 |
| `Receiver` | 4 |
| `Specification` | 2 |

A clause binding several roles names each one, and counts in each row above. 39 bind both servers
and clients, for example "servers and clients SHOULD use conditional requests". 248 of the 296 bind
a server or an authorization server; 48 bind neither.

**Deviation from CLIENT-TESTING.md as first written.** It proposed one value per requirement with
"mixed" for clauses binding several roles. Listing each role says which ones, and a filter on one
role then finds every clause that binds it. Two values the brief lacked are added:
- `IdentityProvider` covers OpenID Providers and SAML identity providers alike;
- `Specification` covers `authn-suite-token-type-uri` and
  `notification-suite-subscription-type-identifier`, which oblige suites, not implementations.

**How a clause was assigned.**
- A clause that defines a message binds whoever produces the message:
  - the ID Token and SAML assertion clauses bind the identity provider;
  - the CID suite's JWT clauses bind the client, which issues that credential for itself;
  - the core credential data model binds both the identity provider and the client;
  - subscription request, access request and grant, query and token request clauses bind the
    client. Where the server must also support or enforce the clause, it binds the server too.
- A clause that validates or refuses binds the verifier:
  - the suites' validation clauses bind the authorization server;
  - the webhook verification steps bind the receiver. Two of those steps also constrain what the
    server publishes, so they bind it as well.
- The curation file of the core draft carries the roles of its generated entries.
  `emit_candidates.py` refuses an entry without them, because only a reviewer can tell. The
  regenerated file differs from the old one only by the new lines.

**The lint** (`lint_definitions.py`) now checks two things:
- every catalog requirement names roles from the vocabulary;
- every test that cites requirements cites one binding a `Server` or `AuthorizationServer`.

A negative test may instead rest on a `Client` or `IdentityProvider` clause: it forges that
party's message, and refusing the message is what it checks. Nine tests do so:
- three subscription refusals;
- six refusals of malformed credentials.

No core clause explicitly obliges a server to refuse a malformed subscription; that may be worth
raising with the working group. One validation test cited only a `Specification` clause:
`authz-metadata-subject-token-types-are-uris` now also cites
`authz-metadata-subject-token-types`, the authorization server's clause for the metadata member
it checks.

**Coverage counts server-side requirements only:** 225 of 248 are cited. That applies to:
- `touchstone coverage`, which also says how many it left out;
- the MCP `coverage` tool, which returns them as `notCounted`;
- every report, since the HTML, JSON, Markdown and PDF reports share one model.

A report still lists a requirement of another role when one of its tests cites it, so the test's
link resolves, but that row is not counted in coverage. A requirement with no roles, from an older
or a fixture catalog, counts as server-side.

**Elsewhere:**
- MCP `list_requirements` gains a `role` filter, and requirement summaries and details carry
  `appliesTo`.
- `definitions/COVERAGE.md` gains section 4: the requirement counts by role, and the 79 client and
  receiver requirements, each with the server tests that already cite it. That generated list
  replaces the hand-made inventory in CLIENT-TESTING.md section 11. Checking §11 against the tags
  showed one mistake: `access-endpoints-jsonld-payloads` binds servers, so the client row now
  cites `access-jsonld-context-lws-v1`.

Drafted by an agent; waits on branch `catalog/conformance-classes`.

### D-0077 — client sessions (phase C1): a mountable reference deployment, legal traps, a recorded traffic log
The new module `harness-clients` is the client-session service of CLIENT-TESTING.md. It is plain
embedded Jetty and depends on `harness-fixtures`. Each session is a reference storage and
authorization server of its own under `{base}/s/{sid}/`, with the session API, a session page
and a traffic log. Phase C1's acceptance holds: `curl` with a session token creates, lists and
deletes, and the log shows each exchange redacted and annotated.

**The reference servers mount.**
- `RefLwsServer.mounted(storageUri, as, owner, traps)` and `RefAuthorizationServer.mounted(issuer)`
  build servers without a Jetty server of their own; `handler()` serves them inside another.
- A mounted storage builds its URLs from its public URI, not from the request, so it works
  behind a reverse proxy.
- A mounted authorization server's issuer has a path, so its metadata is where RFC 8414 section
  3.1 puts it: `/.well-known/lws-configuration` followed by that path.
- `TokenValidator` takes the authorization server's keys in process.
- The standalone servers and the self-test are unchanged.

**Containment is no longer read from paths.** A stored resource records its container, so:
- `rel="up"`, deletion and subscription topics work for URLs that do not nest;
- a topic covers a resource when it is one of the resource's ancestors, not a prefix of its URL;
- topic and search lookups map a URI to a path through the storage's base.

**Traps** (`Traps`, CLIENT-TESTING.md section 6.1) are off by default:
- opaque page URLs;
- flat resource URLs;
- opaque linkset URLs;
- PUT only on text, JSON, XML and RDF media types;
- a decoy, listed first in the root so that a client reading one page meets it;
- an index lag.

A new self-test scenario, `TRAPPED`, runs every definition against a secured deployment with all
traps set and a 1-second lag. All 203 pass, which is what shows the traps are legal.

**The trapped self-test found a definition that assumed nested URIs.**
- `type-search-content-location-protected` found alice's search result by her test
  container's path in the result's id, and checked bob's answer for that path.
- With flat URLs neither works, and the test went inapplicable after a minute of polling.
- It now gives her resource a type whose fragment, `#ContentLocationProtectedSecret`, appears
  nowhere else. A precondition requires her result to carry that type (lws10-index: "each item
  ... MUST carry at least the matched resource's id and its type"), and the check looks for the
  fragment in bob's answer.
- It still fails against the broken storage.

**One change to the reference's behaviour:** every read now advertises the methods the resource
supports in `Allow`, and for a data resource the patch format in `Accept-Patch`. That is the
core MUST that servers "use standard HTTP headers to advertise their capabilities", and it is
what a client is told to check. No definition's outcome changed. The class comment's claim that
deliveries are unsigned and never retried, stale since D-0066 and D-0070, is corrected.

**The traffic log.**
- The storage marks each request with what it addressed and whose valid token it carried.
- The service records every request to a session's storage and authorization server, with:
  - its answer, the response body kept up to 64 KiB;
  - how the token was presented, and its fingerprint (the first twelve hex digits of its
    SHA-256);
  - what the URL last advertised.
- Credential headers, `access_token` in a query, and the credential fields of token requests and
  responses are fingerprinted, never kept.
- The URL ledger learns what each answer hands out:
  - the URLs in `Location`, `Content-Location` and `Link`;
  - a challenge's `as_uri`, with the metadata URL RFC 8414 derives from it;
  - the session URLs in the JSON the server generates.

  A request outside the ledger is marked built by the client. CORS preflights are answered and
  recorded.

**Sessions.**
- The id is 96 random bits, and public.
- The key is 256 random bits, shown once and stored as a SHA-256 hash, compared in constant time.
- The session page reads the key from its URL's fragment, which never reaches the server or a
  Referer, and calls the API with it. It is served with a strict Content Security Policy and
  writes what clients sent only as text.
- The bounds are those of section 8.2, with defaults in `ClientLabConfig`.
- Notifications are not delivered from a session (`deliverOnlyTo(uri -> false)`) until phase C5
  brings the outbound guard.
- Identity documents and the session OpenID Provider are phase C4. Until then a client
  authenticates with the tokens the session hands out.

Drafted by an agent; waits on branch `clients/c1-sessions`.

### D-0078 — client rules: format 0.8.0 adds `ObservationTest` (proposed, Gate C)
Phase C2 starts at Gate C (D-0075): the format for client rules waits for Erich's review
before the first rule is written. Format 0.8.0 is that format. It is proposed, not frozen.

**What is proposed:**
- `ObservationTest`, a third kind of manifest entry, in the schema (`$id …/0-8-0`), the
  context and the vocabulary;
- `definitions/OBSERVATION.md`, the contract for judging rules: the exchange, the recorder's
  annotations, conditions, how trials are selected, outcomes and EARL.

Nothing a 0.7.0 definition relies on changes. The server engine accepts `0-8-0` and never
meets a client rule, because the server root manifest does not include `clients/`.

**A rule is two conditions on one exchange.** `observe` selects the trials and `expect`
judges each one, and both use one vocabulary:
- the request's own terms, `contentType`, `linkHeaders`, `otherHeaders`, `bodyMatches` and
  `json`, reused from the response vocabulary and applied to the request;
- `statusCode`, for the session's answer;
- the recorder's annotations;
- `anyOf`, for alternatives.

`after` expresses "the next X after Y". There are no variables and no captures, so a rule is
a plain predicate on exchanges and reads the same in the traffic log as in its definition.

**The cleverness lives in the recorder** (CLIENT-TESTING.md section 4.3). Annotations are
the facts only the server knows, computed and tested in one place:
- `server` and `role`: what the request addressed;
- `issued`, `builtBy` and `builtFromRole`: whether the session handed the URL out, and if not,
  which handed-out URL the client built it from, by query or by path;
- `presentation`: where the request carried a credential;
- `methodAdvertised`, `patchFormatAdvertised` and `queryFormatAdvertised`: what the URL
  advertised before the request.

Their values are plain strings, not vocabulary terms. Roles such as `container` would
otherwise collide with the traits of the same name.

**The format was tried on the C2 rules before review.** Twenty-five draft rules, 18 MUST, 6
SHOULD and 1 MAY, cover every C2 row of CLIENT-TESTING.md section 11. They exist to test the
format and are not committed, because of the gate; section 11 lists them. They pass the
YAML-LD, schema and JSON-LD export checks. Seventeen broken copies were all rejected,
including a rule with `steps`, `after` inside `expect`, an unknown role and an `anyOf` of one.

**Seven defaults were chosen. Each is open at Gate C.**
1. **`client-no-assumed-methods-405-415` is read as being about linksets,** where the clause
   sits: the metadata section's modifiability considerations. The session's linksets refuse
   PUT, so the rules bite there. Read generally, the clause would also cover data resources,
   where the trap that refuses PUT on binary resources (D-0077) would back it.
2. **A rule takes the level of the obligation it observes,** not that of the clause it
   cites. The client halves of two MUST-level clauses are SHOULDs: not assuming methods, and
   conditional linkset writes. The lint allows a rule weaker than its requirement, never a
   stronger one.
3. **The "authenticated POST" of `subscription-create-post-lws-json` is not judged per
   request.** Discovery works by trying without a token and being challenged.
4. **Access documents get one rule per property,** eleven in all, so each failure cites its
   own clause. The optional members (target, constraint, inbox) are judged only in documents
   that have them. A client that never sends one sees *untested*, not a vacuous pass.
5. **MAY rules are information.** One is proposed: a conditional DELETE. Two MAY rows of
   section 11 get no rule:
   - `prefer-link-relations-filtering`: the draft leaves its syntax open, so nothing can be
     checked;
   - `client-415-accept-query`: while the session accepts only the baseline format, it
     cannot be told apart from `client-query-baseline-after-415`.
6. **`presentation` is a set.** A token in both the header and the query string fails, as
   RFC 6750 section 2 allows one method per request.
7. **Deferred to phase C3, as a later format version:**
   - tasks and faults: `task`, `arm` and a `fault` annotation;
   - `followedBy`, for an absence within a window, which "no blind retry" needs.

   Each will get its own entry, as additions to a frozen format do.

**After the freeze,** in order:
1. the loader and lint for `ObservationTest` in harness-core;
2. the recorder's new annotations;
3. the rule evaluator in harness-clients;
4. the rules;
5. `RefLwsClient` and its first twins, in `ClientRulesSelfTest`.

The documentation pages that say "format 0.7.0, frozen" change when 0.8.0 is frozen.

Drafted by an agent; waits on branch `clients/c2-rules`, which is stacked on
`clients/c1-sessions`.

### D-0079 — Gate C closed: format 0.8.0 is frozen, with the seven defaults accepted
Erich approved the proposal of D-0078 on 2026-10-05. The freeze covers the schema
(`$id …/0-8-0`), `context.jsonld`, `vocab.yamlld`, `EXECUTION.md` and the new
`OBSERVATION.md`. The seven defaults D-0078 left open are accepted as written:
1. `client-no-assumed-methods-405-415` is about linksets;
2. a rule takes the level of the obligation it observes;
3. the subscription POST's authentication is not judged per request;
4. access documents get one rule per property;
5. MAY rules are information, and two MAY rows get no rule;
6. `presentation` is a set;
7. tasks, faults and `followedBy` come with phase C3, as a later format version.

**One clarification at the freeze.** The proposal's `OBSERVATION.md` section 3 said request
bodies "are kept whole". Rules do judge them whole, as each exchange is recorded, but the
traffic log keeps only the first 64 KiB, as it did in phase C1. Section 3 now says both.

A later change to any of the five files bumps the schema `$id` and needs its own entry, as
before. Adding, correcting or retiring a client rule is content, not format.

### D-0080 — client rules (phase C2): 25 rules, judged as each exchange is recorded, proven by a reference client and 25 twins
Phase C2 is built. Its acceptance criterion was that every C2 rule passes for the reference
client and fails for its twin. It holds for all 25 rules.

**The rules** are the 25 of D-0078, unchanged, in `definitions/lws10/clients/` (core,
notifications, index). `COVERAGE.md` lists them in a new section 5. Its role table gains a
column of the requirements a client rule cites: 32 of the 79 that bind a client or a receiver.

**harness-core** gains client-rule loading, and the matching it shares with the engine:
- **`DefinitionLoader.loadClientRules`** reads `lws10/clients/` through the same YAML, schema
  and JSON-LD checks, then the lint of `OBSERVATION.md` section 2 (`ClientRuleLint`). It loads
  the server tests too, because names are unique across both. The server loader still refuses
  a client rule in a server manifest.
- **`Matching`** is a public facade over the response checks. The status, Link and header
  checks moved out of `Evaluator` into `MessageChecks`, which both use. A term therefore means
  the same on a client's request as on a server's response, with one implementation.

**harness-clients** now depends on harness-core, as CLIENT-TESTING.md section 4.1 planned:
- **The recorder** computes the rest of `OBSERVATION.md` section 4:
  - `server`;
  - `builtBy`, `builtFrom` and `builtFromRole`. The ledger keeps the order URLs were handed out
    in, and the role of each URL's latest exchange.
  - `presentation`, now a set. A token the session issued is found anywhere: in another header
    or a query parameter. The session keeps its tokens in memory, those of its API and those
    its token endpoint answers with.
  - the three `…Advertised` booleans. A 415 now advertises, as a 405 already did, since it
    carries `Accept-Patch` or `Accept-Query`.
- **`Judge`** applies the rules to each exchange under the log's lock, in the log's order, with
  `Conditions` evaluating each condition. Each exchange in the log carries the verdicts of the
  rules it was a trial of.
- **The session API** gains `GET …/results` (each rule's outcome, trials, first failure and
  guidance, and the verdict) and `POST …/reset`.
- **The session page** shows the rules and their evidence, and a Rules column in the traffic
  log.
- **The launcher** takes `--definitions` and `--catalog`, and exits 2 if a rule fails its
  checks.

**Some linksets now accept PUT, in sessions only.** The session's linksets all refused PUT.
That left `client-linkset-put-only-when-advertised` with no way to pass: its only possible
trials were failures, so a conformant client could only ever be *untested*. In a session, a data
resource's linkset now accepts PUT and lists it in `Allow`, while a container's refuses it. A
client therefore cannot assume either way.

The draft makes PUT on a linkset optional in so many words ("If advertised in the Allow
header, a client MAY replace the entire linkset"). It is a switch of its own on
`RefLwsServer`, `linksetPutOnDataResources`, not one of the `Traps`. The trapped server
self-test leaves it off, because `linkset-put-405-when-unsupported` needs a data resource's
linkset that refuses PUT, and would otherwise be inapplicable.

**The proof.**
- **`RefLwsClient`** (`harness-fixtures`) is a scripted client on the JDK `HttpClient`. It
  reads before it writes, makes its writes conditional, follows the links it is given,
  composes access documents and a subscription as the drafts define them, and retries a
  refused search in the baseline format.
- **Its `Flaw`s** make 25 twins, one per rule, each getting exactly one thing wrong.
- **`ClientRulesSelfTest`** runs each in a session of its own:
  - the reference passes all 25 rules, none of them *untested*, and the verdict reads "no MUST
    failure in 18 MUST rules exercised, of 18 that apply";
  - every twin fails exactly the rule aimed at it.

  The evidence was read, not only the outcomes, to confirm each twin fails for the reason it
  exists. For example, the page builder fails at `?page=2` on "issued: expected true, was
  false".
- **Checks:** 12 new schema controls for client rules are all rejected, and the Python lint
  enforces section 2.

**Not yet:**
- tasks and faults, phase C3;
- the EARL and JUnit XML forms of the results, and choosing areas out of scope when a session
  starts, phase C6 (the judge already makes such rules *inapplicable*);
- clearer evidence for a quantified JSON expectation (`every`, `some`, `none`). It shows the
  whole nested list rather than the member that failed, which is enough for a log but not for
  the page that phase C6 builds.

Drafted by an agent; waits on branch `clients/c2-rules`.
