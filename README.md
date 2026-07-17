# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (throwaway OIDC issuer, SAML IdP, self-signed key material, hosted agent
identity documents) so credential failure paths can be tested deterministically.

**Status: Phase 1 complete** — multi-module scaffold, full requirements catalog
(162 clauses of the 2026-06-22 core Working Draft, stable IRIs + drift hashes),
extraction/drift tooling, frozen manifest schema v1, and coverage computation
over the (still empty) test set. Next: Phase 2, the manifest executor.
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
