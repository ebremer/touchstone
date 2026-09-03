# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent
identity documents) so credential failure paths can be tested deterministically.

**Status: Phases 0–6 complete.** Touchstone ships as a **Docker image + GitHub
Action** so a third-party LWS server gets a conformance report by adding one workflow
file (`docs/distribution.md`) — verified end-to-end: the container runs the core suite
against a live server and emits the EARL/HTML/JUnit report. The **requirements
catalog** spans all five published spec modules (232 requirements, drift-hashed),
baselined on the **21 August 2026** core Working Draft and the published authentication
suites (DECISIONS.md D-0037/D-0040/D-0042). The
**did:key and CID** authentication suites are real Ed25519 self-signed-credential
fixtures with a negative matrix (valid verifies, every broken variant rejected); the
**SAML** module is catalogued with the OpenSAML fixture as a documented seam
(DECISIONS.md D-0024).

The engine underneath: a declarative manifest executor over a frozen schema, the full
assertion vocabulary (status / headers / JSON Pointer / graph containment /
isomorphism / SHACL / conneg), per-test isolation, EARL + HTML + JUnit reporting with
run diff, the OIDC issuer + secured/broken reference servers with the auth-oidc
negative matrix, and a Spring AI **MCP server** (streamable HTTP + stdio) exposing the
harness as tools. Three consumers — CLI, CI, MCP — over one engine.

Read `DESIGN.md` (build brief and decision record), `DECISIONS.md` (deviation log),
and `CLAUDE.md` (session ground rules) before working on this repo.

## Modules

| Path | What |
|---|---|
| `harness-core` | catalog, manifests, executor, assertions, reporting, run orchestration — no Spring |
| `harness-fixtures` | reference LWS server (open/secured/broken), OIDC issuer, did:key/CID credentials |
| `harness-cli` | picocli front end (`run`, `coverage`, `diff`) |
| `harness-mcp` | Spring AI MCP server over the core engine |
| `catalog/` | requirements catalog (Turtle), 5 spec modules, versioned per spec draft |
| `manifests/` | declarative test manifests (`core`, `auth-oidc`) |
| `tools/` | catalog extraction and spec-drift tooling |
| `docs/` | manifest schema, distribution, harvest method |

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
