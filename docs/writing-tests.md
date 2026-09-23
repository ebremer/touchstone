---
title: Writing tests
nav_order: 9
has_children: true
description: "How to write a Touchstone test definition in YAML-LD: structure, prerequisites, variables, expectations, identities and the review rules."
---

# Writing tests
{: .no_toc }

A Touchstone test is a **definition**: an entry in a YAML-LD manifest under
`definitions/lws10/`. It describes one request and its response, or a flow of steps, with
declarative expectations. Definitions are data, so a new test needs no Java code. The format
is version 0.2.0, frozen: [`definitions/EXECUTION.md`]({% include src.html path="definitions/EXECUTION.md" %})
says exactly what every key means, and an engine that does anything else is wrong.

Before a run sends a single request, every definition is parsed as YAML 1.2, validated
against the JSON Schema, expanded as JSON-LD in safe mode, and linted. A definition that
fails any of these stops the run with exit code `2`.

1. TOC
{:toc}

## Where definitions live

```text
definitions/lws10/
  manifest.yamlld        the root manifest; it includes the others
  core/                  discovery, containers, data_resources, conditional_requests, linksets,
                         storage_authorization, authorization_server, access_grants, notifications
  auth/<suite>/          did_key, oidc, cid and saml, each a manifest.yamlld
  identities.yamlld      the identities tests act as, and how their credentials are made
  fixtures/              request and expected-body files
```

A test's id is its manifest's path without the extension, then `#` and its name:
`core/containers#getContainer`, or `auth/oidc/manifest#authn-oidc-alg-none`. Names are
unique across the suite, so a name alone identifies a test too. `touchstone run --module`
takes `all`, a module (`core`, `auth`), a manifest (`core/containers`, `auth/oidc`) or one
test.

## A walkthrough

This is `core/data_resources#deleteDataResource`, with its comment and some citations left out:

```yaml
  - id: "#deleteDataResource"
    type: ValidationTest
    name: deleteDataResource
    label: DELETE removes a data resource with 204 and takes it out of its container
    status: Proposed
    level: MUST
    source:
      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#delete-resource
      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#containment-integrity
    traits: [Delete, DataResource, Container]
    requirements:
      - https://example.org/touchstone/req/lws10-core/delete-success-204-conditional
    prereqs:
      hierarchy:
        - dataResource: created
          contentType: text/plain
          body: short-lived resource
    steps:
      - label: delete it
        request:
          method: DELETE
          url: "${created}"
          ifMatch: current
        response:
          statusCode: 204
      - label: it is gone
        request:
          method: GET
          url: "${created}"
        response:
          statusCode: [404, 410]
      - label: and its container no longer lists it
        request:
          method: GET
          url: "${test.container}"
          accept: application/lws+json
        response:
          statusCode: 200
          json:
            - pointer: /items
              none:
                - pointer: /id
                  equalsIri: "${created}"
```

1. **It declares what it needs and does not examine.** The data resource it deletes is a
   prerequisite. The engine creates it in the test's own container before the first step,
   and binds the URI the server chose to `${created}`. A failed prerequisite is a setup
   failure (`cantTell`), not a finding.
2. **It has exactly one level.** The verdict counts only MUST tests; a SHOULD check would
   be its own test.
3. **It sends a conditional write.** `ifMatch: current` makes the engine HEAD the resource
   and send the ETag it gets, because the draft lets a server demand a conditional request.
4. **It accepts what the specification accepts.** A deleted resource answers 404, or 410.
5. **It checks the effect, not just the status.** The listing must no longer contain the
   resource, whatever position it held.
6. **It cites its sources and requirements.** `source` names a dated snapshot and an anchor
   the lint checks; each `requirements` IRI must be in the [catalog](catalog.md).

A test that is one exchange uses the short form: `request` and `response` directly on the
test, with no `steps`. Abridged, `core/storage_authorization#getContainer-private-unauthorized`:

```yaml
  - id: "#getContainer-private-unauthorized"
    type: NegativeTest
    name: getContainer-private-unauthorized
    level: MUST
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

## Test fields

| Field | Meaning |
|---|---|
| `id`, `name` | `id` is `#` + `name`; `name` is unique across the suite. |
| `type` | `ValidationTest`, or `NegativeTest` for a test whose point is a refusal. |
| `label`, `comment` | What the test proves, and why it is built the way it is. |
| `status` | `Proposed`, `Approved` or `Rejected`. Every test is `Proposed` for now. |
| `level` | `MUST`, `SHOULD` or `MAY`: exactly one. |
| `source` | Dated specification snapshots with anchors, or RFC sections. |
| `traits` | What the test exercises: `Get`, `Container`, `Authz` and so on. `list_tests` filters on them. |
| `requires` | Capabilities the target must declare, or the test is inapplicable. |
| `as` | The identity for every step; alice when absent. |
| `requirements` | Touchstone only: catalog requirement IRIs. Dropped on export. |
| `mirrors`, `supersedes`, `note` | Touchstone only: the lws-test-suite test it stands for, the retired manifest it replaced, a remark. |
| `prereqs` | Resources to create before the first step, and access to grant on them. |
| `steps`, or `request` and `response` | A flow, or the short form. Never both. |

## Prerequisites

`prereqs.hierarchy` lists resources in order. The engine creates each one as alice with a
POST, and binds the URI the server assigns:

```yaml
prereqs:
  hierarchy:
    - container: notes                  # a container, bound to ${notes}
      authorization: {read: [anonymous]}
    - dataResource: list                # a data resource inside ${notes}
      in: notes
      contentType: text/plain
      bodyURL: ../fixtures/shoppinglist.txt
    - dataResource: missing             # nothing is created: a URI that cannot exist
      absent: true
```

A data resource takes `contentType` and one of `body`, `bodyURL` or `bodyJSON`.
`authorization` grants the draft's four actions (`read`, `modify`, `create`, `delete`) to
`anonymous` (the public) or to an identity such as `bob`. The engine grants it through the
storage's access grant service. When the storage has none, and the provisioning adapter
cannot grant it either, the test is inapplicable.

## Steps and requests

| Step field | Meaning |
|---|---|
| `label` | What the step establishes. Reports show it. |
| `as` | The identity for this step only. |
| `precondition` | `true`: if an expectation fails here, the feature is absent and the test is inapplicable, not failed. |
| `request`, `response` | The request, and the expectations on its response. |

| Request field | Meaning |
|---|---|
| `method`, `url` | `url` is a template; a relative one resolves against the target's base URL. |
| `accept`, `contentType` | The `Accept` and `Content-Type` headers. |
| `ifMatch` | A template, or `current`: HEAD first and send the ETag, if there is one. |
| `linkHeaders` | `rel`, `href` and `mediaType`, sent as one `Link` header. |
| `otherHeaders` | `headerName` and `headerValue` pairs, in order. |
| `body`, `bodyURL`, `bodyJSON`, `bodyForm` | At most one: a template, a fixture file sent byte for byte, a JSON value, or form fields. |

The identity's `Authorization` header is added unless the identity is anonymous.
Redirects are never followed, nothing is retried, and every request has a 30-second
timeout unless the target sets another.

## Variables

Strings in URLs, header values, bodies and expectations are templates: `${name}` is
replaced by a value, with no escaping. Inside a JSON value, a string that is exactly one
expression takes its JSON value, so `"${now+300}"` becomes a number.

| Variable | Value |
|---|---|
| `test.container` | The test's own container, empty when the first step runs. |
| `run.root`, `target.baseUrl` | The run's root container; the URL the target is registered with. |
| `uuid`, `now`, `now+N`, `now-N` | A fresh UUID; the time in seconds since the epoch. |
| `storage` | The storage, from the `lws#storage` link on the test container. |
| `as.uri`, `as.realm`, `as.metadataUrl` | From the Bearer challenge an anonymous request draws. |
| `as.issuer`, `as.tokenEndpoint`, `as.jwksUri` | From the authorization server's metadata. |
| `service.<Type>` | A service's endpoint in the storage description, such as `service.AccessGrantService`. |
| `identity.<name>.webid`, `credential.<name>` | An identity's agent IRI; a subject credential minted for this test. |
| a prerequisite or capture | Bound by `prereqs`, or by a `capture` in an earlier step. |

A variable that cannot be resolved ends the test. If the reason is an optional feature, a
service, an identity or a fixture host the target lacks, the test is inapplicable;
otherwise it ends `cantTell`, naming the variable.

## Expectations

Expectations are checked in a fixed order, and the first that fails ends the test:

| Key | Passes when |
|---|---|
| `statusCode` | The status equals one listed, or falls in a listed class such as `"4xx"`. |
| `contentType` | The response's media type essence equals it. |
| `location` | `Location` is present. Its resolved value is captured, and with `cleanup: true` deleted at test end. |
| `linkHeaders` | A link has the `rel`, and the `href` and `mediaType` if given; `absent: true` inverts it. Parsed per RFC 8288. |
| `otherHeaders` | The header has `headerValue`, is `present`, `differsFrom` a value, or `matches` a pattern. |
| `authenticationChallenge` | A `WWW-Authenticate` challenge has the scheme and every parameter. Parsed per RFC 9110, in any order. |
| `bodyEmpty`, `bodyMatches`, `bodyURL` | The body is empty, matches a pattern, or equals a fixture byte for byte. |
| `json` | JSON pointer checks: `equals`, `equalsIri`, `hasValue`, `matches`, `count`, `jsonType`, `exists`, and `some`, `every` or `none` over an array. |
| `jwt` | The token decodes, verifies against a JWKS if one is given, and its header and claims pass the same pointer checks. |
| `connegEquivalent` | The URL fetched once per media type answers 200 in each, with byte-identical bodies. |

Any of these may `capture` what it matched for later steps. Regular expressions use the
subset that Java, Python and ECMAScript agree on. Put them in single quotes in YAML.

## Identities and capabilities

Tests name identities from `definitions/lws10/identities.yamlld`, never credentials:

- `anonymous` sends nothing; `alice` owns the storage and is the default; `bob` is another
  authenticated agent with no access unless a test grants it.
- A **fault identity**, such as `alice-expired` or `alice-bad-signature`, is its basis with
  exactly one defect, so a failing test names a single cause.
- **Subject credentials** (`didkey`, `oidc`, `cid`, `saml` and their faults) are minted
  for the token-exchange tests and appear as `${credential.<name>}`.

A test that needs something the server cannot reveal about itself `requires` a capability:
`Authentication`, `HarnessIssuedTokens`, `ReachableFixtures` or `SamlTrust`. Against a
target that does not declare it, the test is inapplicable and no request is sent. See
[Authentication](auth.md) for what each means and how a target provides it.

## Outcomes

| Outcome | When |
|---|---|
| `passed` | Every step passed. |
| `failed` | An expectation failed outside a precondition. |
| `inapplicable` | A capability, identity, service or precondition is absent. |
| `cantTell` | The harness could not decide: a transport error, a timeout, a failed prerequisite. |

The target conforms when no MUST test failed or ended `cantTell`. SHOULD and MAY failures
are advisory. An inapplicable MUST test is coverage the run did not have, and the report
lists it.

## Rules the suite follows

The full list is in [`definitions/README.md`]({% include src.html path="definitions/README.md" %}),
"Authoring rules". The ones that matter most when writing a test:

- **Discover, never assume.** Created URIs come from `Location`, linksets from
  `rel="linkset"`, the storage from `rel="…lws#storage"`, the authorization server from
  the 401 challenge. A test that guesses a path tests one server's convention.
- **One test, one level.** A SHOULD or MAY check never sits inside a MUST test.
- **Declare what the test does not examine** in `prereqs`, and create in a step only when
  creation is the point.
- **Negative tests prove the refusal.** After the 4xx, check that nothing changed.
- **Optional features never fail a server.** Gate them with a `precondition` step, a
  `${service.*}` variable or `requires`.
- **No example hosts.** `storage.example` can never match a live server, and the lint
  rejects it in anything the engine sends or compares.
- **Write for YAML 1.1 readers too.** Quote templates, `"@context"`, ids starting with `#`,
  and anything that looks like a number, boolean or date.

## Checking a new test

- **The definition checks:** `python tools/definitions/check.py` parses, validates, expands
  and lints every definition, and checks the JSON-LD export. CI runs it on every push. See
  [YAML-LD definitions](definitions.md#validating-the-definitions).
- **Against the reference deployment:** start it with `SecuredRefScenarioMain` (see
  [Getting started](getting-started.md)), then run the one test:
  `touchstone run --target secured-ref --targets targets-secured.yaml --module <name>`.
  A new test should pass there. If it cannot, extend the reference server first: a test
  that nothing can pass proves nothing.
- **In the build:** `./mvnw verify` runs every definition against the reference deployment,
  where all must pass, and against its broken twins, where the tests that exist to catch
  each defect must fail.

## Review

Tests enter the suite only through review: a pull request that a person approves. The
mapping from tests to requirements is what the reports' claims rest on. An agent may draft
a test, for example with the MCP `draft_test` prompt, but the draft goes through the
definition checks, a run against the reference deployment, and a pull request. It is never
committed directly.

## Reference

- [`definitions/EXECUTION.md`]({% include src.html path="definitions/EXECUTION.md" %}): the
  contract, key by key
- [The JSON Schema]({% include src.html path="definitions/schema/definitions.schema.json" %}),
  format 0.2.0
- [`definitions/COVERAGE.md`]({% include src.html path="definitions/COVERAGE.md" %}): every
  test, and what it replaced
- [Porting Solid tests](harvest.md): how scenarios from the Solid test corpus became tests
