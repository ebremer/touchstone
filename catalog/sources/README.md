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
WD and the four editor's-draft auth snapshots; the 2026-09-30 one (D-0057) retired the
21 August core and CID WDs; the 2026-10-05 one (D-0086) retired the 21 September core WD.

| File | Source | Fetched |
|---|---|---|
| `WD-lws10-core-20261005.html` | https://www.w3.org/TR/2026/WD-lws10-core-20261005/ | 2026-10-05 |
| `WD-lws10-core-20261005.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-10-05 |
| `WD-lws10-core-20261005.curation.json` | human review record: slug + summary per extracted block | 2026-10-05 |
| `WD-lws10-authn-openid-20260803.html` | https://www.w3.org/TR/2026/WD-lws10-authn-openid-20260803/ | 2026-09-02 |
| `WD-lws10-authn-saml-20260803.html` | https://www.w3.org/TR/2026/WD-lws10-authn-saml-20260803/ | 2026-09-02 |
| `WD-lws10-authn-ssi-cid-20260921.html` | https://www.w3.org/TR/2026/WD-lws10-authn-ssi-cid-20260921/ | 2026-09-30 |
| `WD-lws10-authn-ssi-did-key-20260803.html` | https://www.w3.org/TR/2026/WD-lws10-authn-ssi-did-key-20260803/ | 2026-09-02 |
| `WD-lws10-authn-*.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-09-02 (CID: 2026-09-30) |
| `ED-lws10-notifications-webhook-20261002.html` | https://w3c.github.io/lws-protocol/lws10-notifications-webhook/, rendered by https://www.w3.org/publications/spec-generator/ (not published on /TR; normative text = w3c/lws-protocol `4e9481c`) | 2026-10-02 |
| `ED-lws10-notifications-webhook-20261002.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-10-02 |
| `ED-lws10-index-20261002.html` | https://w3c.github.io/lws-protocol/lws10-index/, rendered by https://www.w3.org/publications/spec-generator/ (not published on /TR; normative text = w3c/lws-protocol `3039b37`) | 2026-10-02 |
| `ED-lws10-index-20261002.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-10-02 |

The webhook suite and the search and type index services have no published version, so each snapshot is the editor's draft rendered by
W3C's own spec generator, which expands the ReSpec macros as publication would (D-0066). An
editor's draft is undated, so the file is named for the day it was rendered.

The auth snapshots are the **published** documents, not the ReSpec source pages the
July extraction used: the source page spells cross-references as `[[!CID-1.0]]` macros,
which is not what the specification says and not what a reader of a report should be
shown (D-0042).

The snapshots are © W3C and redistributed unmodified under the
[W3C Document License](https://www.w3.org/copyright/document-license/).
