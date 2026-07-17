# Touchstone — W3C LWS Conformance Harness

**Build brief / project plan.** You are starting this project from scratch. This document is the complete decision record from the design phase — treat the decisions in it as settled unless they prove technically impossible, in which case flag the conflict before deviating. Project name: **Touchstone** — the assayer's stone used to test gold against a known standard. Keep the name in one constant/property so renaming stays trivial (a known naming collision is noted in §10).

---

## 1. Mission

Build a conformance test harness that validates whether a **W3C Linked Web Storage (LWS) server** implementation complies with the LWS specifications. The system under test (SUT) is a server, so the harness plays the **client role**: it fires HTTP requests at the SUT and asserts on responses. It also runs **controlled identity fixtures** (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent identity documents) because the SUT validates credentials against — and dereferences identity documents from — external parties, and the harness must own those parties to test failure paths deterministically.

Three consumers, one engine:
1. **CLI** — run suites locally, output reports.
2. **CI** — GitHub Action + Docker image so server implementers run it on every commit.
3. **MCP server** — a remote LLM/agent triggers runs, reads failures, drills into traces, and iterates on a server implementation in a tight loop.

## 2. Normative inputs and prior art (fetch these before coding)

Spec landscape as of mid-2026 — all early-stage and moving; design for spec-version pinning:
- **LWS Protocol 1.0** (First Public Working Draft, March 2026): https://www.w3.org/TR/lws10-core/ — editor's draft at https://w3c.github.io/lws-protocol/lws10-core/ ; repo https://github.com/w3c/lws-protocol
- **LWS 1.0 Authentication Suites** (four FPWDs, April 2026): OpenID Connect, SAML 2.0, Self-signed Identity using Controlled Identifiers (CID), and did:key. Find current drafts via the WG publications page and the lws-protocol repo.
- **LWS Use Cases** Group Note (requirements list): https://www.w3.org/TR/lws-ucs/
- **Solid Protocol 0.11** — the WG's input document; much LWS behavior descends from it.
- Key spec concepts to model: *LWS resource*, *container* (enumerates a collection; Containers section), *data resource* (Operations section), *containment* (container manages resource lifecycle), *storage root*. Known testable clauses already in the draft: Last-Modified MUST be generated on GET/HEAD; PATCH insertion formulae MUST NOT contain blank nodes; failed credential validation MUST return 401 with a WWW-Authenticate challenge.

Prior art — **harvest, don't fork**:
- Solid Conformance Test Harness (CTH): https://github.com/solid-contrib/conformance-test-harness (Java/Quarkus + KarateDSL). Study its RDF test descriptions linked to spec requirement annotations, EARL + coverage reporting, alice/bob two-account config model, and its `SolidClient` / `SolidResource` / `SolidContainer` / `AccessDatasetBuilder` helper design — that helper shape is right for Touchstone too.
- Test corpus: https://github.com/solid-contrib/specification-tests — many scenarios adapt to LWS since it descends from Solid 0.11. Port scenario *content*, not the Karate execution model.

**Decision already made (do not relitigate): no Karate.** Rationale: Touchstone's assertions are graph isomorphism, SHACL shapes, and header semantics — none expressible in Karate's matchers, so Karate would be a thin Gherkin veneer over Java helpers, costing debuggability and type safety. Instead: declarative manifests + JUnit 5 dynamic tests (§5).

## 3. Repository layout (Gradle or Maven multi-module; prefer Maven)

```
touchstone/
  harness-core/        # engine: catalog, manifests, executor, assertions, EARL/HTML reporting. NO Spring dependency.
  harness-fixtures/    # embedded Jetty 12 fixture servers: OIDC issuer, SAML IdP, CID/did:key signer, identity-doc host, webhook sink
  harness-mcp/         # Spring Boot app: Spring AI MCP server (WebMVC transport) on Jetty; thin adapter over core
  harness-cli/         # picocli front end over core
  catalog/             # requirements catalog (Turtle) — versioned per spec draft
  manifests/           # test manifests (YAML), organized by module: core/, auth-oidc/, auth-saml/, auth-cid/, auth-didkey/
  docs/
```

`harness-core` must be usable without Spring: CLI, CI, and MCP are alternate front ends over the identical engine. If adding the MCP layer requires touching the executor, the layering is wrong.

## 4. Technology stack (pinned choices)

- **JDK 21** (virtual threads), Maven.
- **RDF: Apache Jena** — parsing (Turtle/JSON-LD), graph isomorphism (`IsoMatcher`), reading Turtle catalogs, writing EARL. Decision: Jena, not RDF4J — commit, don't mix.
- **Titanium JSON-LD** for JSON-LD 1.1 expansion/compaction round-trip tests.
- **Jena SHACL** for declarative response-shape assertions in manifests.
- **HTTP:** JDK `java.net.http.HttpClient` for well-formed requests (its WebSocket client covers future notification tests); **Apache HttpClient 5** (or raw sockets) for hostile/malformed requests that polite clients normalize away.
- **Test engine:** JUnit 5 with `@TestFactory` dynamic tests — a factory reads manifests at runtime and materializes one `DynamicTest` per case (IDE integration, parallelism, JUnit XML for free). **AssertJ** for assertions, **Awaitility** for async waits.
- **Auth fixtures:** **Nimbus JOSE+JWT** + **Nimbus OAuth2/OIDC SDK** (mint valid AND deliberately broken tokens; publish JWKS + discovery docs; DPoP if the OIDC suite keeps sender-constrained tokens). **OpenSAML 5** for SAML assertions including invalid ones. **BouncyCastle** + a multibase helper for did:key/CID — did:key is simple enough that hand-rolling encoding over BouncyCastle beats a thin third-party DID lib.
- **Fixture servers:** programmatic **embedded Jetty 12** `Server` instances on random ports, started/stopped per run; **WireMock** acceptable for purely static fixtures (canned identity docs).
- **Infra:** Testcontainers (spin up SUT in CI), Jackson, picocli, SLF4J/Logback, FreeMarker (HTML coverage report).
- **MCP layer:** Spring Boot + **`spring-ai-starter-mcp-server-webmvc`** (servlet stack — REQUIRED so the Jetty swap works; the WebFlux starter pulls Netty). Exclude `spring-boot-starter-tomcat` everywhere (verify with `mvn dependency:tree | grep -i tomcat`), add `spring-boot-starter-jetty`. Pin the **latest Spring AI 1.1.x GA** — Spring AI 2.0 is in milestones and moved the MCP Spring transports into `org.springframework.ai` (breaking); schedule the 2.0 bump, don't build on milestones. Verify current versions online before pinning.

## 5. Core design

### 5.1 Requirements catalog (the highest-leverage artifact)
Extract every RFC 2119 clause from the spec drafts into `catalog/*.ttl`. Each requirement gets a **stable IRI** and records: level (MUST/SHOULD/MAY), spec module, section anchor URL, quoted-clause hash (to detect drift when the draft changes), and status. Example shape:

```turtle
@prefix touchstone: <https://example.org/touchstone/vocab#> .
<https://example.org/touchstone/req/lws10-core/last-modified-get-head>
  a touchstone:Requirement ;
  touchstone:level "MUST" ;
  touchstone:specModule "lws10-core" ;
  touchstone:section <https://www.w3.org/TR/lws10-core/#operations> ;
  touchstone:clauseHash "sha256-..." ;
  touchstone:summary "Server generates Last-Modified on GET and HEAD responses." .
```

Build a semi-automated extractor (fetch draft HTML, find BCP14 keywords, emit candidate entries for human review). Coverage = requirements × tests matrix; every test declares the requirement IRIs it verifies. MUST failure ⇒ non-conformant; SHOULD/MAY ⇒ advisory; optional features gated on the target's declared capability profile.

### 5.2 Test manifests (tests as data — single source of truth)
Declarative YAML in `manifests/`, compiled to `DynamicTest`s. One manifest = metadata + HTTP step sequence + assertions. Sketch (refine the schema in Phase 2, then freeze it behind a JSON Schema):

```yaml
id: core/container-containment-after-post
requirements:
  - https://example.org/touchstone/req/lws10-core/containment-on-create
title: POST to container adds containment triple
capabilities: []           # required server capabilities; empty = always runs
steps:
  - request: { method: POST, target: "${run.container}", headers: { Content-Type: text/turtle }, bodyRef: bodies/note.ttl }
    expect:  { status: 201, headers: { Location: { present: true } } }
    bind:    { created: header:Location }
  - request: { method: GET, target: "${run.container}", headers: { Accept: text/turtle } }
    expect:
      status: 200
      graph:
        contains: [ { s: "${run.container}", p: "http://www.w3.org/ns/ldp#contains", o: "${created}" } ]
```

Assertion vocabulary the executor must support: status, header presence/value/regex, graph-contains triples, graph isomorphism against a fixture graph, SHACL shape conformance, conneg round-trip (Turtle ↔ JSON-LD with relative-IRI resolution against the effective request URI), and negative assertions. Manifests may reference credentials abstractly (`as: alice`, `as: anonymous`, `as: alice-expired-token`) — the auth module resolves them.

### 5.3 Executor and isolation
- Every run allocates a unique root container `/touchstone-run-{uuid}/` on the SUT; every test creates its own sub-resources. **No ordering dependencies between tests, ever.** Parallel by default.
- Provisioning (accounts/storage) is out of spec scope ⇒ pluggable per-implementation **provisioning adapters**. Minimum config: two identities, **alice** and **bob** (two is the floor for access-control tests later), supplied via config/env like the CTH does.
- Timeouts everywhere; retries OFF by default (a flaky SUT is a finding, not noise).

### 5.4 Coverage areas (module roadmap)
`core`: storage/discovery, container semantics + URI allocation, CRUD operation matrix per resource type (RDF + binary), containment lifecycle, PATCH semantics (including the blank-node prohibition in insertion formulae), conditional requests (`If-Match`, `If-None-Match: *` on create, 412), content negotiation, error semantics (401 vs 403, 404, 405 + `Allow`, 409, 412, 415), `Last-Modified`/`ETag`, `WWW-Authenticate` on 401, CORS behavior for browser clients.
`auth-oidc`, `auth-saml`, `auth-cid`, `auth-didkey`: one module per published suite. **Negative tests are the point**: expired token, wrong audience, bad signature, replayed proof, key/identifier mismatch, identity-doc key rotated mid-session. This is why fixtures are library-level fakes (Nimbus/OpenSAML), not Keycloak — real IdPs won't mint corrupted credentials on purpose.
Future modules (charter scope, drafts pending): authorization, notifications, data integrity. Leave module seams for them.

### 5.5 Reporting
- **EARL** RDF (test IRI + subject + outcome) — the W3C-standard format the WG will need for CR implementation reports.
- **HTML** coverage + results report (FreeMarker): per-requirement matrix, MUST/SHOULD/MAY rollups, links to spec sections.
- **JUnit XML** for CI.
- `diff` between two runs (regressions/fixes) as a first-class core operation — the MCP layer and CI both need it.

## 6. MCP interface (harness-mcp)

Spring Boot app, MCP over **streamable HTTP** (stateful; single instance is fine — runs are node-local and progress streaming needs the session), plus an optional **stdio profile** for local agent attachment.

**Configuration baseline:**
```yaml
spring:
  threads.virtual.enabled: true
  ai.mcp.server:
    name: touchstone-harness
    version: 0.1.0
    protocol: STREAMABLE
    type: SYNC
    instructions: "LWS conformance harness. Start with list_requirements or coverage."
server:
  port: 9090
  shutdown: graceful
  jetty:
    threads.max: 64
    connection-idle-timeout: 300s   # streamable connections are long-lived; Jetty's default kills them
```

**Tool surface** (use `@McpTool`/`@McpToolParam` annotations; keep tools thin over core; return tight structured summaries — never dump full reports into a response):
- `list_requirements(module?, level?)`, `get_requirement(iri)` — include clause text + section link so the agent can read *why* a test exists
- `list_tests(requirement?, module?, tag?)` — metadata only
- `start_run(target_id, selector)` → returns `run_id` immediately (async job on a virtual-thread executor); emit MCP progress notifications during execution
- `get_run(run_id)` — status + pass/fail/skip counts by requirement level
- `get_failures(run_id, filter?, page?)` — summaries only, paged
- `get_trace(assertion_id)` — full **redacted** HTTP exchange + expected-vs-actual graph diff; one at a time by design (token economy)
- `diff_runs(a, b)` — the tool agents will live in
- `run_one(test_id)` — synchronous, verbose, for the fix-verify loop
- `coverage(module?)`
- MCP **resources**: `requirement://{...}`, `report://{run}/earl`; MCP **prompts**: "triage this run", "draft a test for requirement X"

**Stdio profile gotchas:** `spring.main.web-application-type=none`, banner off, all logging to stderr/file — anything on stdout corrupts protocol framing.

## 7. Security invariants (never violate; enforce in code review and tests)

1. **No arbitrary targets.** The harness is deliberately an HTTP cannon (malformed, hostile, high-volume requests). Targets are pre-registered out-of-band in config (`@ConfigurationProperties` registry); MCP tools accept only `target_id`, never URLs. This is an SSRF/abuse boundary.
2. **Redact server-side.** Traces contain Authorization headers, DPoP proofs, cookies. Strip before anything leaves the harness; never rely on the consumer to look away.
3. **SUT responses are untrusted input to the agent.** Truncate bodies by default in traces, label trace content clearly as untrusted data (prompt-injection surface), keep the mutating tool surface minimal.
4. **Human gate on test authoring.** Any future `draft_manifest` flow routes: draft → schema validation → dry-run against a reference server → pull request. Never direct commit; the requirements mapping is the crown jewels.
5. If the MCP endpoint is hosted, front it with Spring Security OAuth2 resource server; use a `TransportContextExtractor` if tools need caller identity from HTTP headers.

## 8. Engineering conventions

- Test the harness itself: unit tests for the assertion engine (graph isomorphism edge cases, header parsing), plus an integration loop against a reference LWS/Solid server via Testcontainers (Community Solid Server is a reasonable stand-in until LWS implementations exist — confirm current best option online).
- Version the suite in lockstep with spec drafts (git tags per Working Draft; `clauseHash` drift check fails the build with a "spec moved" report, not silent staleness).
- Structured logging; every SUT interaction logged with run/test/assertion IDs.
- Conventional commits; keep a `DECISIONS.md` recording deviations from this brief with rationale.

## 9. Delivery plan (phases with acceptance criteria)

**Phase 0 — Scaffold.** Multi-module build, JDK 21, CI pipeline, empty modules compile, `touchstone --version` runs. *Done when: green CI on main.*

**Phase 1 — Requirements catalog.** Catalog vocabulary + Turtle files for lws10-core; semi-automated extractor with human-review output; `coverage` computation over an empty test set. *Done when: catalog lists every BCP14 clause in the core draft with stable IRIs, and the extractor flags clause drift on a spec re-fetch.*

**Phase 2 — Manifest schema + executor.** Freeze manifest JSON Schema; JUnit 5 `@TestFactory` loader; assertion engine (status/headers/graph-contains/isomorphism/SHACL/conneg); per-run isolation; provisioning adapter SPI with an env-config implementation; **10 core happy-path tests** (CRUD + containment) passing against the reference server. *Done when: `touchstone run --target ref --module core` executes real tests in parallel with correct pass/fail.*

**Phase 3 — Reporting.** EARL writer, FreeMarker HTML coverage/results, JUnit XML, `diff_runs`. *Done when: a run emits all three formats and the HTML matrix links tests → requirements → spec sections.*

**Phase 4 — Fixtures + OIDC suite.** harness-fixtures with ephemeral Jetty OIDC issuer (Nimbus): discovery + JWKS + token minting including broken variants; `auth-oidc` manifests incl. the 401/WWW-Authenticate clause and the full negative matrix. *Done when: negative tests demonstrably distinguish a compliant reference from a deliberately broken stub server.*

**Phase 5 — MCP server.** harness-mcp per §6: Jetty swap verified (no Tomcat on the classpath), streamable HTTP, full tool surface, async runs with progress notifications, redaction filter, stdio profile. *Done when: an MCP client can start a run, watch progress, page failures, and pull a redacted trace end-to-end.*

**Phase 6 — Distribution + remaining suites.** Dockerfile + GitHub Action; `auth-saml` (OpenSAML), `auth-cid`, `auth-didkey` fixtures and manifests; harvest/port applicable scenarios from solid-contrib/specification-tests into manifest form. *Done when: a third-party server repo can add one workflow file and get a conformance report on every push.*

## 10. Verify online before pinning (things that move fast)

- Current LWS draft contents — the FPWD churns; re-derive the catalog from the live editor's draft, not from this brief's examples.
- Latest Spring AI 1.1.x GA version and current MCP starter property names; Spring Boot LTS line; Jena/Nimbus/OpenSAML current majors.
- Whether the LWS WG has started an official test-suite effort (check w3c/lws-protocol issues) — if so, align formats with it and note in DECISIONS.md; contributing beats a parallel effort.
- Name collision, known: an established conformance-testing platform named Touchstone already exists in the HL7 FHIR ecosystem (by AEGIS) — different standards domain, but the same category of tool. Acceptable for a private repo; before publishing publicly, verify the current trademark/GitHub/Maven situation and prefer disambiguated public coordinates (e.g., repo/artifact `touchstone-lws`, Maven group reflecting your org).

## 11. First actions in this session

1. Fetch and skim: LWS core draft, one auth-suite draft, the CTH README, specification-tests README.
2. Scaffold Phase 0 and commit.
3. Draft the catalog vocabulary + 15 seed requirements from the core draft's Operations and Containers sections; open them for my review before mass extraction.
4. Propose the frozen manifest JSON Schema (informed by §5.2) before writing test #1.

Work phase by phase; do not skip acceptance criteria. When the spec and this brief disagree, the spec wins — record the discrepancy in DECISIONS.md.