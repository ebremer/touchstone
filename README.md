# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent
identity documents) so credential failure paths can be tested deterministically.

**Status: Phase 3 complete** — every `touchstone run` now persists
`runs/<runId>/{run.json, earl.ttl, junit.xml, report.html}`: W3C EARL assertions
(one per test, linked to catalog requirement IRIs), JUnit XML for CI, and a
FreeMarker HTML report whose per-requirement matrix links tests → requirements →
spec sections with MUST/SHOULD/MAY rollups and a §5.1 conformance verdict.
`touchstone diff <runA> <runB>` compares runs (regressions/fixes/added/removed;
exit 1 on regressions). Engine: manifest executor over frozen schema v1, full
assertion vocabulary, per-test isolation, 12 core manifests green against the
in-memory reference LWS server. Next: Phase 4, identity fixtures + the OIDC suite.
Read `DESIGN.md` (build brief and decision record), `DECISIONS.md` (deviation log),
and `CLAUDE.md` (session ground rules) before working on this repo.

## Layout

| Path | Purpose |
|---|---|
| `harness-core` | engine: catalog, manifests, executor, assertions, EARL/HTML reporting — no Spring |
| `harness-fixtures` | embedded Jetty fixture servers: OIDC issuer, SAML IdP, CID/did:key signer, identity-doc host |
| `harness-cli` | picocli front end |
| `harness-mcp` | Spring Boot + Spring AI MCP server; thin adapter over the core engine |
| `catalog/` | requirements catalog (Turtle), versioned per spec draft |
| `manifests/` | declarative test manifests (YAML) |
| `tools/` | catalog extraction/drift-check tooling |
| `docs/` | schema proposals, notes |

## Build

```
./mvnw -B verify
java -jar harness-cli/target/touchstone.jar --version
```

Requires JDK 21+.

## Run the conformance suite locally

```
./mvnw -q install -DskipTests                          # once, so -pl invocations resolve siblings
./mvnw -q -pl harness-fixtures exec:java -Dexec.args=4711   # terminal 1: reference LWS server
java -jar harness-cli/target/touchstone.jar run --target ref --module core
java -jar harness-cli/target/touchstone.jar coverage
```

Targets are pre-registered in `targets.yaml` (DESIGN.md §7.1) — commands accept
only target ids, never URLs.
