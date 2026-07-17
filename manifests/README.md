# Test manifests

Declarative YAML test definitions — the single source of truth the JUnit 5
`@TestFactory` loader compiles into `DynamicTest`s (DESIGN.md §5.2).

**Gate 2 closed (2026-07-16): manifest JSON Schema v1 is frozen**
(`docs/manifest-schema/manifest.schema.json`, `$id …/manifest/1-0-0`). Test
manifests arrive with Phase 2's executor; every manifest must validate against
the frozen schema.

Planned layout once the gate opens:

```
manifests/
  core/            # storage, containers, CRUD, conditional requests, conneg, errors
    bodies/        # request-body fixtures referenced via bodyRef
  auth-oidc/
  auth-saml/
  auth-cid/
  auth-didkey/
```
