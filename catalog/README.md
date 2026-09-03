# Requirements catalog

Turtle files, one per spec module, derived from **dated spec snapshots** (see
`sources/`). Every requirement has a stable IRI and records: level
(MUST/SHOULD/MAY), spec module, section anchor, verbatim clause text, and a
sha256 drift hash (normalization rule: DECISIONS.md D-0008). Tests declare the
requirement IRIs they verify; coverage = requirements × tests.

| File | Content |
|---|---|
| `vocab/touchstone-vocab.ttl` | the catalog vocabulary (subclass of EARL's TestRequirement) |
| `lws10-core.ttl` | requirements for LWS Protocol 1.0 core (WD 2026-08-21) — 191 |
| `lws10-authn-openid.ttl` | OpenID Connect authentication suite (WD 2026-08-03) — 8 |
| `lws10-authn-ssi-did-key.ttl` | Self-signed Identity using did:key (WD 2026-08-03) — 12 |
| `lws10-authn-ssi-cid.ttl` | Self-signed Identity using Controlled Identifiers (WD 2026-08-21) — 14 |
| `lws10-authn-saml.ttl` | SAML 2.0 authentication suite (WD 2026-08-03) — 7 |
| `sources/` | archived spec snapshots + raw extraction output (provenance) |

232 requirements across all five published spec modules: 188 MUST, 22 SHOULD, 22 MAY.

Tooling lives in `tools/extractor/`. `check_drift.py` is the "spec moved" alarm — it
re-extracts a fetched draft and fails if any stored clause has vanished or any section
anchor no longer resolves.

**Gate 1 closed (2026-07-16): the seeds are Approved** and the full core-draft
extraction lives in `lws10-core.ttl` (generated entries carry
`touchstone:status touchstone:Draft` pending batch review; the curation record
sits alongside the snapshot in `sources/`).

**Re-baselined 2026-09-02** from the 22 June 2026 core WD onto the 21 August 2026 one,
and from the four authentication editor's drafts onto their published versions
(DECISIONS.md D-0037, D-0040, D-0042). The core module grew from 162 requirements to
191: twenty clauses no longer appear in the draft and were replaced or retired, and
forty-nine are new — twenty-nine of them the Notifications section the June draft did
not have.
