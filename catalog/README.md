# Requirements catalog

Turtle files, one per spec module, derived from **dated spec snapshots** (see
`sources/`). Every requirement has a stable IRI and records: level
(MUST/SHOULD/MAY), spec module, section anchor, verbatim clause text, and a
sha256 drift hash (normalization rule: DECISIONS.md D-0008). Tests declare the
requirement IRIs they verify; coverage = requirements × tests.

| File | Content |
|---|---|
| `vocab/touchstone-vocab.ttl` | the catalog vocabulary (subclass of EARL's TestRequirement) |
| `lws10-core.ttl` | requirements for LWS Protocol 1.0 core (WD 2026-06-22) |
| `sources/` | archived spec snapshots + raw extraction output (provenance) |

Tooling lives in `tools/extractor/`.

**Gate 1 closed (2026-07-16): the 15 seeds are Approved and the full core-draft
extraction lives in `lws10-core.ttl`** (generated entries carry
`touchstone:status touchstone:Draft` pending batch review; the curation record
sits alongside the snapshot in `sources/`).
