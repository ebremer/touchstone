---
title: YAML-LD definitions
nav_order: 12
description: "The proposed YAML-LD test format: it mirrors and extends the LWS test group's JSON-LD suite and is designed to be contributed back."
---

# YAML-LD test definitions
{: .no_toc }

{: .important }
**Proposed, format version 0.1.0, not frozen.** The definitions are written and
validated, but Touchstone does not execute them yet. `touchstone run` still runs
`manifests/`. The engine for this format will be generated from the definitions and
their execution contract once the format has been reviewed and frozen.

1. TOC
{:toc}

## Why a second format

The LWS test group is building `lws-contrib/lws-test-suite`, a suite of JSON-LD test
manifests intended to become the authoritative LWS test suite. Touchstone's definitions
serve that effort in three ways:

1. **They mirror it.** Each of its 27 tests has a counterpart. Where one of its tests
   contradicts the current draft, the counterpart follows the draft. For example, DELETE
   expects `204`, and discovery uses `rel="https://www.w3.org/ns/lws#storage"` and
   `application/lws+cid`.
2. **They go further.** There are 101 definitions (84 MUST, 15 SHOULD, 2 MAY). They cover
   storage discovery, containers, data resources, conditional requests, linksets, the
   access-token negative matrix, the authorization server, access grants, notification
   discovery, and the did:key, OpenID Connect, CID and SAML suites.
3. **They can be contributed back.** YAML-LD is JSON-LD written in YAML, so converting a
   definition to JSON-LD is mechanical and loses nothing. Nothing has been contributed
   yet; that will be a separate, deliberate step.

## Layout

```text
definitions/
  README.md                 why the definitions exist, authoring rules, validation, export
  EXECUTION.md              the contract an engine must implement
  COVERAGE.md               mapping to lws-test-suite and to manifests/, test by test
  schema/
    definitions.schema.json JSON Schema (2020-12) for manifests and the identity registry
  lws10/                    laid out like lws-test-suite's lws10/, so export is file for file
    context.jsonld          the JSON-LD context, proposed as lws-test-suite's successor
    touchstone.jsonld       Touchstone-only terms (catalog links, traceability), dropped on export
    vocab.yamlld            RDFS definitions of every term
    identities.yamlld       abstract identities and how their credentials are made
    manifest.yamlld         the root manifest, which includes the modules below
    core/                   discovery, containers, data_resources, conditional_requests, linksets,
                            storage_authorization, authorization_server, access_grants, notifications
    auth/                   did_key, oidc, cid, saml
    fixtures/               request and expected-body files
```

## A definition

```yaml
  - id: "#deleteDataResource"
    type: ValidationTest
    name: deleteDataResource
    label: DELETE removes a data resource with 204 and takes it out of its container
    status: Proposed
    level: MUST
    source:
      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#delete-resource
    traits: [Delete, DataResource, Container]
    steps:
      - label: create a data resource
        request: {method: POST, url: "${test.container}", contentType: text/plain, body: short-lived resource}
        response:
          statusCode: 201
          location: {capture: created}
      - label: delete it
        request: {method: DELETE, url: "${created}", ifMatch: current}
        response: {statusCode: 204}
      - label: it is gone
        request: {method: GET, url: "${created}"}
        response: {statusCode: [404, 410]}
```

The terms are lws-test-suite's wherever their meaning is the same: `request`,
`response`, `method`, `url`, `contentType`, `statusCode`, `linkHeaders`, `traits`,
`status`, `source`, and others. The additions are the ones its reviewers asked for, or
that its tests needed but could not express:

- ordered `steps` with captured values;
- templates such as `${test.container}` in place of fixed hosts;
- explicit matching: JSON pointers with `some`/`every`/`none`, parsed authentication
  challenges, content-negotiation equivalence, and JWT claims;
- exactly one `level` per test, so a SHOULD check can never decide conformance;
- `precondition` steps and `requires`, so an optional feature is recorded as
  inapplicable instead of failed;
- abstract identities instead of embedded credentials.

## How they relate to the rest of Touchstone

| | `manifests/` | `definitions/` |
|---|---|---|
| Format | YAML, schema `1-1-0` (frozen) | YAML-LD, format `0.1.0` (proposed) |
| Executed by `touchstone run` | yes | not yet |
| Draft followed | 21 August 2026 | 21 September 2026 |
| Tests | 33 | 101 |
| Destination | Touchstone | Touchstone, and lws-test-suite as JSON-LD |

32 of the 33 manifests have a successor among the definitions. The remaining one,
`core/put-unconditional-428`, tests a requirement the September draft removed, so it is
retired. Superseding `manifests/` is proposed, not decided. It will follow once a
generated engine runs the definitions green against the reference server.

## Validating the definitions

The definitions are data, so they are checked as data:

1. YAML 1.2 Core Schema parsing, which YAML-LD requires, plus a check that a YAML 1.1
   reading gives the same result;
2. JSON Schema validation against `schema/definitions.schema.json`, in strict mode;
3. JSON-LD `toRDF` of every document with an offline loader, in safe mode, so a dropped
   term is an error;
4. a lint: names are unique, variables are bound, identities exist, catalog IRIs exist,
   `source` anchors resolve, fixtures exist;
5. the vocabulary defines exactly the context's terms;
6. `COVERAGE.md` regenerates without changes.

All six pass for version 0.1.0.

## Exporting to JSON-LD

Export is mechanical:

1. Parse each `.yamlld` file.
2. Remove `touchstone.jsonld` from every `@context`, together with the four keys it
   defines: `requirements`, `mirrors`, `supersedes` and `note`.
3. Rewrite every `include` from `.yamlld` to `.jsonld`.
4. Write each document as JSON, at the same relative path, with a `.jsonld` extension.

The result must convert to RDF using only `context.jsonld`. Its graph must equal the
YAML-LD graph minus the Touchstone-only triples. A trial export produced identical
canonical RDF for all 16 documents.

## Read more

- [definitions/README.md]({% include src.html path="definitions/README.md" %}): authoring
  rules, open questions for the working group, and what is not defined yet
- [definitions/EXECUTION.md]({% include src.html path="definitions/EXECUTION.md" %}): the
  engine contract, covering variables, matching, identities, outcomes and reporting
- [definitions/COVERAGE.md]({% include src.html path="definitions/COVERAGE.md" %}): every
  test, mapped to lws-test-suite and to `manifests/`
- [The definitions tree]({% include src.html path="definitions" kind="tree" %}) on GitHub
