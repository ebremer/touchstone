# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent
identity documents) so credential failure paths can be tested deterministically.

**Status: Phase 5 complete** — `harness-mcp` is a Spring AI MCP server (streamable
HTTP on Jetty, endpoint `/mcp`, plus a stdio profile) exposing the harness as tools
over the identical core engine: `list_requirements` / `get_requirement`,
`list_tests`, `coverage`, `start_run` (async on virtual threads, progress
notifications), `get_run`, `get_failures` (paged), `get_trace` (one redacted
exchange at a time), `diff_runs`, `run_one`, plus the `report://{run}/earl` and
`requirement://…` resources and `triage_run` / `draft_test` prompts. Targets are
referenced by id only (the §7.1 SSRF boundary); traces are redacted server-side and
labelled untrusted. A real MCP client drives start → watch → page → pull-trace
end-to-end in the test suite — the Phase 5 acceptance criterion.

Earlier phases: multi-module scaffold, 170-clause requirements catalog (core +
auth-oidc) with drift hashing, frozen manifest schema v1, the declarative executor
and assertion engine, reporting (`runs/<id>/{run.json, earl.ttl, junit.xml,
report.html}` + `touchstone diff`), and the OIDC issuer + secured/broken reference
servers with the auth-oidc negative matrix. Next: Phase 6, distribution + the
remaining auth suites.
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
