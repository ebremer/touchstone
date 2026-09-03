# Test manifests

Declarative YAML test definitions — the single source of truth the JUnit 5
`@TestFactory` loader compiles into `DynamicTest`s (DESIGN.md §5.2).

**Gate 2 closed (2026-07-16): manifest JSON Schema v1 is frozen**
(`docs/manifest-schema/manifest.schema.json`, `$id …/manifest/1-1-0`). Every manifest
must validate against it, and every requirement IRI a manifest declares must resolve to
a catalog entry — a run refuses to start otherwise (DECISIONS.md D-0039).

Layout:

```
manifests/
  core/            # storage discovery, containers, CRUD, conditional requests,
                   # media-type equivalence, linkset discovery, errors (24 tests)
    bodies/        # request-body fixtures referenced via bodyRef
  auth-oidc/       # OIDC negative matrix: 401 + WWW-Authenticate, token validation (9 tests)
```

Auth manifests declare `capabilities: [authentication]`, so they run only against a
target that advertises it (the secured reference server) and are skipped against the
open one. They reference abstract identities (`alice`, `bob`, `anonymous`,
`alice-expired`, `alice-bad-signature`, …) that the provisioning adapter resolves to
credentials; the harness owns the OIDC issuer that mints them (DECISIONS.md D-0017).

The remaining suites are catalogued but not yet manifested, each for a recorded reason:
`auth-saml` needs an OpenSAML fixture (D-0024), `auth-cid` and `auth-didkey` are proven
through fixture-level negative matrices rather than storage-level manifests (D-0023),
and `notifications` needs a subscription endpoint and a delivery transport in the
reference server before a test could distinguish anything (D-0041).

Manifests are specification documents, so they use only what the specification defines.
The `Slug` header was removed from all of them when the 21 August 2026 draft dropped it
(D-0040); provisioning still sends one as an unstandardised hint, which is operations
rather than conformance.
