# Test manifests

Declarative YAML test definitions — the single source of truth the JUnit 5
`@TestFactory` loader compiles into `DynamicTest`s (DESIGN.md §5.2).

**Gate 2 (DESIGN.md §11): no manifest is written until the JSON Schema is
frozen.** The proposed schema, its rationale, and a worked example live in
`docs/manifest-schema/` and await Erich's review.

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
