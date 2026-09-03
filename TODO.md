# TODO — prioritized

Output of a full code + spec-conformance review, **2026-09-02**, against
**"Linked Web Storage Protocol 1.0", W3C Working Draft 21 August 2026**
(`https://www.w3.org/TR/2026/WD-lws10-core-20260821/`) and the four authentication
suites now published on `/TR/` (3 and 21 August 2026).

The repo's catalog baseline was the **22 June 2026** WD (D-0002); the spec moved on
21 August. Everything in P1 followed from that; P0 and P2 are defects independent of it.
**Every priority is done** (DECISIONS.md D-0038 to D-0046). What remains open is the
notification suite, deferred on purpose (D-0041).
The `check_drift.py` output quoted below is the state that prompted the work — re-running it
today reports no drift.

How the spec findings were derived (reproducible):

```sh
curl -sSL -o /tmp/WD-lws10-core-20260821.html https://www.w3.org/TR/2026/WD-lws10-core-20260821/
cd tools/extractor
python check_drift.py --spec /tmp/WD-lws10-core-20260821.html ../../catalog/lws10-core.ttl
#   catalog entries checked: 162; spec normative blocks: 192
#   info: 50 uncatalogued normative block(s)
#   DRIFT: 20 catalog clause(s) no longer present in the spec
```

---

## P0 — broken now; fix before anything else

**Done 2026-09-02 (D-0038).** `./mvnw -B clean verify` is green on Windows across all five
modules (106 tests), and `touchstone run --target ref --module core` exits **0** with the
full seven-file bundle under `runs/2026-09-03T013153Z-b9e22538/`.

- [x] **`Reports.writeAll` throws on Windows and the whole run bundle is lost.**
      `RunDirs.dirName` stamps the directory with an XSD `dateTime`
      (`2026-09-03T01:02:53Z-6ae2104a`); `:` is illegal in a Windows filename, so
      `runsDir.resolve(...)` throws `InvalidPathException` at
      `harness-core/.../report/Reports.java:29` — **before** the `try`, so nothing is
      written: no `earl.ttl`, no `report.md`, no `run.json`. picocli turns the escaped
      exception into **exit 1**, so a *fully conformant* run reports non-conformance.
      Reproduced on this machine: 21/21 core tests passed, exit 1, zero artifacts.
      D-0032 predicted the incompatibility but not that it destroys the evidence and
      inverts the verdict.
      *Fix:* make `RunDirs.stamp` filesystem-safe (`2026-09-03T010253Z` — still ISO 8601
      basic, still lexically chronological; `RunDirs.stamp` is the single change point the
      javadoc already names), keep `RunDirs.locate` matching both spellings, and add a
      `RunDirsTest` case that runs on every platform. Record as a decision amending D-0032.
      *Files:* `harness-core/.../report/RunDirs.java:52`, `Reports.java:29`.
      → **Fixed.** Stamp is now ISO 8601 basic (`2026-08-21T193247Z`); `RunDirs.resolve`
      falls back to the bare run id if a filesystem ever rejects a name, so this class can
      no longer cost a run its evidence; `RunDirsTest` (9 cases) is platform-independent
      and asserts the whole bundle is written.

- [x] **`harness-cli` is red on this branch.** `RunCommandTest.coreSuitePasses…:43` fails
      for the reason above, so `./mvnw -B verify` does not pass today. CI (ubuntu) is green
      because the bug is Windows-only — which is exactly why it survived.
      → **Green**, on Windows and unchanged on Linux.

- [x] **A manifest cites a requirement IRI that does not exist, and nothing checks.**
      `…/req/lws10-core/authz-token-validation-verification` is referenced by five
      `auth-oidc` manifests; the catalog entry is `authz-token-validation-checklist`.
      Consequences: EARL emits a `touchstone:verifies` triple pointing at nothing (this is
      the artifact intended for W3C implementation reports); coverage never counts those
      five; and `RunTools.strongestLevel` (`harness-mcp/.../RunTools.java:251`) silently
      returns `UNCLASSIFIED` for an uncatalogued IRI, so a MUST failure can stop counting
      toward `conformant`.
      *Fix:* correct the five manifests, then make a dangling IRI a **hard load-time
      failure** — validate `manifest.requirements ⊆ catalog` in `ManifestLoader`/`Harness`
      (or a build-time test), the same way the schema is enforced. This must land before
      P1: the re-baseline will invalidate more IRIs (see below), and today they would fail
      silently.
      *Files:* `manifests/auth-oidc/{alg-none-401,expired-token-401,unknown-key-401,wrong-audience-401,wrong-issuer-401}.yaml`.
      → **Fixed.** The five manifests now cite `authz-token-validation-checklist`, and
      `RequirementRefs` makes an unresolvable IRI refuse the run: `touchstone run` exits
      **2** (a configuration error, not a non-conformant server) and the MCP `start_run` /
      `run_one` tools decline it. `coverage` warns rather than failing, since it is a report
      and not a gate. `RequirementRefsTest` checks the shipped catalog against the shipped
      manifests on every build, so the next typo fails in CI rather than in an EARL file.
      Coverage rose 36 → 38 of 203: the corrected IRI now counts.

---

## P1 — conform to the 21 August 2026 draft

**Done 2026-09-02 (D-0040, D-0041, D-0042)**, except where an item says otherwise below.
The catalog is baselined on WD-lws10-core-20260821 and on the four published authentication
suites: 232 requirements, `clause_hash.py --check` clean, `check_drift.py` reporting no drift
and no dead anchor. `clean verify` is green across five modules; the core suite is 23
manifests, all passing against the reference server.

Two things are deliberately **not** done and are recorded as such: notification manifests and
fixture support (D-0041), and the `link:<rel>` bind extractor that would let a discovery test
follow the advertised storage URI instead of the registered one — still blocked on the frozen
schema, and now blocking six requirements rather than one (see P2).

One judgement is flagged for review rather than assumed settled: the Gate-1 seed
`container-representation-conneg` was replaced by `conneg-media-type-equivalence`, which
**carries the Approved status over** because only the wording of the obligation changed
(D-0040). Demoting it to Draft instead is a one-line change.

### 1. Re-baseline the core catalog

- [x] Archive `catalog/sources/WD-lws10-core-20260821.html` + `.clauses.json` beside the
      June snapshot, update the `catalog/lws10-core.ttl` header and every
      `touchstone:sourceDraft` to the new dated URL, re-run `clause_hash.py --update`.
- [x] **20 catalogued clauses no longer appear in the spec.** Deprecate or replace each.
      Two are editorial (`conformance-client-class`: "A LWS Client" → "An LWS Client";
      `access-endpoints-jsonld-payloads`: cross-reference renumbered 10.4.1 → 11.4.1) —
      re-hash and move on. The other 18 are substantive and listed under items 2–5.
- [x] **50 normative blocks are uncatalogued**, 29 of them the new §10 Notifications
      (item 6). Non-notification additions worth entries: the `StorageRoot` service
      requirement, `application/lws+cid` (×3 blocks), Media Type Equivalence (×2), and the
      two BCP 14 / client-conformance-class blocks (skip per D-0014's rule).
- [x] **5 requirements point at section anchors that 404 in the new draft**
      (`#content-negotiation` ×4, `#normative-json-ld-context` ×1). `report.md`'s
      per-requirement spec links are its stated reason for existing (D-0032) — a dead
      anchor turns "this MUST failed" back into something the reader cannot act on.
- [x] Add an anchor-liveness check to `check_drift.py` (every `touchstone:section` fragment
      must exist as an `id=` in the fetched draft). Two-line addition; it would have caught
      this class on the first re-fetch.

### 2. Storage description: rel, media type, context, services

The single largest behavioral change, and the harness has **no coverage of it at all**
today (no manifest, no `RefLwsServer` implementation).

- [x] `rel="https://www.w3.org/ns/lws#storageDescription"` → **`rel="https://www.w3.org/ns/lws#storage"`**,
      and the link target changed from *the storage description resource's URI* to **the
      canonical URI of the storage**. `storageDescription` occurs 0 times in the new draft.
      Affects `storage-description-link-header`, `link-storage-description-on-responses`,
      `create-401-storage-description-link`, and
      `harness-core/.../exec/LinkHeadersTest.java:23`.
- [x] Storage description media type `application/lws+json` → **`application/lws+cid`** (a
      specialization of a W3C Controlled Identifier document), and its `@context` **MUST be
      an array starting with `https://www.w3.org/ns/cid/v1` then
      `https://www.w3.org/ns/lws/v1`**. Affects `storage-description-lws-json`,
      `storage-description-representation`, `lws-media-type-required`.
- [x] `id` MUST now be **the canonical URI of the storage** (and the canonical URL of the
      CID document) — `storage-description-id-required`.
- [x] New MUST: the `service` set **MUST contain a `StorageRoot` service** whose
      `serviceEndpoint` is the storage root container. Replaces the old
      `StorageDescription`-service clause (`storage-description-self-describing`).
- [x] Write the first `core/` manifests for discovery, and teach `RefLwsServer` to serve a
      storage description. Without a fixture the self-test loop cannot prove these tests
      distinguish anything.

### 3. Bundle `cid/v1`; the LWS context lost its in-spec source

- [x] **`JsonLdContexts.BUNDLED` holds only `lws/v1`**
      (`harness-core/.../assertions/JsonLdContexts.java:38`). The moment a storage
      description is parsed, its `@context` array names `https://www.w3.org/ns/cid/v1` and
      the offline loader raises a hard `LOADING_REMOTE_CONTEXT_FAILED` — by design
      (D-0026), so the fix is to bundle it. `https://www.w3.org/ns/cid/v1` **resolves
      today** (200, `application/ld+json`, 3248 bytes), so copy it verbatim and record the
      sha256 the way `lws-v1.jsonld` is recorded.
- [x] **`https://www.w3.org/ns/lws/v1` is still a 404** (re-checked 2026-09-02), and §13 of
      the new draft no longer carries the context inline — it is now only a URL plus
      "TODO: include the JSON-LD context digest once the context document is finalized"
      (w3c/lws-protocol#216). The bundled `lws-v1.jsonld` therefore has **no normative
      source in the current draft**; it is a copy of a superseded one. Note this in the file
      header and in DECISIONS as an amendment to D-0026, and keep watching #216. Good
      candidate for WG feedback.
- [x] `Graphs.langFor` (`…/assertions/Graphs.java:65`) throws "no RDF reader for media type"
      on **`application/lws+cid`**; add it (and `application/linkset+json`, which §9.1 makes
      a MUST) before writing the tests that need them.

### 4. `mediaType` → `format` in contained resource descriptions

- [x] The spec renamed it; `mediaType` occurs 0 times in the new draft. Three places: the
      catalogued clause (`contained-mediatype-for-dataresources`), the bundled context
      (`touchstone/context/lws-v1.jsonld` maps `mediaType` → `as:mediaType` and defines no
      `format` term — so a conforming server's `format` is **silently dropped during JSON-LD
      expansion** and any graph assertion on it sees nothing), and `RefLwsServer.java:568`.
- [x] Add a manifest asserting `format` is present for `DataResource` members. There is no
      test for this property in either spelling today.

### 5. Content negotiation moved and was rewritten; `Vary: Accept` is new

- [x] The four §"Content Negotiation" clauses are replaced by **one** clause in §12.1.1
      Media Type Equivalence: "Servers MUST honor a request for any of these media types and
      MUST set the Content-Type…". `manifests/core/container-conneg.yaml` cites **five
      requirement IRIs, all five of which die in the re-baseline** — it is the manifest most
      exposed to item 1, and the reason the P0 dangling-IRI check must land first.
- [x] New SHOULD: container responses **SHOULD carry `Vary: Accept`**. Untested;
      `RefLwsServer` does not send it.
- [x] `container-model-pagination-jsonld` lost its normative content — "Containers MUST
      support pagination… Representations MUST use JSON-LD with a specific frame and
      normative context" became a pointer to §8.1 and §12. The pagination MUSTs still live
      in §12.1.2 and are catalogued there; retire the container-model entry rather than
      re-hashing it.

### 6. Notifications — a whole new normative section, zero coverage

- [x] §10 adds **29 normative blocks** (plus 3 in Security/Privacy Considerations).
      DESIGN.md §5.4 filed notifications under "future modules, drafts pending"; they are now
      in the core draft, so the core module is incomplete without them. Shape:
      - discovery: a storage that supports notifications MUST advertise a
        `NotificationService` in its storage description, with `serviceEndpoint` and a
        `subscriptionType` array;
      - subscription: authenticated POST to that endpoint, request/response bodies
        `application/lws+json`, required `type` + `topic` fields, response carries `type` +
        `subscription` URL;
      - **authorization (the security-relevant MUSTs):** a server MUST reject a subscription
        whose `topic` includes anything the subscriber cannot read, MUST NOT deliver a
        notification for a resource unreadable at event time, and MUST stop delivering when
        access is revoked;
      - envelope/activity data model: `type: "Notification"`, `storage`, `activity`,
        Activity Streams 2.0 activity objects, required activity types, optional batching.
- [ ] **Deferred (D-0041):** catalogued only, as SAML is (D-0024). Manifests and reference-server
      support still to do. The authorization MUSTs are the ones worth a negative matrix —
      they are where a plausible implementation leaks data.

### 7. Authentication suites are published; realign

- [x] All four suites now have dated `/TR/` versions (`lws10-authn-openid`, `-saml`,
      `-ssi-did-key` on 3 Aug 2026; `-ssi-cid` on 21 Aug 2026). D-0010's premise
      ("unofficial-proposal editor's drafts, no dated /TR/") is obsolete. Point
      `touchstone:section` / `sourceDraft` at `https://www.w3.org/TR/lws10-authn-*/#…`, as
      the core module already does.
- [x] **Re-extract `clauseText` from the rendered `/TR/` HTML.** The auth catalogs were
      extracted from unrendered ReSpec *source*, so stored clause text contains raw macros —
      e.g. `…controlled identifier document [[!CID-1.0]] with an id value…` where the
      published spec reads `[CID-1.0]`. That text is what `get_requirement`, the
      `requirement://` resource and every report matrix show a reader, and it makes
      `check_drift.py` report **6 false drifts** against the authoritative document (2 openid,
      1 saml, 2 ssi-cid, 1 ssi-did-key). Against the editor's drafts all four catalogs are
      clean, so this is a source-of-truth choice, not decay.
- [x] Also new: a `lws10-vocab` Group Note ("Linked Web Storage Vocabulary", 14 July 2026)
      that the repo does not reference. It defines the `https://www.w3.org/ns/lws#` terms the
      harness asserts on (`#storage`, `#Container`, `#PreferLinkRelations`). Worth pinning as
      a source even though it carries no BCP 14 clauses.
- [x] The suites' only other change since the July snapshots is prose added to the
      informative Security/Privacy Considerations sections — no normative movement. Refresh
      the snapshots so the next drift check is clean.

### 8. `Slug` is gone from the specification

- [x] `Slug` occurs **0 times** in the 21 August draft (6 times in the June one). The clause
      `create-slug-honored-if-no-conflict` is retired outright, and the sentence was removed
      from `create-server-managed-metadata-protected` and `uri-independent-of-hierarchy`
      ("client hints (e.g., the Slug header)" → "client hints").
- [x] The harness sends `Slug` in 13 core manifests and in `Containers.create`
      (`harness-core/.../exec/Containers.java:23`), and `RefLwsServer` honours it. Nothing
      *breaks* — every manifest binds `header:Location` rather than assuming the name — but
      the harness should stop asserting a mechanism the spec dropped. Decide: keep sending it
      as a tolerated hint (and say so), or remove it and accept opaque server-assigned names
      (which also makes run roots unrecognisable in D-0027's "left behind" warning).

---

## P2 — harness correctness and fidelity

**Done 2026-09-02 (D-0044, D-0045).** `clean verify` green across five modules, 120 tests;
the core suite is 24 manifests. Jetty resolves at one version everywhere; the trace scrub
reaches bodies and query strings; the manifest schema is `1-1-0` and the `link:<rel>`
extractor is reachable, so the Approved MUST D-0035 left uncovered now has a test.

- [x] **The `jetty.version` pin has no effect.** `<jetty.version>12.1.11</jetty.version>` is
      declared, but the effective POM resolves Jetty **12.1.8** in
      `harness-core`/`-fixtures`/`-cli` and **12.1.10** in `harness-mcp`
      (`Server: Jetty(12.1.8)` on the reference server; `dependency:tree` confirms both).
      Cause: `jena-bom` is imported **before** `jetty-bom` (`pom.xml:62` vs `:69`), and
      `org.apache.jena:jena:6.1.0` — jena-bom's parent, whose management an import carries —
      pins `jetty-bom` at 12.1.8. First import wins, so the later import is inert. The repo
      already hit this exact leak for logback and fixed it with an explicit pin
      (`pom.xml:131`); Jetty was missed. *Fix:* import `jetty-bom` first (or pin the Jetty
      artifacts explicitly), and add an enforcer/`dependency:tree` assertion so a pin that
      stops being effective fails the build instead of looking authoritative.
- [x] **CI never runs on the default branch.** `.github/workflows/ci.yml:5` triggers
      `push: branches: [main]`; the repository's default branch is **`master`**
      (`origin/HEAD -> origin/master`). Pull requests do run. D-0007's "green CI on main"
      acceptance is therefore still unmet for pushes. One-word fix.
- [x] **Conneg equivalence is weaker than the clause it tests.** The spec says the response
      body is *the same JSON-LD document* and only `Content-Type` varies;
      `AssertionEngine.conneg` (`:283`) asserts **graph isomorphism**, which a server can
      satisfy while returning a differently serialized body. Either assert byte identity (and
      say so in the manifest) or record why isomorphism is the deliberate weakening.
- [x] **Redaction covers headers only.** `Redaction.redactHeaders` strips six header names;
      request/response **bodies are truncated but never scanned**, and URLs are not scanned at
      all. The core spec's §5.2.3 token exchange puts a `subject_token` in a request body and
      an `access_token` in a response body — so the moment the authorization module grows a
      test, live credentials land in `run.json`, `report.html` and every MCP `get_trace`.
      DESIGN.md §7.2 says strip before anything leaves the harness. *Fix:* redact well-known
      credential-bearing JSON/form keys and `?access_token=`-style query parameters; extend
      `SENSITIVE` with `authentication-info`, `proxy-authenticate`, `dpop-nonce`, `api-key`.
      *File:* `harness-core/.../results/Redaction.java:17`.
- [x] **`link:<rel>` bind extractor is implemented but unreachable.** `Executor.bind:210`
      resolves a Link relation (`LinkHeaders`, RFC 8288, unit-tested), but the frozen manifest
      schema pins `bind` to `^(header:…|status|body)$`
      (`docs/manifest-schema/manifest.schema.json:73` and the resource copy). So the Approved
      MUST `linkset-accept-patch-advertised` stays uncovered and the code is dead. **Gate 2
      hard stop** (CLAUDE.md rule 4): widening the pattern is a schema revision — it needs a
      version bump to `…/manifest/1-1-0` and a decision entry. Raise it; the alternative
      (hard-coding a `.meta` path convention) tests one server, not the spec.
- [x] **Header `equals` inspects only the first value** while `matches` and `contains` inspect
      any (`AssertionEngine.java:82`). For a legally repeated field like `Link` that is the
      same trap D-0036 fixed for `matches`, one spelling later.
- [x] **An uncatalogued requirement IRI silently downgrades a verdict.**
      `RunTools.strongestLevel:251` returns `UNCLASSIFIED` when `catalog.find` misses, and
      `mustFailures` counts only `MUST` — so a typo'd IRI can make a failing MUST invisible to
      `get_run`'s `conformant` flag. Covered operationally by the P0 load-time check; also
      make the fallback loud (log, or treat unknown as MUST).
- [x] **`RefLwsServer` emits an inline `@context`, so the self-test loop cannot see context
      bugs.** It writes `{"@vocab": "https://www.w3.org/ns/lws#", …}` (`RefLwsServer.java:550`)
      while the spec's own example — and every real server — sends
      `"@context": "https://www.w3.org/ns/lws/v1"`. Term mappings differ
      (`totalItems`/`size`/`modified` resolve to `as:`/`schema:` under the real context, to
      `lws:` under `@vocab`), so the reference server and a live SUT produce **different graphs
      from identical data**. This is precisely the blind spot D-0026 describes, still open six
      phases on; now that the offline loader exists, the fixture can emit the IRI. Doing so is
      also the only way item 4's `format` mapping gets exercised.
      → **Fixed as part of P1** (D-0040): the reference server names its context by IRI, so the
      self-test loop exercises the offline loader on every run.

---

## P3 — hygiene, dependencies, docs

**Done 2026-09-02 (D-0046).** Dependencies refreshed (Boot 4.1.1, Logback 1.6.3, Jena 6.2.0
and eight patch bumps); the Action no longer interpolates a caller's input into the script
that writes the SSRF boundary, and it tells a misconfigured workflow apart from a
non-conformant server; the image runs as a non-root user, verified end to end at 24/24
through a host-owned bind mount; the MCP server binds loopback.

- [x] **Dependency drift** (checked against `repo1.maven.org` `maven-metadata.xml`,
      2026-09-02; stable releases only):
      Jena 6.1.0 → **6.2.0** · Spring Boot 4.0.7 → **4.1.1** · Spring AI 2.0.0 → **2.0.1** ·
      Logback 1.5.38 → **1.6.3** · BouncyCastle 1.85 → **1.85.2** · Jetty 12.1.11 →
      **12.1.12** (see the P2 pin bug first) · Jackson 2.22.1 → **2.22.2** · JUnit 6.1.2 →
      **6.1.3** · FreeMarker 2.3.34 → **2.3.35** · networknt json-schema-validator 3.0.6 →
      **3.0.7** · oauth2-oidc-sdk 11.38.1 → **11.38.2**. Unchanged: Nimbus JOSE+JWT 10.9.1,
      picocli 4.7.7, PDFBox 3.0.8, SLF4J 2.0.18, AssertJ 3.27.7 (4.0 still milestones),
      Titanium 1.7.0 (2.0 still milestones). Take the patch bumps freely; Boot 4.1 and
      Logback 1.6 are minor-line moves and deserve their own commit.
- [x] **Composite action interpolates an input straight into a shell heredoc.**
      `.github/actions/lws-conformance/action.yml` writes `baseUrl: ${{ inputs.target-url }}`
      into `targets.yaml` inside an unquoted heredoc in a `run:` block — GitHub's documented
      script-injection shape. Pass it via `env:` and reference `$TARGET_URL`. `targets.yaml`
      is the SSRF boundary (§7.1); it should not be writable by string interpolation.
- [x] The action also treats **any** non-zero CLI exit as non-conformance, including the CLI's
      exit **2** for configuration errors (unknown target, missing registry). A misconfigured
      workflow reports a failing server. Distinguish them.
- [x] **MCP server ships with no authentication and Boot's default all-interfaces bind.**
      DESIGN.md §7.5 asks for a Spring Security OAuth2 resource server *if hosted*; nothing in
      `application.yml` or the docs says which posture is intended. At minimum document
      "loopback only unless fronted", or bind `server.address: 127.0.0.1` by default. This
      process can fire hostile traffic at pre-registered targets.
- [x] **Dockerfile runs as root** — no `USER`. Add a non-root user to the runtime stage.
- [x] **Docs drift:** `manifests/README.md` says core has **12 tests** (there are **21**) and
      lists `auth-saml`/`auth-cid`/`auth-didkey` directories that do not exist;
      `catalog/README.md` still describes the core module as *WD 2026-06-22* and the auth
      suites as *editor's drafts*; `README.md` carries the module table **twice**
      ("## Modules" and "## Layout") with different wording.
      → **Fixed in P1**: counts, baselines and the duplicated table.
- [x] **Record the changed premise in `DECISIONS.md`** — D-0002 pinned the June WD as the
      baseline and CLAUDE.md rule 2 makes a moved premise a dated entry. → D-0037, extended by
      D-0040 to D-0042 as the P1 items landed.

---

## Verified healthy — no action

- Security invariants hold: targets resolve from `targets.yaml` by id only (CLI and MCP); no
  credential has ever been committed; `runs/` is git-ignored; MCP trace tools carry the
  untrusted-input label DESIGN.md §7.3 asks for.
- The Tomcat ban works: `harness-mcp`'s tree carries only `tomcat-embed-el` (the EL jar D-0011
  carved out), and the enforcer fails the build on regression.
- `harness-core` is Spring-free; CLI and MCP are genuinely thin over `Harness`/`Reports`.
- The offline JSON-LD document loader refuses every unbundled context IRI, as designed.
- The four auth catalogs show **zero drift** against their editor's drafts.
- The two schema copies (`docs/` and the classpath resource) are identical, and
  `SchemaSyncTest` keeps them so.
