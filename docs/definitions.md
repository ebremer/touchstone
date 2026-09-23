---
title: YAML-LD definitions
nav_order: 12
description: "The YAML-LD test format: it mirrors and extends the LWS test group's JSON-LD suite and is designed to be contributed back."
---

# YAML-LD test definitions
{: .no_toc }

{: .important }
**Format version 0.2.0, frozen on 2026-09-23.** These are the tests `touchstone run`
executes. The engine that runs them implements the format's execution contract, and all 101
run against the reference deployment in every build.

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
  COVERAGE.md               mapping to lws-test-suite and to the retired manifests, test by test
  COMPARISON.md             this format against lws-test-suite's, and why it is stronger
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

A test that is one exchange is written as one, the way lws-test-suite writes its tests:

```yaml
  - id: "#getContainer-private-unauthorized"
    type: NegativeTest
    name: getContainer-private-unauthorized
    level: MUST
    source: [https://www.w3.org/TR/2026/WD-lws10-core-20260921/#authorization-server-discovery]
    traits: [Get, Container, Private, Authn]
    requires: [Authentication]
    as: anonymous
    request:
      method: GET
      url: "${test.container}"
    response:
      statusCode: 401
      authenticationChallenge:
        wwwAuthenticate: Bearer
        asUri: {matches: '^https?://'}
        realm: {matches: '^https?://'}
```

What a test needs but does not examine is declared, and the engine creates it. A flow of
several exchanges uses `steps`:

```yaml
  - id: "#deleteDataResource"
    type: ValidationTest
    level: MUST
    ...
    prereqs:
      hierarchy:
        - dataResource: created          # the server picks the URI; it becomes ${created}
          contentType: text/plain
          body: short-lived resource
    steps:
      - label: delete it
        request: {method: DELETE, url: "${created}", ifMatch: current}
        response: {statusCode: 204}
      - label: it is gone
        request: {method: GET, url: "${created}"}
        response: {statusCode: [404, 410]}
```

A prerequisite can also carry access, such as `authorization: {read: [anonymous]}`, which the
engine grants through the storage's access grant service.

The terms are lws-test-suite's wherever their meaning is the same: `request`,
`response`, `method`, `url`, `contentType`, `statusCode`, `linkHeaders`,
`authenticationChallenge`, `prereqs`, `hierarchy`, `authorization`, `traits`, `status`,
`source`, and others. The additions are the ones its reviewers asked for, or that its tests
needed but could not express:

- ordered `steps` with captured values, for tests that need more than one exchange;
- templates such as `${test.container}` in place of fixed hosts and paths;
- explicit matching: JSON pointers with `some`/`every`/`none`, parsed authentication
  challenges, content-negotiation equivalence, and JWT claims;
- exactly one `level` per test, so a SHOULD check can never decide conformance;
- `precondition` steps and `requires`, so an optional feature is recorded as
  inapplicable instead of failed;
- abstract identities instead of embedded credentials.

## Compared with lws-test-suite

Format 0.2.0 merges lws-test-suite's design into this one. It keeps that suite's
vocabulary, its one-request tests and its declared prerequisites. It fixes what stops those
tests running against a real server or makes them contradict the draft. Measured on
lws-test-suite's current files:

| lws-test-suite today | The merged format |
|---|---|
| 21 of 27 tests name example hosts such as `storage.example`, and 13 share the path `/alice/notes/` | Every server-chosen URL is a variable; each test has its own container; the checks reject example hosts |
| Access modes `write`, `append` and `control` are Solid's, not LWS's; `Role-Authenticated` cannot be expressed in the draft | The draft's four actions; grants to identities, carried out through the access grant service |
| No rules for comparing content types, links, bodies or challenges | Every comparison defined, with challenges parsed per RFC 9110 |
| One request per test, so no test can check a delete took effect | One request when that suffices, `steps` for flows |
| 7 tests carry credential placeholders like `<expired-token>` | Named identities whose valid and broken credentials the harness makes |
| No test declares a level; every `source` is an undated editor's draft | One level per test; dated sources whose anchors are checked |
| A nonexistent `mf:` namespace, an `@vocab` fallback, `Location` resolved against the manifest | Real namespaces, no fallback, strict JSON-LD; `Location` captured |
| No validation; its YAML and JSON-LD copies disagree | Six automated checks, negative controls, and an export trial |

The full comparison, with both formats side by side and what would change in
lws-test-suite's files, is [COMPARISON.md]({% include src.html path="definitions/COMPARISON.md" %}).

## How Touchstone runs them

The engine in `harness-core` implements
[`definitions/EXECUTION.md`]({% include src.html path="definitions/EXECUTION.md" %}): it
loads, validates, expands and lints the definitions, then runs them with the identities,
prerequisites and expectations the contract defines. The CLI, the MCP server, the Docker
image and the GitHub Action all run the definitions through it. [How it
works](how-it-works.md) follows a run step by step.

Every build runs all 101 against the reference deployment, where 100 pass and the
notification test is inapplicable, and against broken twins, where the tests that exist to
catch each defect fail.

The definitions replaced the YAML test manifests Touchstone ran before, which followed the
21 August 2026 draft. 32 of those 33 tests have a successor among the definitions. The
other, `core/put-unconditional-428`, tested a requirement the September draft removed, so
it has none. [COVERAGE.md]({% include src.html path="definitions/COVERAGE.md" %}), table 2,
maps each to its successor.

## Validating the definitions

The definitions are data, so they are checked as data:

1. YAML 1.2 Core Schema parsing, which YAML-LD requires, plus a check that a YAML 1.1
   reading gives the same result;
2. JSON Schema validation against `schema/definitions.schema.json`, in strict mode;
3. JSON-LD `toRDF` of every document with an offline loader, in safe mode, so a dropped
   term is an error;
4. a lint: names are unique, variables are bound, identities exist, catalog IRIs exist,
   `source` anchors resolve, fixtures exist, and no executable value names an example host;
5. the vocabulary defines exactly the context's terms;
6. `COVERAGE.md` regenerates without changes.

All six pass for version 0.2.0, as does the JSON-LD export trial below. One command runs
them all, and CI runs it on every push and pull request. The engine applies the same
checks, bar the anchor and lws-test-suite ones, before every run:

```sh
cd tools/definitions && npm ci && pip install -r requirements.txt
python check.py              # add --write to regenerate vocab.yamlld and COVERAGE.md
```

See [tools/definitions/README.md]({% include src.html path="tools/definitions/README.md" %})
for what each check does, and for the lws-test-suite checkout that two of them read.

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

- [definitions/COMPARISON.md]({% include src.html path="definitions/COMPARISON.md" %}): the
  format against lws-test-suite's, side by side, with the evidence
- [definitions/README.md]({% include src.html path="definitions/README.md" %}): authoring
  rules, open questions for the working group, and what is not defined yet
- [definitions/EXECUTION.md]({% include src.html path="definitions/EXECUTION.md" %}): the
  engine contract, covering variables, matching, identities, outcomes and reporting
- [definitions/COVERAGE.md]({% include src.html path="definitions/COVERAGE.md" %}): every
  test, mapped to lws-test-suite and to the manifests it replaced
- [The definitions tree]({% include src.html path="definitions" kind="tree" %}) on GitHub
