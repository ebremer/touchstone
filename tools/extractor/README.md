# Catalog extraction tooling

Semi-automated pipeline for the requirements catalog (DESIGN.md §5.1): fetch a
spec draft, find BCP 14 keyword clauses, emit candidates for **human review** —
requirements never enter the catalog unreviewed.

## Scripts

- `extract_clauses.py <spec.html> <out.json>` — parses a ReSpec-rendered W3C
  draft and emits one JSON entry per text block that carries `rfc2119` keyword
  markup (section id stack, heading, keywords, normalized text). Output for the
  current baseline lives at `catalog/sources/WD-lws10-core-20260622.clauses.json`.
- `emit_candidates.py <clauses.json> <curation.json> <catalog.ttl>` — turns the
  extraction plus the human curation file (slug + summary per block; the review
  record) into generated catalog entries. Every block must be seeded, skipped, or
  curated; levels derive from the strongest BCP 14 keyword (D-0014). Regeneration
  replaces everything below the marker line — never hand-edit generated entries.
- `clause_hash.py --update|--check <catalog.ttl>...` — fills in / verifies the
  `touchstone:clauseHash` drift hashes (rule: DECISIONS.md D-0008). `--check`
  exits non-zero on mismatch.
- `check_drift.py --spec <spec.html> <catalog.ttl>...` — the "spec moved" alarm
  (DESIGN.md §8): re-extracts the given draft and fails (exit 1) if any stored
  clause no longer appears; uncatalogued normative blocks are reported as info.
  Run it against a fresh fetch of the editor's draft to detect churn.
