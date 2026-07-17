# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent
identity documents) so credential failure paths can be tested deterministically.

**Status: Phase 4 complete** — the harness now owns an ephemeral **OIDC issuer**
(Nimbus): discovery + JWKS + minting of valid RFC 9068 access tokens and
deliberately broken variants (expired, wrong audience/issuer, bad signature,
unknown key, `alg=none`, mid-session key rotation). The reference LWS server gains
`SECURED` mode (validates Bearer tokens against the issuer; 401 + conforming
WWW-Authenticate, 403 for a valid non-owner) and a `BROKEN` twin. Nine `auth-oidc`
negative-matrix manifests **pass against the compliant server and fail against the
broken one** — the Phase 4 acceptance criterion, asserted in the self-test loop and
verified end-to-end through `touchstone run --module auth-oidc`.

Earlier phases: multi-module scaffold, 170-clause requirements catalog (core +
auth-oidc) with drift hashing, frozen manifest schema v1, the declarative executor
and assertion engine, and reporting (`runs/<id>/{run.json, earl.ttl, junit.xml,
report.html}` + `touchstone diff`). Next: Phase 5, the MCP server.
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
