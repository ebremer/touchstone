# Manifest JSON Schema v1 — proposal (Gate 2)

Status: **FROZEN v1 — approved by Erich on 2026-07-16 (DECISIONS.md D-0013).**
The `$id` `https://example.org/touchstone/schema/manifest/1-0-0` is fixed; any
later change bumps the version and gets a DECISIONS.md entry.

## Shape

One manifest = one test = metadata + an ordered list of HTTP **steps**, each with a
`request` (or `rawRequest`), an `expect` block of declarative assertions, and an
optional `bind` block exporting response parts as variables for later steps. This is
§5.2's sketch, refined and made strict (`additionalProperties: false` everywhere —
typos fail schema validation instead of silently passing tests).

## What changed relative to the §5.2 sketch, and why

- **`schemaVersion: 1`** (required): manifests self-describe their schema generation.
- **JSON-pointer assertions (`expect.json`)** are first-class. The June WD is
  JSON-first: container representations are `application/lws+json` /
  `application/ld+json` / `application/json`, with `items`/`totalItems` members and
  no `ldp:` vocabulary (DECISIONS.md D-0012). Clauses like "empty container ⇒ `items`
  is an empty array" are natively JSON-shape claims. Graph assertions remain (JSON-LD
  is RDF); both views of the same response are assertable.
- **`${test.container}`** replaces the sketch's `${run.container}`: §5.3 requires
  per-test isolation, so the executor allocates a fresh container per test inside the
  per-run root (`${run.root}`). Both are documented in `$defs.template`.
- **`rawRequest`** escape hatch: §4 plans hostile/malformed requests that polite
  clients normalize away (raw socket, Apache HttpClient 5 territory). Freezing the
  schema without this would force a schema bump at Phase 4.
- **The containment example is re-expressed** against the WD model (container listing
  + `Link rel="up"`), not `ldp#contains` — see `example-container-containment.yaml`.

## Assertion vocabulary → engine mapping (§5.2 checklist)

| §5.2 requirement | Schema element |
|---|---|
| status | `expect.status` (int or acceptable set) |
| header presence/value/regex | `expect.headers.<Name>` (`present`/`equals`/`matches`/`contains`) |
| negative assertions | `headers.<Name>.absent`, `graph.notContains`, `expect.status` with 4xx |
| graph-contains triples | `expect.graph.contains` (templates; `_:any` blank-node wildcard; literal objects with lang/datatype) |
| graph isomorphism vs fixture | `expect.graph.isomorphicTo` (Jena IsoMatcher) |
| SHACL shape conformance | `expect.graph.shacl` (Jena SHACL) |
| conneg round-trip w/ relative-IRI resolution | `expect.connegEquivalent.accepts` |
| abstract credentials (`as: alice`) | manifest-level `as` default + per-step override; reserved `anonymous` |
| body fixtures | `request.bodyRef`, `expect.body.equalsRef` — paths relative to the manifest file |

## Execution semantics fixed by this schema (not re-decided later)

- Variables: `${run.root}`, `${test.container}`, plus `bind` names
  (`header:<Name>` / `status` / `body`). Unresolvable variables are execution
  errors, not skips.
- Steps run strictly in order within a test; **tests never depend on each other**
  (§5.3). Parallelism is between tests.
- `timeoutMillis` per step; executor default applies otherwise. Retries stay OFF —
  a flaky SUT is a finding.
- `expect` omitted ⇒ the step is setup-only (still fails the test on transport
  errors).

## Deliberately deferred (would need a reviewed schema bump)

- SPARQL-based `bind` extraction from response graphs
- WebSocket/notification steps (future notifications module)
- capability *expressions* (v1: flat capability keys)
- multi-document manifests (v1: one test per file — keeps diffs and review atomic)

## WG alignment note

The strawman in w3c/lws-protocol PR #145 uses declarative `request`/`response`
pairs with `source` spec-links — structurally a subset of this schema (single step,
no graph/SHACL). A mechanical JSON-LD export of Touchstone manifests into that
vocabulary is a candidate Phase 3 deliverable (DECISIONS.md D-0006).
