# Catalog extraction tooling

Semi-automated pipeline for the requirements catalog (DESIGN.md §5.1): fetch a
spec draft, find BCP 14 keyword clauses, emit candidates for **human review** —
requirements never enter the catalog unreviewed.

## Scripts

- `extract_clauses.py <spec.html> <out.json>` — parses a ReSpec-rendered W3C
  draft and emits one JSON entry per text block that carries `rfc2119` keyword
  markup (section id stack, heading, keywords, normalized text). Output for the
  current baseline lives at `catalog/sources/WD-lws10-core-20260622.clauses.json`.
- `clause_hash.py --update|--check <catalog.ttl>...` — fills in / verifies the
  `touchstone:clauseHash` drift hashes (rule: DECISIONS.md D-0008). `--check`
  exits non-zero on mismatch.

## Phase 1 roadmap

- candidate emitter: clauses.json → reviewable Turtle stubs (post-Gate-1)
- drift alarm: re-fetch the editor's draft, re-extract, compare against stored
  clause hashes; fail the build with a "spec moved" report (DESIGN.md §8)
