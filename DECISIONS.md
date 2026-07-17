# DECISIONS.md — deviations and clarifications vs DESIGN.md

Per the brief: decisions in DESIGN.md are settled; anything that changed on contact
with reality is recorded here, dated, with rationale. When the spec and the brief
disagree, the spec wins.

Gate status:
- **Gate 1 (15 seed requirements before mass extraction): OPEN — awaiting Erich's review.**
- **Gate 2 (frozen manifest JSON Schema before test #1): OPEN — proposal drafted, awaiting Erich's review.**

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

### D-0010 — Auth-suite drafts are early "unofficial proposal" editor's drafts
The four suites live in-repo as `lws10-authn-{openid,saml,ssi-cid,ssi-did-key}`
(brief's module names auth-oidc/-saml/-cid/-didkey map 1:1). The OIDC draft is thin:
claim-mapping clauses (`sub`/`iss`/`azp`/`aud`, "MUST NOT use 'none'"), no
401/WWW-Authenticate text — that clause lives in core §4 Authentication. No impact
on phase order; auth catalogs will be extracted per-suite at Phase 4+.
