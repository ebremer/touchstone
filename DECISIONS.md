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
