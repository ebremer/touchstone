# Coverage of the LWS 1.0 test definitions

Generated from the definitions; do not edit by hand. Baseline: LWS Protocol 1.0 WD
2026-09-21, the OpenID Connect and SAML suites of 2026-08-03, and the CID suite of
2026-09-21. The did:key suite was discontinued on 2026-09-29; its tests run under the CID suite.

## Summary

- **204 tests**: 162 MUST, 31 SHOULD, 11 MAY; 129 validation tests, 75 negative tests.
- **53 client rules** (`clients/`, judged by client sessions; OBSERVATION.md): 41 MUST, 10 SHOULD, 2 MAY. They cite 64 of the 79 requirements that bind a client or a receiver; the other 15 are not judged, each for a reason section 4 gives: 11 permissions a client cannot break, 3 obligations that do not show in what a client sends, and the conformance class itself (sections 4 and 5).
- **264 catalog requirements** cited, 225 of the 248 that bind a server or an authorization server (section 4). For comparison, the retired `manifests/` covered 48 of 232.
- **lws-test-suite:** all 27 of 27 tests are accounted for (table 1). The definitions change what those tests assert wherever it contradicts the 5 October draft.
- **manifests/ (retired, D-0055):** 31 of its 33 tests have a successor; the other 2 were dropped because the specification no longer says what they tested (table 2).

| Module | Tests | MUST | SHOULD | MAY |
|---|---:|---:|---:|---:|
| `auth/cid/manifest` | 22 | 22 | 0 | 0 |
| `auth/oidc/manifest` | 6 | 6 | 0 | 0 |
| `auth/saml/manifest` | 3 | 3 | 0 | 0 |
| `core/access_grants` | 17 | 13 | 2 | 2 |
| `core/authorization_server` | 9 | 7 | 2 | 0 |
| `core/conditional_requests` | 9 | 4 | 5 | 0 |
| `core/containers` | 18 | 11 | 5 | 2 |
| `core/data_resources` | 17 | 10 | 6 | 1 |
| `core/discovery` | 7 | 6 | 1 | 0 |
| `core/linksets` | 9 | 8 | 1 | 0 |
| `core/notifications` | 15 | 14 | 1 | 0 |
| `core/pagination` | 5 | 4 | 1 | 0 |
| `core/storage_authorization` | 17 | 17 | 0 | 0 |
| `index/manifest` | 39 | 29 | 6 | 4 |
| `notifications/webhook/manifest` | 11 | 8 | 1 | 2 |

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
| `authn-didkey-valid-credential` | `auth/cid/manifest#authn-cid-didkey-valid-credential` | W3C discontinued the did:key suite (29 September 2026) in favour of the CID suite, so this is a CID test with a did:key subject: token type jwt (the CID suite's MUST) instead of id_token, and a kid naming the DID document's verification method; checks the access token's sub. |
| `authn-didkey-invalid-signature` | `auth/cid/manifest#authn-cid-didkey-invalid-signature` | A CID test with a did:key subject, as above; token type jwt, so the refusal is for the signature. |
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
| `core/patch-merge-patch-baseline` | None. The 5 October 2026 draft made JSON Patch the baseline patch format in place of JSON Merge Patch; `core/data_resources#patch-json-patch-baseline` tests the new one. |
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
| `authn-cid-document-id-mismatch` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-key-not-for-authentication` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-key-revoked` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-foreign-controller` | Negative | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-referenced-method` | Validation | MUST | Authentication, ReachableFixtures |  |
| `authn-cid-didkey-valid-credential` | Validation | MUST | Authentication | authn-didkey-valid-credential |
| `authn-cid-didkey-invalid-signature` | Negative | MUST | Authentication | authn-didkey-invalid-signature |
| `authn-cid-didkey-alg-none` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-expired` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-claims-mismatch` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-audience-excludes-as` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-missing-exp` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-missing-iat` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-missing-subject` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-missing-issuer` | Negative | MUST | Authentication |  |
| `authn-cid-didkey-missing-client-id` | Negative | MUST | Authentication |  |

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
| `access-grant-extra-properties-accepted` | Validation | MAY | Authentication |  |
| `access-grant-inbox-notified` | Validation | SHOULD | Authentication, ReachableFixtures |  |
| `access-request-create` | Validation | MUST | Authentication |  |
| `access-grant-document-shape` | Validation | MUST | Authentication |  |
| `access-grant-incomplete-refused` | Negative | MUST | Authentication |  |
| `access-grant-constraints-all-satisfied` | Validation | MUST | Authentication |  |
| `access-grant-left-operands-accepted` | Validation | MUST | Authentication |  |
| `access-grant-constraint-format` | Validation | MUST | Authentication |  |
| `access-grant-constraint-type` | Validation | MUST | Authentication |  |
| `access-grant-constraint-client` | Validation | MUST | Authentication |  |
| `container-listing-filtered-metadata` | Validation | MAY | Authentication |  |

### `core/authorization_server`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `authz-server-metadata-well-known` | Validation | MUST | Authentication | authz-server-metadata-well-known |
| `authz-metadata-subject-token-types` | Validation | SHOULD | Authentication |  |
| `authz-metadata-subject-token-types-are-uris` | Validation | MUST | Authentication |  |
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
| `conditional-delete-stale-if-match-412` | Negative | SHOULD |  |  |
| `update-changes-strong-etag` | Validation | MUST |  |  |
| `delete-updates-parent-strong-etag` | Validation | MUST |  |  |
| `conditional-get-if-modified-since-304` | Validation | SHOULD |  |  |
| `conditional-get-if-unmodified-since-412` | Negative | SHOULD |  |  |

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
| `container-conneg-weighted` | Validation | MUST |  |  |
| `container-head-parity` | Validation | MUST |  |  |
| `delete-empty-container` | Validation | MUST |  |  |
| `delete-non-empty-container-409` | Negative | MUST |  |  |
| `delete-container-recursive` | Validation | MAY |  |  |
| `delete-container-recursive-deep` | Validation | MAY |  |  |
| `containment-hierarchy-consistent` | Validation | MUST |  |  |
| `container-ld-json-lws-profile` | Validation | SHOULD |  |  |

### `core/data_resources`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `createDataResource` | Validation | MUST |  | createDataResource |
| `createDataResource-type-link` | Validation | SHOULD |  |  |
| `create-post-twice-distinct` | Validation | SHOULD |  |  |
| `create-in-missing-container-rejected` | Negative | MUST |  |  |
| `create-in-missing-container-404` | Negative | SHOULD |  |  |
| `create-server-managed-links-protected` | Validation | MUST |  |  |
| `readDataResource` | Validation | MUST |  | readDataResource |
| `data-resource-head-parity` | Validation | MUST |  |  |
| `data-resource-range-request` | Validation | MUST |  |  |
| `data-resource-range-unsatisfiable` | Negative | SHOULD |  |  |
| `data-resource-accept-ranges` | Validation | MAY |  |  |
| `updateDataResource` | Validation | MUST |  | updateDataResource |
| `patch-json-patch-baseline` | Validation | MUST |  |  |
| `patch-json-patch-atomic` | Negative | MUST |  |  |
| `deleteDataResource` | Validation | MUST |  | deleteDataResource |
| `post-to-non-container-405` | Negative | SHOULD |  |  |
| `error-problem-details` | Validation | SHOULD |  |  |

### `core/discovery`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `discovery-get-links-storageDescription` | Validation | MUST |  | discovery-get-links-storageDescription |
| `discovery-link-storage-on-data-resource` | Validation | MUST |  |  |
| `discovery-storage-description` | Validation | MUST |  | discovery-storage-description |
| `discovery-storage-description-default-media-type` | Validation | MUST |  |  |
| `discovery-unauthorized-response-headers` | Negative | SHOULD | Authentication | discovery-unauthorized-response-headers |
| `discovery-storage-root-is-container` | Validation | MUST |  |  |
| `discovery-storage-description-ids-are-uris` | Validation | MUST |  |  |

### `core/linksets`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `getLinkset` | Validation | MUST |  | getLinkset |
| `linkset-advertises-patch` | Validation | MUST |  |  |
| `linkset-conditional-412` | Negative | MUST |  |  |
| `linkset-put-405-when-unsupported` | Negative | MUST |  |  |
| `linkset-removed-with-resource` | Validation | MUST |  |  |
| `linkset-ready-at-create` | Validation | MUST |  |  |
| `linkset-up-not-redirected` | Validation | MUST |  |  |
| `linkset-patch-stays-linkset` | Validation | MUST |  |  |
| `linkset-patch-json-patch` | Validation | SHOULD |  |  |

### `core/notifications`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `notification-service-advertised` | Validation | MUST |  |  |
| `subscription-create` | Validation | MUST |  |  |
| `subscription-missing-type-refused` | Negative | MUST |  |  |
| `subscription-unadvertised-type-refused` | Negative | MUST |  |  |
| `subscription-missing-topic-refused` | Negative | MUST |  |  |
| `subscription-unreadable-topic-refused` | Negative | MUST | Authentication |  |
| `subscription-after-revocation-refused` | Negative | MUST | Authentication |  |
| `notification-delivered-create` | Validation | MUST | ReachableFixtures |  |
| `notification-delivered-update` | Validation | MUST | ReachableFixtures |  |
| `notification-delivered-delete` | Validation | MUST | ReachableFixtures |  |
| `notification-not-delivered-for-unreadable-resource` | Negative | MUST | Authentication, ReachableFixtures |  |
| `notification-stops-after-revocation` | Negative | MUST | Authentication, ReachableFixtures |  |
| `notification-actor-omitted` | Validation | SHOULD | ReachableFixtures |  |
| `notification-delivered-in-subcontainer` | Validation | MUST | ReachableFixtures |  |
| `notification-scope-data-resource-only` | Validation | MUST | ReachableFixtures |  |

### `core/pagination`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `pagination-first-page` | Validation | MUST |  |  |
| `pagination-next-page` | Validation | MUST |  |  |
| `pagination-single-page` | Validation | MUST |  |  |
| `pagination-last-page` | Validation | MUST |  |  |
| `pagination-totalitems-all-pages` | Validation | SHOULD |  |  |

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

### `index/manifest`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `index-services-advertised` | Validation | MUST | Authentication |  |
| `type-index-lists-readable-types` | Validation | MUST | Authentication |  |
| `type-index-omits-unreadable-type` | Validation | MUST | Authentication |  |
| `type-index-not-shared` | Validation | MUST | Authentication |  |
| `type-search-by-type` | Validation | MUST | Authentication |  |
| `type-search-and-or` | Validation | MUST | Authentication |  |
| `type-search-native-classes` | Validation | MUST | Authentication |  |
| `type-search-no-match` | Validation | MUST | Authentication |  |
| `type-search-duplicate-groups` | Validation | MUST | Authentication |  |
| `type-search-at-members-ignored` | Validation | MUST | Authentication |  |
| `type-search-empty-key-absent` | Validation | MUST | Authentication |  |
| `type-search-empty-group-rejected` | Negative | MUST | Authentication |  |
| `type-search-type-not-array-rejected` | Negative | MUST | Authentication |  |
| `type-search-bad-element-rejected` | Negative | MUST | Authentication |  |
| `type-search-malformed-json-rejected` | Negative | MUST | Authentication |  |
| `type-search-relative-iri-rejected` | Negative | MUST | Authentication |  |
| `type-search-missing-content-type-rejected` | Negative | MUST | Authentication |  |
| `type-search-unsupported-format-rejected` | Negative | MUST | Authentication |  |
| `type-search-unsupported-format-accept-query` | Negative | SHOULD | Authentication |  |
| `type-search-accept-query-advertised` | Validation | SHOULD | Authentication |  |
| `type-search-not-acceptable` | Negative | MUST | Authentication |  |
| `type-search-unindexed-relation` | Validation | MUST | Authentication |  |
| `type-search-structural-relation-not-indexed` | Validation | MUST | Authentication |  |
| `type-search-authorization-filtered` | Validation | MUST | Authentication |  |
| `type-search-revoked-not-shown` | Validation | MUST | Authentication |  |
| `type-search-not-shared` | Validation | MUST | Authentication |  |
| `type-search-type-from-link-header` | Validation | SHOULD | Authentication |  |
| `type-search-type-from-content` | Validation | MAY | Authentication |  |
| `type-search-reflects-update` | Validation | SHOULD | Authentication |  |
| `type-search-reflects-delete` | Validation | SHOULD | Authentication |  |
| `type-search-vary-accept` | Validation | MUST | Authentication |  |
| `type-search-safe` | Validation | MAY | Authentication |  |
| `type-search-next-page` | Validation | MUST | Authentication |  |
| `type-search-relation-filter` | Validation | MAY | Authentication |  |
| `type-search-relation-cnf` | Validation | MAY | Authentication |  |
| `type-search-relation-from-link-header` | Validation | SHOULD | Authentication |  |
| `type-search-too-complex` | Validation | MUST | Authentication |  |
| `type-search-unrecognized-page-link` | Negative | MUST | Authentication |  |
| `type-search-content-location-protected` | Validation | MUST | Authentication |  |

### `notifications/webhook/manifest`

| Test | Type | Level | Requires | Mirrors |
|---|---|---|---|---|
| `webhook-subscription-response` | Validation | MUST | ReachableFixtures |  |
| `webhook-subscription-expires-supported` | Validation | MUST | ReachableFixtures |  |
| `webhook-subscription-listed` | Validation | MUST | Authentication, ReachableFixtures |  |
| `webhook-subscription-get-delete` | Validation | MUST | ReachableFixtures |  |
| `webhook-delivery-lws-json` | Validation | MUST | ReachableFixtures |  |
| `webhook-delivery-signed` | Validation | SHOULD | ReachableFixtures |  |
| `webhook-signature-components` | Validation | MUST | ReachableFixtures |  |
| `webhook-signing-key-published` | Validation | MUST | ReachableFixtures |  |
| `webhook-signature-verifies` | Validation | MUST | ReachableFixtures |  |
| `webhook-delivery-retried` | Validation | MAY | ReachableFixtures |  |
| `webhook-subscription-deactivated` | Validation | MAY | ReachableFixtures |  |

## 4. Requirements by role

Each catalog requirement names the roles it binds (`touchstone:appliesTo`, D-0076). One that binds
several roles is counted in each. Server runs answer for the Server and AuthorizationServer rows;
client sessions ([CLIENT-TESTING.md](../CLIENT-TESTING.md)) answer for the Client and Receiver rows.

| Role | Requirements | MUST | SHOULD | MAY | Cited by a test | Cited by a client rule |
|---|---:|---:|---:|---:|---:|---:|
| Server | 223 | 167 | 24 | 32 | 201 | 31 |
| AuthorizationServer | 26 | 22 | 2 | 2 | 24 | 2 |
| Client | 75 | 54 | 8 | 13 | 64 | 60 |
| IdentityProvider | 16 | 15 | 1 | 0 | 16 | 6 |
| Receiver | 4 | 4 | 0 | 0 | 4 | 4 |
| Specification | 2 | 2 | 0 | 0 | 2 | 0 |

### Client and receiver requirements

What a client session can judge: the inventory of CLIENT-TESTING.md section 11. *Also binds*
names the other roles of a clause that binds more than one; *Cited by* names the server tests that
cite it, as a premise or for its server half; *Judged by* names the client rules that cite it, or
says why none does: a permission a client cannot break, an obligation that does not show in what a
client sends (unobservable), or the conformance class itself (D-0087).

| Level | Requirements | Judged by a client rule | Not judged: a permission | Not judged: unobservable | Not judged: the conformance class |
|---|---:|---:|---:|---:|---:|
| MUST | 58 | 56 | 0 | 1 | 1 |
| SHOULD | 8 | 6 | 0 | 2 | 0 |
| MAY | 13 | 2 | 11 | 0 | 0 |

| Requirement | Level | Also binds | Summary | Cited by | Judged by |
|---|---|---|---|---|---|
| `lws10-authn-openid/id-token-token-type-uri` | MUST |  | An ID Token used as an authentication credential carries the id_token token-type URI when interacting with an authorization server. | `authn-oidc-valid-id-token` | `client-oidc-token-type-id-token` |
| `lws10-authn-saml/token-type-saml2` | MUST |  | A SAML 2.0 assertion credential uses the saml2 token-type URI at the authorization server. | `authn-saml-valid-assertion` | `client-saml-token-type-saml2` |
| `lws10-authn-ssi-cid/alg-not-none` | MUST |  | The self-issued JWT must not use "none" as its signing algorithm. | `authn-cid-alg-none`, `authn-cid-didkey-alg-none` | `client-cid-credential-signed` |
| `lws10-authn-ssi-cid/aud-includes-as` | MUST |  | Any audience restriction uses aud, which must include the target authorization server. | `authn-cid-didkey-audience-excludes-as`, `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-audience-includes-as` |
| `lws10-authn-ssi-cid/client-id-claim` | MUST |  | The client_id claim carries the client identifier. | `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-client-id-claim` |
| `lws10-authn-ssi-cid/exp-claim` | MUST |  | The JWT includes an exp (expiration) claim. | `authn-cid-didkey-missing-exp`, `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-expiry-claim` |
| `lws10-authn-ssi-cid/iat-claim` | MUST |  | The JWT includes an iat (issued at) claim. | `authn-cid-didkey-missing-iat`, `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-issued-at-claim` |
| `lws10-authn-ssi-cid/iss-claim` | MUST |  | The iss claim carries the issuer identifier. | `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-issuer-claim` |
| `lws10-authn-ssi-cid/sub-claim` | MUST |  | The sub claim carries the subject identifier. | `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-subject-claim` |
| `lws10-authn-ssi-cid/sub-iss-client-same-uri` | MUST |  | For self-issued credentials, sub, iss, and client_id are the same URI. | `authn-cid-claims-mismatch`, `authn-cid-didkey-claims-mismatch`, `authn-cid-didkey-missing-client-id`, `authn-cid-didkey-missing-issuer`, `authn-cid-didkey-missing-subject`, `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-identifiers-agree` |
| `lws10-authn-ssi-cid/token-type-jwt` | MUST |  | A self-issued JWT credential uses the jwt token-type URI at the authorization server. | `authn-cid-didkey-valid-credential`, `authn-cid-valid-credential` | `client-cid-token-type-jwt` |
| `lws10-core/access-access-collection` | MUST | Server | The access property is a collection of one or more objects. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-document-access` |
| `lws10-core/access-access-required` | MUST | Server | The access property is required. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-document-access` |
| `lws10-core/access-extra-properties-allowed` | MAY | Server | Other properties may be present on access requests and access grants. | `access-grant-extra-properties-accepted` | *Not judged: a permission.* A client may add properties or not; either is allowed. |
| `lws10-core/access-inbox-optional` | MAY | Server | The inbox property is optional. | `access-grant-extra-properties-accepted` | *Not judged: a permission.* A client may leave the inbox out. One it gives is judged by `client-access-document-inbox`. |
| `lws10-core/access-inbox-uri` | MUST | Server | The inbox property value is a URI. | `access-grant-incomplete-refused` | `client-access-document-inbox` |
| `lws10-core/access-jsonld-context-lws-v1` | MUST | Server | Access request/grant JSON-LD documents include an @context ordered set containing https://www.w3.org/ns/lws/v1; extension contexts are allowed. | `access-grant-document-shape` | `client-access-document-context` |
| `lws10-core/access-storage-required` | MUST | Server | The storage property is required. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-document-storage` |
| `lws10-core/access-storage-uri` | MUST | Server | The storage property value is a URI. | `access-grant-create` | `client-access-document-storage` |
| `lws10-core/access-type-required` | MUST | Server | The type property on access requests and grants is required. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-grant-type`, `client-access-request-type` |
| `lws10-core/access-type-values` | MUST | Server | The type value includes AccessRequest for requests or AccessGrant for grants; additional types are allowed. | `access-grant-create`, `access-request-create` | `client-access-grant-type`, `client-access-request-type` |
| `lws10-core/authn-audience-restriction-recommended` | SHOULD | IdentityProvider | Audience restriction is recommended and its list should include an authorization server identifier. | `authn-cid-didkey-audience-excludes-as` | `client-cid-audience-restricted` |
| `lws10-core/authn-client-claim` | MUST | IdentityProvider | The client claim is required and should be a URI. | `authn-cid-didkey-missing-client-id`, `authn-oidc-missing-azp` | `client-cid-client-id-claim` |
| `lws10-core/authn-credential-signed` | MUST | IdentityProvider | Authentication credentials are signed; asymmetric cryptography is recommended. | `authn-cid-alg-none`, `authn-cid-bad-signature`, `authn-cid-didkey-alg-none`, `authn-cid-didkey-invalid-signature`, `authn-oidc-alg-none`, `authn-oidc-bad-signature`, `authn-saml-invalid-signature`, `authn-saml-unsigned` | `client-cid-credential-signed` |
| `lws10-core/authn-credential-tamper-evident-claims` | MUST | IdentityProvider | An authentication credential includes tamper-evident claims about a subject. | `authn-cid-didkey-missing-client-id`, `authn-cid-didkey-missing-issuer`, `authn-cid-didkey-missing-subject` | `client-cid-subject-claim` |
| `lws10-core/authn-issuer-claim-uri` | MUST | IdentityProvider | The issuer claim is required and its value is a URI. | `authn-cid-didkey-missing-issuer` | `client-cid-issuer-claim` |
| `lws10-core/authn-subject-claim-uri` | MUST | IdentityProvider | The subject claim is required and its value is a URI. | `authn-cid-didkey-missing-subject` | `client-cid-subject-claim` |
| `lws10-core/authz-bearer-presentation-rfc6750` | MUST |  | Clients present access tokens via the Authorization header per RFC 6750. | `getContainer-authenticated-owner` | `client-token-in-authorization-header` |
| `lws10-core/authz-challenge-realm-param` | MUST | Server | The realm challenge parameter is required; clients verify the request URI is logically within the realm. | `getContainer-private-unauthorized` | `client-token-for-containing-realm` |
| `lws10-core/authz-token-exchange-resource-param` | MUST | AuthorizationServer | The resource parameter is a required URI that populates aud; requests naming unknown or untrusted storages are rejected. | `authz-token-exchange-invalid-resource`, `authz-token-exchange-missing-resource`, `authz-token-exchange-valid` | `client-token-exchange-resource` |
| `lws10-core/authz-token-exchange-subject-token-param` | MUST | AuthorizationServer | The subject_token parameter is required and carries a valid subject token such as an authentication credential. | `authz-token-exchange-missing-subject-token`, `authz-token-exchange-valid` | `client-token-exchange-subject-token` |
| `lws10-core/client-no-assumed-methods-405-415` | MUST |  | Clients do not assume PUT or patch-format support unless advertised, and handle 405 and 415 gracefully. |  | `client-linkset-patch-format-advertised`, `client-linkset-put-only-when-advertised`, `client-no-repeat-after-405-415` |
| `lws10-core/conformance-client-class` | MUST |  | An LWS Client is an HTTP client that complies with all relevant MUST statements, specifically those in the Operations section. |  | *Not judged: the conformance class.* The client conformance class itself: the session's verdict, which only MUST rules decide, answers for it. |
| `lws10-core/create-container-type-link` | MUST | Server | Creating a container is signalled by a request Link header rel=type pointing at lws#Container; the server materializes a container accordingly. | `createContainer` | `client-create-container-type-link` |
| `lws10-core/create-post-not-idempotent` | SHOULD |  | POST is not idempotent; clients should avoid unintentional retries or use unique identifiers. | `create-post-twice-distinct` | `client-no-blind-retry-of-create` |
| `lws10-core/create-server-managed-metadata-protected` | MUST | Server | On create, clients may supply user-managed metadata as Link headers, but server-managed metadata is generated by the server and must not be overridden. | `create-server-managed-links-protected` | `client-create-no-server-managed-links` |
| `lws10-core/delete-if-match-optional` | MAY |  | DELETE targets the resource URI; clients may include If-Match for concurrency checks. | `conditional-delete-stale-if-match-412` | `client-delete-conditional` |
| `lws10-core/delete-non-empty-container-409-depth` | MUST | Server | Non-recursive DELETE of a non-empty container is rejected with 409; recursive deletion is requested via Depth: infinity. | `delete-container-recursive`, `delete-container-recursive-deep`, `delete-non-empty-container-409` | `client-delete-container-depth` |
| `lws10-core/iana-ld-json-profile-equivalence` | SHOULD | Server | Per the IANA registration, application/ld+json with the lws/v1 profile should be treated as equivalent to application/lws+json. | `container-ld-json-lws-profile` | *Not judged: unobservable.* The IANA registration's restatement of `lws-profile-equivalence`, unobservable for the same reason. |
| `lws10-core/linkset-precondition-failed-412` | MUST | Server | A conditional PUT or PATCH on a linkset whose precondition fails is rejected with 412 Precondition Failed; servers and clients SHOULD use conditional requests there. | `linkset-conditional-412` | `client-linkset-write-conditional` |
| `lws10-core/linkset-put-405-if-unsupported` | MUST | Server | PUT may replace the entire linkset if advertised; otherwise the server rejects it with 405 Method Not Allowed. | `linkset-put-405-when-unsupported` | `client-linkset-put-only-when-advertised` |
| `lws10-core/lws-profile-equivalence` | SHOULD | Server | application/ld+json with the lws/v1 profile should be treated as equivalent to application/lws+json. | `container-ld-json-lws-profile` | *Not judged: unobservable.* How a client treats a body it receives, which shows only in what it does next. The session may label a listing with the profile only when the client's Accept allows it, and a client that does not take it for LWS stops or reads again, as conformant clients also may. |
| `lws10-core/pagination-first-page-flow` | MAY | Server | The composite resource URI yields the first page; servers may support direct access to specific pages. | `pagination-first-page` | *Not judged: a permission.* Its keyword lets servers offer direct access to pages. The flow it describes for clients, following the links given, is judged by `client-page-urls-issued`. |
| `lws10-core/pagination-uris-opaque` | SHOULD |  | Pagination URIs are opaque; clients use the URIs the server provides. |  | `client-page-urls-issued` |
| `lws10-core/policy-action-required` | MUST | Server | The action property is required. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-policy-action` |
| `lws10-core/policy-action-values` | MUST | Server | The action property is a collection of server-recognized operation strings; read, modify, create and delete are mandatory to support. | `access-grant-authenticated-agent` | `client-access-policy-action` |
| `lws10-core/policy-assignee-required` | MUST | Server | The assignee property is required. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-policy-assignee` |
| `lws10-core/policy-assignee-uri-foaf-agent` | MUST | Server | The assignee is a URI; public access may be assigned via foaf:Agent. | `getContainer-public-read`, `readDataResource-public-read` | `client-access-policy-assignee` |
| `lws10-core/policy-constraint-objects` | MUST | Server | The constraint property, if present, is a collection of constraint objects with the defined members. | `access-grant-constraints-all-satisfied`, `access-grant-left-operands-accepted` | `client-access-policy-constraint` |
| `lws10-core/policy-constraint-optional` | MAY | Server | The constraint property is optional. | `access-grant-extra-properties-accepted` | *Not judged: a permission.* A client may leave constraints out. Those it gives are judged by `client-access-policy-constraint`. |
| `lws10-core/policy-target-object` | MUST | Server | The target property is an object containing the defined properties. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-policy-target` |
| `lws10-core/policy-target-optional` | MAY | Server | The target property is optional. |  | *Not judged: a permission.* A client may leave the target out. One it gives is judged by `client-access-policy-target`. |
| `lws10-core/policy-type-access-policy` | MUST | Server | A policy type includes AccessPolicy; additional types are allowed. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-policy-type` |
| `lws10-core/policy-type-required` | MUST | Server | The policy type property is required. | `access-grant-document-shape`, `access-grant-incomplete-refused` | `client-access-policy-type` |
| `lws10-core/prefer-link-relations-filtering` | MAY |  | Clients may use Prefer with lws#PreferLinkRelations to include or omit specific relations. |  | *Not judged: a permission.* A client may use the preference or not, and the draft leaves its syntax for naming relations open. |
| `lws10-core/put-clients-use-conditional-requests` | SHOULD | Server | Clients SHOULD make PUT conditional (RFC 9110) to avoid overwriting concurrent changes; PUT is idempotent for existing resources. |  | `client-put-conditional` |
| `lws10-core/subscription-create-post-lws-json` | MUST |  | A subscription is created by an authenticated POST to the NotificationService endpoint with an application/lws+json body. | `subscription-create` | `client-subscription-media-type` |
| `lws10-core/subscription-request-additional-fields` | MAY |  | A subscription request may carry further fields required by its subscription type. | `subscription-create` | *Not judged: a permission.* A client may send the further fields a subscription type asks for; either way is allowed. |
| `lws10-core/subscription-request-required-fields` | MUST |  | A subscription request contains the required subscription fields. | `subscription-create`, `subscription-missing-topic-refused`, `subscription-missing-type-refused` | `client-subscription-topic`, `client-subscription-type` |
| `lws10-core/subscription-request-topic` | MUST |  | A subscription request carries a required topic: an array of URIs naming the resources in scope. | `subscription-create`, `subscription-missing-topic-refused` | `client-subscription-topic` |
| `lws10-core/subscription-request-type` | MUST |  | A subscription request carries a required type, one of the subscription types the NotificationService advertises. | `subscription-create`, `subscription-missing-type-refused`, `subscription-unadvertised-type-refused` | `client-subscription-type` |
| `lws10-core/update-content-vs-metadata-prefer-set-linkset` | MUST | Server | PUT/PATCH on a resource URI modify content only; combined content+metadata updates are opt-in via Prefer: set-linkset (optional for servers; otherwise ignored or 501). | `updateDataResource` | `client-combined-update-prefer-set-linkset` |
| `lws10-core/uri-independent-of-hierarchy` | SHOULD | Server | Resource URIs are independent of containment position; servers may use client hints but clients must not assume URI structure reflects containment. | `create-post-twice-distinct` | `client-member-urls-issued` |
| `lws10-index/client-415-accept-query` | MAY |  | A client refused with 415 may consult Accept-Query to pick a supported format. (A client permission.) |  | *Not judged: a permission.* A client may consult Accept-Query after a 415. While the session accepts only the baseline format, doing so looks the same as `client-query-baseline-after-415` (D-0078). |
| `lws10-index/client-baseline-only` | MUST |  | A portable client relies on no query format beyond the baseline. (A client obligation.) |  | `client-query-baseline-after-415` |
| `lws10-index/client-no-read-your-writes` | MUST |  | Clients do not assume read-your-writes consistency and tolerate transient staleness. (A client obligation.) |  | *Not judged: unobservable.* An assumption inside the client: one that relies on reading its own writes goes wrong in its own logic, not in what it sends. The lagging-index trap lets the developer see it. |
| `lws10-index/client-restart` | SHOULD |  | A client refused a page restarts the search or listing. (A client obligation.) |  | `client-restart-after-refused-page` |
| `lws10-index/query-content-type-required` | MUST | Server | A search request carries a Content-Type identifying its query format; a server fails a request without one. | `type-search-missing-content-type-rejected` | `client-query-content-type` |
| `lws10-index/query-safe-idempotent` | MAY | Server | QUERY is safe and idempotent: a search never alters server state and may be repeated, retried or cached. | `type-search-safe` | *Not judged: a permission.* A client may repeat, retry or cache a search; each is allowed. |
| `lws10-index/type-filter` | MAY | Server | The optional type key is a conjunctive-normal-form filter over rdf:type: an array whose elements are ANDed, each a type IRI or an array of IRIs ORed; a filter with no constraints matches every resource visible to the client. | `type-search-and-or`, `type-search-by-type`, `type-search-empty-key-absent`, `type-search-native-classes`, `type-search-relation-cnf` | *Not judged: a permission.* A client may filter by type or not; either is allowed. |
| `lws10-notifications-webhook/inbox-verifies-signature` | MUST |  | An inbox receiving a signed delivery verifies the signature with the notification server's public key from the storage description. (A receiver obligation.) | `webhook-signature-verifies` | `client-inbox-refuses-altered-body`, `client-inbox-refuses-unpublished-key` |
| `lws10-notifications-webhook/keyid-url-with-fragment` | MUST | Server | The keyid is a URL with a fragment component; without the fragment it is the storage identifier. | `webhook-signing-key-published` | `client-inbox-refuses-keyid-without-fragment` |
| `lws10-notifications-webhook/per-subscription-inbox-urls` | MAY |  | Subscribers may use unique per-subscription inbox URLs to limit correlation. (A subscriber option.) |  | `client-subscription-own-inbox` |
| `lws10-notifications-webhook/receiver-verification-steps` | MUST |  | A receiver verifies a webhook signature by the steps the suite gives. (A receiver obligation.) | `webhook-signature-verifies` | `client-inbox-acknowledges-genuine-delivery`, `client-inbox-refuses-foreign-key-document`, `client-inbox-refuses-keyid-without-fragment`, `client-inbox-refuses-unpublished-key` |
| `lws10-notifications-webhook/storage-description-id-matches` | MUST | Server | The storage description dereferenced from the keyid has a top-level id equal to the storage identifier. | `webhook-signing-key-published` | `client-inbox-refuses-foreign-key-document` |
| `lws10-notifications-webhook/subscription-expires-optional` | MAY | Server | A webhook subscription request may carry an expires datetime. | `webhook-subscription-expires-supported` | *Not judged: a permission.* A client may ask for an expiry or not; either is allowed. |
| `lws10-notifications-webhook/subscription-inbox-required` | MUST | Server | A webhook subscription request carries a required inbox: the URI notifications are delivered to. | `webhook-subscription-response` | `client-subscription-inbox` |
| `lws10-notifications-webhook/subscription-type-and-fields` | MUST | Server | A webhook subscription request has type WebhookSubscription, and the server supports the webhook fields. | `webhook-subscription-expires-supported`, `webhook-subscription-response` | `client-subscription-type` |
| `lws10-notifications-webhook/subscription-type-identifier` | MUST | Server | A webhook subscription uses the string WebhookSubscription as its subscription type with the NotificationService. | `webhook-subscription-response` | `client-subscription-type` |

## 5. Client rules

Each rule judges the exchanges an LWS client sends to a client session (definitions/OBSERVATION.md);
*Area* is what a developer may declare out of scope.

### `clients/authentication`

| Rule | Level | Area | Requirements |
|---|---|---|---|
| `client-token-exchange-resource` | MUST | authentication | `authz-token-exchange-resource-param` |
| `client-token-exchange-subject-token` | MUST | authentication | `authz-token-exchange-subject-token-param` |
| `client-token-for-containing-realm` | MUST | authentication | `authz-challenge-realm-param` |
| `client-cid-token-type-jwt` | MUST | authentication | `token-type-jwt` |
| `client-cid-credential-signed` | MUST | authentication | `alg-not-none`, `authn-credential-signed` |
| `client-cid-subject-claim` | MUST | authentication | `sub-claim`, `authn-subject-claim-uri`, `authn-credential-tamper-evident-claims` |
| `client-cid-issuer-claim` | MUST | authentication | `iss-claim`, `authn-issuer-claim-uri` |
| `client-cid-client-id-claim` | MUST | authentication | `client-id-claim`, `authn-client-claim` |
| `client-cid-identifiers-agree` | MUST | authentication | `sub-iss-client-same-uri` |
| `client-cid-audience-includes-as` | MUST | authentication | `aud-includes-as` |
| `client-cid-audience-restricted` | SHOULD | authentication | `authn-audience-restriction-recommended` |
| `client-cid-expiry-claim` | MUST | authentication | `exp-claim` |
| `client-cid-issued-at-claim` | MUST | authentication | `iat-claim` |
| `client-oidc-token-type-id-token` | MUST | authentication | `id-token-token-type-uri` |
| `client-saml-token-type-saml2` | MUST | authentication | `token-type-saml2` |

### `clients/core`

| Rule | Level | Area | Requirements |
|---|---|---|---|
| `client-token-in-authorization-header` | MUST | core | `authz-bearer-presentation-rfc6750` |
| `client-linkset-put-only-when-advertised` | SHOULD | core | `client-no-assumed-methods-405-415`, `linkset-put-405-if-unsupported` |
| `client-linkset-patch-format-advertised` | SHOULD | core | `client-no-assumed-methods-405-415` |
| `client-put-conditional` | SHOULD | core | `put-clients-use-conditional-requests` |
| `client-linkset-write-conditional` | SHOULD | core | `linkset-precondition-failed-412` |
| `client-page-urls-issued` | SHOULD | core | `pagination-uris-opaque` |
| `client-member-urls-issued` | SHOULD | core | `uri-independent-of-hierarchy` |
| `client-access-document-context` | MUST | core | `access-jsonld-context-lws-v1` |
| `client-access-request-type` | MUST | core | `access-type-required`, `access-type-values` |
| `client-access-grant-type` | MUST | core | `access-type-required`, `access-type-values` |
| `client-access-document-storage` | MUST | core | `access-storage-required`, `access-storage-uri` |
| `client-access-document-access` | MUST | core | `access-access-required`, `access-access-collection` |
| `client-access-policy-type` | MUST | core | `policy-type-required`, `policy-type-access-policy` |
| `client-access-policy-action` | MUST | core | `policy-action-required`, `policy-action-values` |
| `client-access-policy-assignee` | MUST | core | `policy-assignee-required`, `policy-assignee-uri-foaf-agent` |
| `client-access-policy-target` | MUST | core | `policy-target-object` |
| `client-access-policy-constraint` | MUST | core | `policy-constraint-objects` |
| `client-access-document-inbox` | MUST | core | `access-inbox-uri` |
| `client-delete-conditional` | MAY | core | `delete-if-match-optional` |
| `client-create-no-server-managed-links` | MUST | core | `create-server-managed-metadata-protected` |
| `client-create-container-type-link` | MUST | core | `create-container-type-link` |
| `client-delete-container-depth` | MUST | core | `delete-non-empty-container-409-depth` |
| `client-combined-update-prefer-set-linkset` | MUST | core | `update-content-vs-metadata-prefer-set-linkset` |
| `client-no-repeat-after-405-415` | MUST | core | `client-no-assumed-methods-405-415` |
| `client-no-blind-retry-of-create` | SHOULD | core | `create-post-not-idempotent` |

### `clients/index`

| Rule | Level | Area | Requirements |
|---|---|---|---|
| `client-query-content-type` | MUST | index | `query-content-type-required` |
| `client-query-baseline-after-415` | MUST | index | `client-baseline-only` |
| `client-restart-after-refused-page` | SHOULD | index | `client-restart` |

### `clients/notifications`

| Rule | Level | Area | Requirements |
|---|---|---|---|
| `client-subscription-media-type` | MUST | notifications | `subscription-create-post-lws-json` |
| `client-subscription-type` | MUST | notifications | `subscription-request-required-fields`, `subscription-request-type`, `subscription-type-and-fields`, `subscription-type-identifier` |
| `client-subscription-topic` | MUST | notifications | `subscription-request-required-fields`, `subscription-request-topic` |
| `client-subscription-inbox` | MUST | notifications | `subscription-inbox-required` |
| `client-subscription-own-inbox` | MAY | notifications | `per-subscription-inbox-urls` |
| `client-inbox-acknowledges-genuine-delivery` | SHOULD | notifications | `receiver-verification-steps` |
| `client-inbox-refuses-unpublished-key` | MUST | notifications | `inbox-verifies-signature`, `receiver-verification-steps` |
| `client-inbox-refuses-altered-body` | MUST | notifications | `inbox-verifies-signature` |
| `client-inbox-refuses-keyid-without-fragment` | MUST | notifications | `receiver-verification-steps`, `keyid-url-with-fragment` |
| `client-inbox-refuses-foreign-key-document` | MUST | notifications | `receiver-verification-steps`, `storage-description-id-matches` |
