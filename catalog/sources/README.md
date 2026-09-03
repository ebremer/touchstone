# Spec snapshots (provenance)

Archived, unmodified copies of the spec drafts the catalog is extracted from, plus
the raw extractor output. Kept in-repo so clause-drift checks and catalog review do
not depend on the W3C site, and so every `touchstone:clauseHash` can be re-derived
from a byte-exact source.

One snapshot per module: the draft the catalog is *currently* baselined on. When a
draft moves, the catalog is re-baselined onto the new one and the superseded snapshot
is removed rather than kept alongside — git holds the history, and two snapshots in
this directory would leave it ambiguous which one a hash was derived from. The
2026-09-02 re-baseline (DECISIONS.md D-0037, D-0040, D-0042) retired the 22 June core
WD and the four editor's-draft auth snapshots.

| File | Source | Fetched |
|---|---|---|
| `WD-lws10-core-20260821.html` | https://www.w3.org/TR/2026/WD-lws10-core-20260821/ | 2026-09-02 |
| `WD-lws10-core-20260821.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-09-02 |
| `WD-lws10-core-20260821.curation.json` | human review record: slug + summary per extracted block | 2026-09-02 |
| `WD-lws10-authn-openid-20260803.html` | https://www.w3.org/TR/2026/WD-lws10-authn-openid-20260803/ | 2026-09-02 |
| `WD-lws10-authn-saml-20260803.html` | https://www.w3.org/TR/2026/WD-lws10-authn-saml-20260803/ | 2026-09-02 |
| `WD-lws10-authn-ssi-cid-20260821.html` | https://www.w3.org/TR/2026/WD-lws10-authn-ssi-cid-20260821/ | 2026-09-02 |
| `WD-lws10-authn-ssi-did-key-20260803.html` | https://www.w3.org/TR/2026/WD-lws10-authn-ssi-did-key-20260803/ | 2026-09-02 |
| `WD-lws10-authn-*.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-09-02 |

The auth snapshots are the **published** documents, not the ReSpec source pages the
July extraction used: the source page spells cross-references as `[[!CID-1.0]]` macros,
which is not what the specification says and not what a reader of a report should be
shown (D-0042).

The snapshots are © W3C and redistributed unmodified under the
[W3C Document License](https://www.w3.org/copyright/document-license/).
