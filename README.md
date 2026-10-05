# Touchstone

Conformance test harness for the W3C **Linked Web Storage (LWS)** protocol family.
Touchstone plays the client role against an LWS server (the system under test):
it fires HTTP requests and asserts on responses, and it runs controlled identity
fixtures (a throwaway OpenID Provider, a SAML identity provider, self-signed key material,
hosted agent identity documents) so credential failure paths can be tested
deterministically.

**Status: Phases 0–6 complete, running the YAML-LD definitions.** The tests are 101
YAML-LD definitions (`definitions/`, format 0.7.0, frozen; DECISIONS.md D-0070) that mirror
and extend the LWS test group's JSON-LD suite, follow the **5 October 2026** Working
Draft, and cover the core protocol and all three current authentication suites (OpenID
Connect, CID with HTTPS and did:key subjects, SAML) end to end. The engine that runs them implements
`definitions/EXECUTION.md` (D-0054); the YAML manifests it replaced are retired (D-0055).
Every build runs all 101 against a reference deployment, a storage server and an
authorization server, and against broken twins of both. Touchstone ships as a **Docker
image + GitHub Action**, so a third-party LWS server gets a conformance report by adding
one workflow file (`docs/distribution.md`). The **requirements catalog** spans all four
current spec modules, the webhook notification suite and the search and type index services (296 requirements, drift-hashed), baselined on the 5 October 2026
core Working Draft and the 21 September 2026 CID one, the latest W3C versions as of 5 October 2026
(D-0057, D-0086).

Three consumers, CLI, CI and MCP, over one engine: templates and derived variables,
prerequisites and access grants, RFC 8288 links and RFC 9110 challenges, JSON pointer,
JWT and content-negotiation expectations, per-test isolation, EARL + HTML + Markdown + PDF
+ JSON + JUnit reports with run diff, and a Spring AI **MCP server** (streamable HTTP +
stdio) exposing the harness as tools.

Read `DESIGN.md` (build brief and decision record), `DECISIONS.md` (deviation log),
and `CLAUDE.md` (session ground rules) before working on this repo.

## Modules

| Path | What |
|---|---|
| `harness-core` | catalog, the definitions loader and lint, the engine, identities and credentials, reporting — no Spring |
| `harness-fixtures` | reference LWS server (open/secured/broken), reference authorization server (and its broken twin), the reference deployment the self-test loop runs against |
| `harness-clients` | client-session service for testing LWS clients ([CLIENT-TESTING.md](CLIENT-TESTING.md)): a reference storage and authorization server per session, every request recorded; `java -jar harness-clients/target/touchstone-clients.jar` |
| `harness-cli` | picocli front end (`run`, `coverage`, `diff`) |
| `harness-mcp` | Spring AI MCP server over the core engine |
| `catalog/` | requirements catalog (Turtle), 5 spec modules, versioned per spec draft |
| `definitions/` | the tests: YAML-LD definitions (format 0.11.0, frozen), their execution contract and schema, and the client rules (`lws10/clients/`, `OBSERVATION.md`); mirror and extend lws-test-suite, and export to its JSON-LD (D-0047, D-0051, D-0053) |
| `tools/` | catalog extraction and spec-drift tooling (`extractor/`); checks and generators for `definitions/` (`definitions/`) |
| `docs/` | documentation site, published with GitHub Pages (Jekyll, just-the-docs): usage, architecture, CLI, reports, MCP, test authoring and distribution |

## Build

```
./mvnw -B verify
java -jar harness-cli/target/touchstone.jar --version
```

Requires JDK 21+.

## Run the conformance suite locally

```
./mvnw -q clean install -DskipTests                     # once, so -pl invocations resolve siblings
./mvnw -q -pl harness-fixtures exec:java -Dexec.args=4711   # terminal 1: reference LWS server
java -jar harness-cli/target/touchstone.jar run --target ref
java -jar harness-cli/target/touchstone.jar coverage
```

Against the open reference server the authentication tests are inapplicable. To run every
definition, start the secured reference deployment instead (`SecuredRefScenarioMain`; see
`docs/getting-started.md`).

Targets are pre-registered in `targets.yaml` (DESIGN.md §7.1) — commands accept
only target ids, never URLs.
