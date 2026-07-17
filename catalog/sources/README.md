# Spec snapshots (provenance)

Archived, unmodified copies of the spec drafts the catalog was extracted from,
plus the raw extractor output. Kept in-repo so clause-drift checks and catalog
review do not depend on the W3C site, and so every `touchstone:clauseHash` can
be re-derived from a byte-exact source.

| File | Source | Fetched |
|---|---|---|
| `WD-lws10-core-20260622.html` | https://www.w3.org/TR/2026/WD-lws10-core-20260622/ | 2026-07-16 |
| `WD-lws10-core-20260622.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-07-16 |
| `ED-lws10-authn-openid.html` | https://w3c.github.io/lws-protocol/lws10-authn-openid/ (editor's draft; no dated /TR/) | 2026-07-16 |
| `ED-lws10-authn-openid.clauses.json` | `tools/extractor/extract_clauses.py` over the above | 2026-07-16 |
| `ED-lws10-authn-ssi-did-key.html` | https://w3c.github.io/lws-protocol/lws10-authn-ssi-did-key/ (editor's draft) | 2026-07-17 |
| `ED-lws10-authn-ssi-cid.html` | https://w3c.github.io/lws-protocol/lws10-authn-ssi-cid/ (editor's draft) | 2026-07-17 |
| `ED-lws10-authn-saml.html` | https://w3c.github.io/lws-protocol/lws10-authn-saml/ (editor's draft) | 2026-07-17 |
| `ED-lws10-authn-ssi-*.clauses.json`, `ED-lws10-authn-saml.clauses.json` | extractor output over the above | 2026-07-17 |

The snapshot is © W3C and redistributed unmodified under the
[W3C Document License](https://www.w3.org/copyright/document-license/).
