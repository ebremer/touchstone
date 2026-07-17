# Harvesting the Solid specification-tests corpus

LWS descends from Solid Protocol 0.11, so much of the
[solid-contrib/specification-tests](https://github.com/solid-contrib/specification-tests)
scenario *content* applies. Per DESIGN.md §2 we **harvest the content, not the execution
model**: their scenarios are KarateDSL feature files; Touchstone ports the intent into
declarative manifests (the no-Karate decision, §2).

## Mapping

| Karate feature | Touchstone manifest |
|---|---|
| `Background` container/resource setup | per-run `${run.root}` + per-test `${test.container}` isolation (executor) |
| `Given url` + method + `getAuthHeaders()` | a `request` step with `as: <identity>` |
| `Then status 201` / `status 4xx` | `expect.status` (exact or a set) |
| RDF response parsed for containment | `expect.json` pointers and/or `expect.graph.contains` |
| `clients.alice` / `clients.bob` | abstract identities `alice` / `bob` |

## What is already ported

The twelve original `core/` manifests are LWS-adapted CRUD, containment, conditional-
request, and content-negotiation scenarios of the kind the corpus covers, re-expressed
against the WD-20260622 model (container listings + `Link rel="up"`, not `ldp:contains`
— DECISIONS.md D-0012).

`core/post-to-non-container-405.yaml` is a concrete, attributed port of the
`protocol/writing-resource` scenario "a POST must not mutate the tree beneath a
non-container", adapted to the LWS Operations model where POST is defined only against
containers.

## Not ported (Solid-specific, absent from the LWS core WD)

- PUT auto-creating intermediate containers (LWS create is POST-to-container with a
  server-assigned name).
- WAC/ACP access-control scenarios (LWS authorization is a separate, still-draft module).
- SPARQL-Update PATCH bodies (the LWS PATCH baseline is JSON Merge Patch — D-0012).

These become portable as the corresponding LWS modules mature; the mapping above is the
recipe.
