# Coverage of the LWS 1.0 test definitions

Generated from the definitions; do not edit by hand. Baseline: LWS Protocol 1.0 WD
2026-09-21, the did:key, OpenID Connect and SAML suites of 2026-08-03, and the CID suite
of 2026-09-21.

## Summary

- **101 tests**: 84 MUST, 15 SHOULD, 2 MAY; 54 validation tests, 47 negative tests.
- **127 catalog requirements** cited. For comparison, the retired `manifests/` covered 48 of 232.
- **lws-test-suite:** all 27 of 27 tests are accounted for (table 1). The definitions change what those tests assert wherever it contradicts the 21 September draft.
- **manifests/ (retired, D-0055):** 32 of its 33 tests have a successor; the other one was dropped because its clause left the specification (table 2).

| Module | Tests | MUST | SHOULD | MAY |
|---|---:|---:|---:|---:|
| `auth/cid/manifest` | 6 | 6 | 0 | 0 |
| `auth/did_key/manifest` | 8 | 8 | 0 | 0 |
| `auth/oidc/manifest` | 6 | 6 | 0 | 0 |
| `auth/saml/manifest` | 3 | 3 | 0 | 0 |
| `core/access_grants` | 7 | 6 | 1 | 0 |
| `core/authorization_server` | 8 | 6 | 2 | 0 |
| `core/conditional_requests` | 6 | 4 | 2 | 0 |
| `core/containers` | 14 | 9 | 4 | 1 |
| `core/data_resources` | 14 | 9 | 4 | 1 |
| `core/discovery` | 5 | 4 | 1 | 0 |
| `core/linksets` | 6 | 5 | 1 | 0 |
| `core/notifications` | 1 | 1 | 0 | 0 |
| `core/storage_authorization` | 17 | 17 | 0 | 0 |

## 1. lws-test-suite → definitions

| lws-test-suite test | Definition(s) | What changed |
|---|---|---|
| `discovery-unauthorized-response-headers` | `core/discovery#discovery-unauthorized-response-headers`, `core/storage_authorization#getContainer-private-unauthorized` | Split. The 401 challenge (MUST) is getContainer-private-unauthorized; the Link on the 401 became SHOULD, with rel lws#storage replacing storageDescription. |
| `discovery-storage-description` | `core/discovery#discovery-storage-description` | application/lws+cid instead of lws+json. The URL comes from the lws#storage link, not /alice/description. Asserts the WD data model (CID @context array, StorageRoot service) instead of an exact body. |
| `discovery-get-links-storageDescription` | `core/discovery#discovery-get-links-storageDescription` | Asserts rel lws#storage to the storage URI, on GET and HEAD. |
| `getContainer` | `core/access_grants#getContainer-public-read`, `core/containers#getContainer` | Owner read: ETag, linkset/up/type/storage links, container properties. The public-read half is getContainer-public-read, via an access grant. |
| `getContainer-private-unauthorized` | `core/storage_authorization#getContainer-private-unauthorized` | Challenge parsed per RFC 9110 (any order, token or quoted values) and scheme Bearer checked. |
| `getContainer-authenticated-owner` | `core/storage_authorization#getContainer-authenticated-owner` | Essentially unchanged. |
| `createDataResource` | `core/data_resources#createDataResource` | No Slug-derived Location, .meta URL or Content-Length 47; Location and linkset are read from the response. rel=type (a SHOULD) is split out. |
| `createDataResource-unauthorized` | `core/storage_authorization#createDataResource-unauthorized` | Also checks that nothing was created. |
| `readDataResource` | `core/access_grants#readDataResource-public-read`, `core/data_resources#readDataResource` | Adds ETag, rel=type and the storage link; the body is byte-exact against a fixture whose size is measured, not assumed. The public half is readDataResource-public-read. |
| `updateDataResource` | `core/data_resources#updateDataResource` | Accepts 200 or 204, sends If-Match, verifies by reading back; no Link assertion on the PUT response. |
| `deleteDataResource` | `core/data_resources#deleteDataResource` | 204 (the WD's MUST) instead of 200; checks the resource is gone and delisted. |
| `deleteDataResource-unauthorized` | `core/storage_authorization#deleteDataResource-unauthorized` | Also checks that the resource survives. |
| `getLinkset` | `core/linksets#getLinkset` | Follows rel=linkset instead of GETting /.meta; checks media type, ETag and linkset shape. |
| `getContainer-containmentIntegrity` | `core/containers#getContainer-containmentIntegrity` | Matches the listing by content (some), not against a fixture listing ideas/ that no test created. |
| `authz-server-metadata-well-known` | `core/authorization_server#authz-server-metadata-well-known` | AS discovered from the 401 challenge; asserts RFC 8414 members instead of an exact fixture carrying the id-token typo. |
| `authz-token-exchange-valid` | `core/authorization_server#authz-token-exchange-valid` | Uses a did:key credential with the jwt token type; asserts RFC 6749/8693 response members (issued_token_type, no-store) and the RFC 9068 access token claims. |
| `authz-token-exchange-invalid-resource` | `core/authorization_server#authz-token-exchange-invalid-resource` | Accepts invalid_target (RFC 8693's SHOULD) or invalid_request. |
| `authz-expired-token-rejected` | `core/storage_authorization#authz-expired-token-rejected` | Identity alice-expired instead of an Authorization header alongside alice's own credentials. |
| `createContainer` | `core/containers#createContainer` | No body and no Content-Type (the WD's example); the name is read from Location. |
| `authn-didkey-valid-credential` | `auth/did_key/manifest#authn-didkey-valid-credential` | Token type jwt (the suite's MUST) instead of id_token; checks the access token's sub. |
| `authn-didkey-invalid-signature` | `auth/did_key/manifest#authn-didkey-invalid-signature` | Token type jwt, so the refusal is for the signature. |
| `authn-didkey-missing-credential` | `core/authorization_server#authz-token-exchange-missing-subject-token` | Moved to core as authz-token-exchange-missing-subject-token (suite-independent). |
| `authn-oidc-valid-id-token` | `auth/oidc/manifest#authn-oidc-valid-id-token` | The harness hosts the subject's CID document and its own OP, so the trust path is exercised end to end. |
| `authn-oidc-expired-id-token` | `auth/oidc/manifest#authn-oidc-expired-id-token` | As above, with an expired ID Token. |
| `authn-oidc-server-metadata-discovery` | `core/storage_authorization#getContainer-private-unauthorized` | Duplicate of the anonymous-401 test; folded into getContainer-private-unauthorized. |
| `authn-saml-valid-assertion` | `auth/saml/manifest#authn-saml-valid-assertion` | Gated on SamlTrust; the fixture paths that were broken are gone. |
| `authn-saml-invalid-signature` | `auth/saml/manifest#authn-saml-invalid-signature` | As above. |

Definitions with no lws-test-suite counterpart extend it. That is every test in table 3 with an empty *Mirrors* column.

## 2. Retired manifests/ → definitions

`touchstone run` executed these until the YAML-LD engine replaced them (D-0055).

| Retired manifest | Superseded by |
|---|---|
| `auth-oidc/alg-none-401` | `core/storage_authorization#authz-token-alg-none-rejected` |
| `auth-oidc/anonymous-request-401-challenge` | `core/storage_authorization#getContainer-private-unauthorized` |
| `auth-oidc/bad-signature-401` | `core/storage_authorization#authz-token-bad-signature-rejected` |
| `auth-oidc/expired-token-401` | `core/storage_authorization#authz-expired-token-rejected` |
| `auth-oidc/non-owner-403` | `core/storage_authorization#authz-non-owner-read-rejected` |
| `auth-oidc/unknown-key-401` | `core/storage_authorization#authz-token-unknown-key-rejected` |
| `auth-oidc/valid-token-access` | `core/storage_authorization#getContainer-authenticated-owner` |
| `auth-oidc/wrong-audience-401` | `core/storage_authorization#authz-token-wrong-audience-rejected` |
| `auth-oidc/wrong-issuer-401` | `core/storage_authorization#authz-token-wrong-issuer-rejected` |
| `core/conditional-get-304` | `core/conditional_requests#conditional-get-304` |
| `core/conditional-get-stale-validator-200` | `core/conditional_requests#conditional-get-stale-validator-200` |
| `core/conditional-if-match-mismatch-412` | `core/conditional_requests#conditional-put-stale-if-match-412` |
| `core/contained-resource-format` | `core/containers#contained-resource-format` |
| `core/contained-resource-type-values` | `core/containers#contained-resource-type-values` |
| `core/container-conneg` | `core/containers#container-conneg`, `core/containers#container-conneg-vary` |
| `core/container-containment-after-post` | `core/containers#getContainer-containmentIntegrity` |
| `core/container-create-basic` | `core/containers#createContainer` |
| `core/create-advertises-linkset-link` | `core/containers#createContainer`, `core/data_resources#createDataResource` |
| `core/create-in-missing-container-404` | `core/data_resources#create-in-missing-container-rejected`, `core/data_resources#create-in-missing-container-404` |
| `core/data-resource-get` | `core/data_resources#readDataResource` |
| `core/data-resource-range-request` | `core/data_resources#data-resource-range-request`, `core/data_resources#data-resource-range-unsatisfiable`, `core/data_resources#data-resource-accept-ranges` |
| `core/delete-non-empty-container-409` | `core/containers#delete-non-empty-container-409`, `core/containers#delete-container-recursive` |
| `core/delete-resource-updates-parent` | `core/data_resources#deleteDataResource` |
| `core/delete-updates-parent-etag` | `core/conditional_requests#delete-updates-parent-strong-etag` |
| `core/etag-on-head-and-container-listing` | `core/conditional_requests#conditional-get-container-304`, `core/containers#container-head-parity`, `core/data_resources#data-resource-head-parity` |
| `core/head-parity` | `core/data_resources#data-resource-head-parity` |
| `core/link-up-on-resources` | `core/data_resources#createDataResource`, `core/data_resources#readDataResource`, `core/data_resources#data-resource-head-parity` |
| `core/linkset-discovery-and-patch-advertisement` | `core/linksets#getLinkset`, `core/linksets#linkset-advertises-patch` |
| `core/patch-merge-patch-baseline` | `core/data_resources#patch-merge-patch-baseline` |
| `core/post-to-non-container-405` | `core/data_resources#post-to-non-container-405` |
| `core/put-replace-with-if-match` | `core/conditional_requests#update-changes-strong-etag`, `core/data_resources#updateDataResource` |
| `core/put-unconditional-428` | None. The 21 September 2026 draft removed the 428 MUST ("Clients SHOULD use conditional requests"), so this test failed conforming servers. |
| `core/storage-description-discovery` | `core/discovery#discovery-get-links-storageDescription`, `core/discovery#discovery-storage-description` |

## 3. All definitions

### `auth/cid/manifest`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `authn-cid-valid-credential` | Validation | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-unknown-kid` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-bad-signature` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-expired` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-alg-none` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-claims-mismatch` | Negative | MUST | Authentication, ReachableFixtures |  |

### `auth/did_key/manifest`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `authn-didkey-valid-credential` | Validation | MUST | Authentication | authn-didkey-valid-credential |
| `authn-didkey-invalid-signature` | Negative | MUST | Authentication | authn-didkey-invalid-signature |
| `authn-didkey-alg-none` | Negative | MUST | Authentication |  |
| `authn-didkey-expired` | Negative | MUST | Authentication |  |
| `authn-didkey-claims-mismatch` | Negative | MUST | Authentication |  |
| `authn-didkey-audience-excludes-as` | Negative | MUST | Authentication |  |
| `authn-didkey-missing-exp` | Negative | MUST | Authentication |  |
| `authn-didkey-missing-iat` | Negative | MUST | Authentication |  |

### `auth/oidc/manifest`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `authn-oidc-valid-id-token` | Validation | MUST | Authentication, ReachableFixtures | authn-oidc-valid-id-token |
| `authn-oidc-expired-id-token` | Negative | MUST | Authentication, ReachableFixtures | authn-oidc-expired-id-token |
| `authn-oidc-alg-none` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-oidc-bad-signature` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-oidc-untrusted-issuer` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-oidc-missing-azp` | Negative | MUST | Authentication, ReachableFixtures |  |

### `auth/saml/manifest`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `authn-saml-valid-assertion` | Validation | MUST | Authentication, SamlTrust | authn-saml-valid-assertion |
| `authn-saml-invalid-signature` | Negative | MUST | Authentication, SamlTrust | authn-saml-invalid-signature |
| `authn-saml-unsigned` | Negative | MUST | Authentication, SamlTrust |  |

### `core/access_grants`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `access-grant-endpoint-is-container` | Validation | MUST |  |  |
| `access-grant-create` | Validation | MUST | Authentication |  |
| `access-grant-revoke` | Validation | SHOULD | Authentication |  |
| `getContainer-public-read` | Validation | MUST | Authentication | getContainer |
| `readDataResource-public-read` | Validation | MUST | Authentication | readDataResource |
| `access-grant-authenticated-agent` | Validation | MUST | Authentication |  |
| `access-request-create` | Validation | MUST | Authentication |  |

### `core/authorization_server`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `authz-server-metadata-well-known` | Validation | MUST | Authentication | authz-server-metadata-well-known |
| `authz-metadata-subject-token-types` | Validation | SHOULD | Authentication |  |
| `authz-metadata-subject-identifier-types` | Validation | SHOULD | Authentication |  |
| `authz-token-exchange-valid` | Validation | MUST | Authentication | authz-token-exchange-valid |
| `authz-token-exchange-invalid-resource` | Negative | MUST | Authentication | authz-token-exchange-invalid-resource |
| `authz-token-exchange-missing-resource` | Negative | MUST | Authentication |  |
| `authz-token-exchange-missing-subject-token` | Negative | MUST | Authentication | authn-didkey-missing-credential |
| `authz-issued-token-accepted-by-storage` | Validation | MUST | Authentication |  |

### `core/conditional_requests`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `conditional-get-304` | Validation | SHOULD |  |  |
| `conditional-get-container-304` | Validation | SHOULD |  |  |
| `conditional-get-stale-validator-200` | Validation | MUST |  |  |
| `conditional-put-stale-if-match-412` | Negative | MUST |  |  |
| `update-changes-strong-etag` | Validation | MUST |  |  |
| `delete-updates-parent-strong-etag` | Validation | MUST |  |  |

### `core/containers`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `getContainer` | Validation | MUST |  | getContainer |
| `createContainer` | Validation | MUST |  | createContainer |
| `createContainer-type-link` | Validation | SHOULD |  |  |
| `getContainer-containmentIntegrity` | Validation | MUST |  | getContainer-containmentIntegrity |
| `container-total-items-accurate` | Validation | SHOULD |  |  |
| `contained-resource-type-values` | Validation | MUST |  |  |
| `contained-resource-format` | Validation | MUST |  |  |
| `contained-resource-size-modified` | Validation | SHOULD |  |  |
| `container-conneg` | Validation | MUST |  |  |
| `container-conneg-vary` | Validation | SHOULD |  |  |
| `container-head-parity` | Validation | MUST |  |  |
| `delete-empty-container` | Validation | MUST |  |  |
| `delete-non-empty-container-409` | Negative | MUST |  |  |
| `delete-container-recursive` | Validation | MAY |  |  |

### `core/data_resources`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `createDataResource` | Validation | MUST |  | createDataResource |
| `createDataResource-type-link` | Validation | SHOULD |  |  |
| `create-in-missing-container-rejected` | Negative | MUST |  |  |
| `create-in-missing-container-404` | Negative | SHOULD |  |  |
| `create-server-managed-links-protected` | Validation | MUST |  |  |
| `readDataResource` | Validation | MUST |  | readDataResource |
| `data-resource-head-parity` | Validation | MUST |  |  |
| `data-resource-range-request` | Validation | MUST |  |  |
| `data-resource-range-unsatisfiable` | Negative | SHOULD |  |  |
| `data-resource-accept-ranges` | Validation | MAY |  |  |
| `updateDataResource` | Validation | MUST |  | updateDataResource |
| `patch-merge-patch-baseline` | Validation | MUST |  |  |
| `deleteDataResource` | Validation | MUST |  | deleteDataResource |
| `post-to-non-container-405` | Negative | SHOULD |  |  |

### `core/discovery`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `discovery-get-links-storageDescription` | Validation | MUST |  | discovery-get-links-storageDescription |
| `discovery-link-storage-on-data-resource` | Validation | MUST |  |  |
| `discovery-storage-description` | Validation | MUST |  | discovery-storage-description |
| `discovery-storage-description-default-media-type` | Validation | MUST |  |  |
| `discovery-unauthorized-response-headers` | Negative | SHOULD | Authentication | discovery-unauthorized-response-headers |

### `core/linksets`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `getLinkset` | Validation | MUST |  | getLinkset |
| `linkset-advertises-patch` | Validation | MUST |  |  |
| `linkset-conditional-412` | Negative | MUST |  |  |
| `linkset-put-405-when-unsupported` | Negative | MUST |  |  |
| `linkset-removed-with-resource` | Validation | MUST |  |  |
| `linkset-patch-merge` | Validation | SHOULD |  |  |

### `core/notifications`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `notification-service-advertised` | Validation | MUST |  |  |

### `core/storage_authorization`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `getContainer-private-unauthorized` | Negative | MUST | Authentication | getContainer-private-unauthorized, discovery-unauthorized-response-headers, authn-oidc-server-metadata-discovery |
| `getContainer-authenticated-owner` | Validation | MUST | Authentication | getContainer-authenticated-owner |
| `createDataResource-unauthorized` | Negative | MUST | Authentication | createDataResource-unauthorized |
| `updateDataResource-unauthorized` | Negative | MUST | Authentication |  |
| `deleteDataResource-unauthorized` | Negative | MUST | Authentication | deleteDataResource-unauthorized |
| `authz-non-owner-read-rejected` | Negative | MUST | Authentication |  |
| `authz-non-owner-write-rejected` | Negative | MUST | Authentication |  |
| `authz-non-owner-delete-rejected` | Negative | MUST | Authentication |  |
| `authz-expired-token-rejected` | Negative | MUST | Authentication | authz-expired-token-rejected |
| `authz-token-bad-signature-rejected` | Negative | MUST | Authentication |  |
| `authz-token-alg-none-rejected` | Negative | MUST | Authentication |  |
| `authz-token-unknown-key-rejected` | Negative | MUST | Authentication |  |
| `authz-token-wrong-audience-rejected` | Negative | MUST | Authentication |  |
| `authz-token-wrong-issuer-rejected` | Negative | MUST | Authentication |  |
| `authz-token-not-yet-valid-rejected` | Negative | MUST | Authentication |  |
| `authz-token-issued-in-future-rejected` | Negative | MUST | Authentication |  |
| `authz-token-multiple-audiences-rejected` | Negative | MUST | Authentication |  |
