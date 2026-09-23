# Harvesting the Solid specification-tests corpus

LWS descends from Solid Protocol 0.11, so much of the
[solid-contrib/specification-tests](https://github.com/solid-contrib/specification-tests)
scenario *content* applies. Per DESIGN.md §2 we **harvest the content, not the execution
model**: their scenarios are KarateDSL feature files; Touchstone ports the intent into
declarative YAML-LD definitions (the no-Karate decision, §2).

## Mapping

| Karate feature | Touchstone definition |
|---|---|
| `Background` container/resource setup | `prereqs`, inside the per-test `${test.container}` (isolation is the engine's) |
| `Given url` + method + `getAuthHeaders()` | a `request`, with `as: <identity>` |
| `Then status 201` / `status 4xx` | `statusCode` (exact, a list, or a class such as `"4xx"`) |
| RDF response parsed for containment | `json` pointer checks with `some`, `every` or `none` |
| `clients.alice` / `clients.bob` | the identities `alice` / `bob` |

## What is already ported

The twelve original `core/` tests were LWS-adapted CRUD, containment, conditional-request
and content-negotiation scenarios of the kind the corpus covers, re-expressed against the
WD-20260821 model (container listings + `Link rel="up"`, not `ldp:contains`; DECISIONS.md
D-0012). They were manifests; the definitions that superseded them are listed in
`definitions/COVERAGE.md`, table 2.

`core/data_resources#post-to-non-container-405` is a concrete, attributed port of the
`protocol/writing-resource` scenario "a POST must not mutate the tree beneath a
non-container", adapted to the LWS Operations model where POST is defined only against
containers.

## Not ported (Solid-specific, absent from the LWS core WD)

- PUT auto-creating intermediate containers (LWS create is POST-to-container with a
  server-assigned name).
- WAC/ACP access-control scenarios (LWS authorization is access grants, tested in
  `core/access_grants`).
- SPARQL-Update PATCH bodies (the LWS PATCH baseline is JSON Merge Patch; D-0012).

These become portable as the corresponding LWS modules mature; the mapping above is the
recipe.
