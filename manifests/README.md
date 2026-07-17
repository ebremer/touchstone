# Test manifests

Declarative YAML test definitions — the single source of truth the JUnit 5
`@TestFactory` loader compiles into `DynamicTest`s (DESIGN.md §5.2).

**Gate 2 closed (2026-07-16): manifest JSON Schema v1 is frozen**
(`docs/manifest-schema/manifest.schema.json`, `$id …/manifest/1-0-0`). Test
manifests arrive with Phase 2's executor; every manifest must validate against
the frozen schema.

Layout:

```
manifests/
  core/            # storage, containers, CRUD, conditional requests, conneg, errors (12 tests)
    bodies/        # request-body fixtures referenced via bodyRef
  auth-oidc/       # OIDC negative matrix: 401 + WWW-Authenticate, token validation (9 tests)
  auth-saml/       # (Phase 6)
  auth-cid/        # (Phase 6)
  auth-didkey/     # (Phase 6)
```

Auth manifests declare `capabilities: [authentication]`, so they run only against a
target that advertises it (the secured reference server) and are skipped against the
open one. They reference abstract identities (`alice`, `bob`, `anonymous`,
`alice-expired`, `alice-bad-signature`, …) that the provisioning adapter resolves to
credentials; the harness owns the OIDC issuer that mints them (DECISIONS.md D-0017).
