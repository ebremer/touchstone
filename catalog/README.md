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
| `lws10-core.ttl` | requirements for LWS Protocol 1.0 core (WD 2026-10-19) — 215 |
| `lws10-authn-openid.ttl` | OpenID Connect authentication suite (WD 2026-10-15) — 8 |
| `lws10-authn-ssi-cid.ttl` | Self-signed Identity using Controlled Identifiers (WD 2026-10-15) — 14 |
| `lws10-authn-saml.ttl` | SAML 2.0 authentication suite (WD 2026-10-15) — 7 |
| `lws10-notifications-webhook.ttl` | Webhook notification suite (editor's draft, not on /TR; snapshot rendered 2026-10-02) — 20 |
| `lws10-index.ttl` | Search and Type Index Services (editor's draft, not on /TR; snapshot rendered 2026-10-02) — 57 |
| `sources/` | archived spec snapshots + raw extraction output (provenance) |

321 requirements across six spec modules: 254 MUST, 29 SHOULD, 38 MAY. By the roles they
bind: Server 247, Client 81, AuthorizationServer 26, IdentityProvider 16, Receiver 4,
Specification 2 (a clause binding several roles counts in each); 272 bind a server or an
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

**Re-baselined 2026-10-19** onto the 19 October 2026 core WD and the 15 October 2026 OpenID
Connect, SAML and CID WDs, the latest versions W3C had published that day (DECISIONS.md
D-0100). The core module grew from 190 requirements to 215. A 401's challenge names the
requested resource's protected resource metadata (RFC 9728) where it named the authorization
server and a realm, and links to the storage a token is for, with checks a client makes
before it asks for one (w3c/lws-protocol#256); content negotiation requires only
application/lws+json on containers, and a representation asked for in the LWS profile must
be RDF isomorphic to it (#253); and a new section lets clients move resources between
containers by changing their up link, as a MoveResource capability describes (#237). Six
entries are retired, among them the Approved seed `conneg-media-type-equivalence`, and 31
are new. The three authentication suites changed words only: every entry keeps its slug.
The editor's drafts of the webhook and index suites did not change.

**Not yet re-baselined** onto the core WD of 23 October 2026. Its one change
(w3c/lws-protocol#263) gives a create in a missing container a table of statuses, so
`create-post-missing-container-404` quotes a clause that draft removed (TODO.md).
