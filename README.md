# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent
identity documents) so credential failure paths can be tested deterministically.

**Status: Phase 2 complete** — declarative manifest executor over the frozen
schema v1, assertion engine (status / headers / JSON Pointer / graph containment /
isomorphism / SHACL / conneg equivalence), per-run + per-test isolation with a
provisioning-adapter SPI, and 12 core happy-path manifests passing against the
in-memory reference LWS server (DECISIONS.md D-0015) via `touchstone run` (parallel,
virtual threads) and a JUnit `@TestFactory` self-test loop. Coverage: 21 of 162
requirements. Next: Phase 3, reporting (EARL, HTML matrix, JUnit XML, run diff).
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
