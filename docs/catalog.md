---
title: Requirements catalog
nav_order: 10
description: "Every normative clause of the LWS drafts as a Turtle requirement with a stable IRI, a level and a drift hash, and how the catalog follows the draft."
---

# Requirements catalog
{: .no_toc }

The catalog is how Touchstone ties its results to the specification's text. Every
normative clause in the LWS drafts becomes a requirement with a stable IRI. Every test
cites the requirements it verifies. Coverage, the report matrix, the verdict and the EARL
links are all computed from those citations.

1. TOC
{:toc}

## What is in it

| File | Source document | Requirements |
|---|---|---:|
| `catalog/lws10-core.ttl` | LWS Protocol 1.0, Working Draft of 21 August 2026 | 191 |
| `catalog/lws10-authn-openid.ttl` | OpenID Connect authentication suite, WD 3 August 2026 | 8 |
| `catalog/lws10-authn-ssi-did-key.ttl` | Self-signed identity using did:key, WD 3 August 2026 | 12 |
| `catalog/lws10-authn-ssi-cid.ttl` | Self-signed identity using Controlled Identifiers, WD 21 August 2026 | 14 |
| `catalog/lws10-authn-saml.ttl` | SAML 2.0 authentication suite, WD 3 August 2026 | 7 |
| `catalog/vocab/touchstone-vocab.ttl` | The catalog vocabulary | |
| `catalog/sources/` | The archived draft snapshots and raw extraction output | |

The catalog holds 232 requirements in total: 188 MUST, 22 SHOULD and 22 MAY.

## A requirement

```turtle
req:create-post-201-location-links
    a touchstone:Requirement ;
    touchstone:level "MUST" ;
    touchstone:specModule "lws10-core" ;
    touchstone:section <https://www.w3.org/TR/lws10-core/#create-resource> ;
    touchstone:sourceDraft <https://www.w3.org/TR/2026/WD-lws10-core-20260821/#create-resource> ;
    touchstone:summary "POST create returns 201 with Location and atomic server-managed Link metadata (rel=up, rel=linkset)." ;
    touchstone:clauseText """On success, the server MUST return the 201 status code with the new URI in the Location header. ...""" ;
    touchstone:clauseHash "sha256-6808523b25cc42196caf104571dd803d918e6237c1778147d809e65ace11dd87" ;
    touchstone:status touchstone:Approved ;
    dcterms:created "2026-07-16"^^xsd:date .
```

| Property | Meaning |
|---|---|
| IRI | `https://example.org/touchstone/req/<module>/<slug>`. Stable: tests and reports cite it. |
| `touchstone:level` | `MUST`, `SHOULD` or `MAY`: the strongest BCP 14 keyword in the clause. `MUST NOT` counts as `MUST`. The exact wording is in the clause text. |
| `touchstone:specModule` | Which document the clause comes from, such as `lws10-core`. |
| `touchstone:section` | The section anchor in the undated `/TR/` URL. Reports link here. |
| `touchstone:sourceDraft` | The same anchor in the dated snapshot the clause was extracted from. |
| `touchstone:clauseText` | The clause, verbatim: Unicode NFC, whitespace collapsed. |
| `touchstone:clauseHash` | The SHA-256 of the normalised clause text, used to detect drift. |
| `touchstone:summary` | One sentence restating the clause as something testable. |
| `touchstone:status` | Where the entry is in review: `Draft` (extracted and curated, awaiting review), `Approved`, or `Deprecated` (the clause changed or disappeared; the entry is kept so historical reports still resolve). |

`touchstone:Requirement` is a subclass of `earl:TestRequirement`, so the requirement IRIs
fit directly into EARL reports.

## Levels and verdicts

A **MUST** failure makes a run non-conformant. **SHOULD** and **MAY** failures are
advisory. Optional features are gated by capabilities rather than failed. See
[Reports and verdicts](reports.md#the-verdict).

## How requirements get in

Requirements are extracted semi-automatically, and none enters the catalog without human
review. The tools are in `tools/extractor/`. They are plain Python 3 with only the
standard library.

| Script | Purpose |
|---|---|
| `extract_clauses.py <spec.html> <out.json>` | Parses a ReSpec-rendered W3C draft and emits every block that carries BCP 14 keyword markup, with its section, heading, keywords and normalised text. |
| `emit_candidates.py <clauses.json> <curation.json> <catalog.ttl>` | Turns the extraction plus a human curation file (a slug and a summary for each block) into catalog entries. Every block must be seeded, skipped or curated. The generated part of the file is replaced on each run, so it is never edited by hand. |
| `clause_hash.py --update/--check <catalog.ttl>...` | Fills in or verifies the clause hashes. `--check` exits non-zero on a mismatch. |
| `check_drift.py --spec <spec.html> <catalog.ttl>...` | The "spec moved" alarm, described below. |

The draft snapshot each file is based on is archived, unmodified, in `catalog/sources/`.
Every hash can be recomputed from a byte-exact source, and checking for drift does not
depend on the W3C site.

## Tracking the draft

LWS is a Working Draft and it changes. To check whether the catalog still matches the
current draft, fetch the draft and run the drift check:

```sh
curl -sL -o /tmp/lws10-core.html https://www.w3.org/TR/lws10-core/
python tools/extractor/check_drift.py --spec /tmp/lws10-core.html catalog/lws10-core.ttl
```

It re-extracts the draft and fails (exit `1`) if any catalogued clause no longer appears
or any section anchor no longer resolves. It also lists new normative blocks the catalog
does not hold. Run against the snapshot the catalog is based on, it passes:

```text
catalog entries checked: 191; spec normative blocks: 192; section anchors checked: 191
info: 1 uncatalogued normative block(s):
  [conformance] The key words MAY, MUST, MUST NOT, OPTIONAL, RECOMMENDED, REQUIRED, SHOULD, and SHOULD NOT...
no drift: every catalog clause still appears in the spec, and every section anchor resolves
```

When the draft moves, the catalog is **re-baselined**. The new snapshot replaces the old
one in `catalog/sources/`. Changed clauses are updated, new ones are added, and retired
ones are removed or deprecated. Manifests that cite affected requirements are reviewed.
Because re-baselining rewrites Approved entries, it is a reviewed change.

{: .important }
The catalog is currently based on the **21 August 2026** core draft. The
**21 September 2026** draft changed 14 catalogued clauses. Most changes are editorial,
but four affect existing tests:
- the requirement to answer an unconditional PUT with `428` is gone;
- support for conditional requests dropped from MUST to SHOULD;
- the MUST that a container's ETag changes after a member is deleted is gone;
- the SHOULD that a PUT yields a new ETag is gone.

Until the catalog is re-baselined, `core/put-unconditional-428` tests a MUST that the
current draft no longer contains, so a server that follows the September draft can fail
it.
