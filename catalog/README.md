# Requirements catalog

Turtle files, one per spec module, derived from **dated spec snapshots** (see
`sources/`). Every requirement has a stable IRI and records: level
(MUST/SHOULD/MAY), spec module, section anchor, verbatim clause text, and a
sha256 drift hash (normalization rule: DECISIONS.md D-0008), and the roles the clause
binds (`touchstone:appliesTo`, D-0076). Tests declare the requirement IRIs they verify;
coverage = requirements × tests, counted over the requirements a server run answers for.

| File | Content |
|---|---|
| `vocab/touchstone-vocab.ttl` | the catalog vocabulary (subclass of EARL's TestRequirement) |
| `lws10-core.ttl` | requirements for LWS Protocol 1.0 core (WD 2026-10-05) — 190 |
| `lws10-authn-openid.ttl` | OpenID Connect authentication suite (WD 2026-08-03) — 8 |
| `lws10-authn-ssi-cid.ttl` | Self-signed Identity using Controlled Identifiers (WD 2026-09-21) — 14 |
| `lws10-authn-saml.ttl` | SAML 2.0 authentication suite (WD 2026-08-03) — 7 |
| `lws10-notifications-webhook.ttl` | Webhook notification suite (editor's draft, not on /TR; snapshot rendered 2026-10-02) — 20 |
| `lws10-index.ttl` | Search and Type Index Services (editor's draft, not on /TR; snapshot rendered 2026-10-02) — 57 |
| `sources/` | archived spec snapshots + raw extraction output (provenance) |

296 requirements across six spec modules: 228 MUST, 30 SHOULD, 38 MAY. By the roles they
bind: Server 223, Client 75, AuthorizationServer 26, IdentityProvider 16, Receiver 4,
Specification 2 (a clause binding several roles counts in each); 248 bind a server or an
authorization server. The webhook
notification suite (D-0066) and the search and type index services (D-0067) are baselined on
editor's drafts: W3C has not published them, so their entries are Draft and `check_published.py` watches for publication. The
did:key suite, discontinued by W3C on 2026-09-29, is retired (D-0058).

Tooling lives in `tools/extractor/`. `check_drift.py` is the "spec moved" alarm — it
re-extracts a fetched draft and fails if any stored clause has vanished or any section
anchor no longer resolves. `check_published.py` runs it weekly in CI against the latest
version of every document on `/TR/`, and also fails when W3C has published a newer one.

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

**Re-baselined 2026-09-30** onto the 21 September 2026 core and CID WDs, the latest
versions W3C had published as of 28 September 2026 (DECISIONS.md D-0057). Thirteen core
blocks changed and the module went from 191 requirements to 190; the CID suite's normative
text did not change.

**Re-baselined 2026-10-05** onto the 5 October 2026 core WD, the latest version W3C had
published that day (DECISIONS.md D-0086). Three core blocks changed, all to make JSON Patch
(RFC 6902) the baseline patch format where they named JSON Merge Patch
(w3c/lws-protocol#255); the module still holds 190 requirements. The CID, OpenID Connect
and SAML drafts, and the editor's drafts of the webhook and index suites, did not change.
