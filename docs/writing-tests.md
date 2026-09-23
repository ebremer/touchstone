---
title: Writing tests
nav_order: 9
has_children: true
description: "How to write a Touchstone test manifest: structure, variables, bindings, the assertion vocabulary and the review rules."
---

# Writing tests
{: .no_toc }

A Touchstone test is a **manifest**: one YAML file that describes one test as a sequence
of HTTP steps with declarative expectations. Manifests are data, so a new test needs no
Java code. Every manifest is validated against a JSON Schema, which is frozen at
version `1-1-0`, before it can run.

1. TOC
{:toc}

## Where manifests live

```text
manifests/
  core/                 storage discovery, containers, CRUD, conditional requests, linksets, errors
    bodies/             request-body fixtures referenced with bodyRef
  auth-oidc/            OpenID Connect access-token tests (need the authentication capability)
```

A test's `id` is `<module>/<slug>`, and the file is `manifests/<module>/<slug>.yaml`.
The module is what `touchstone run --module` selects. Every `*.yaml` file under the
module's directory is loaded, including files in subdirectories.

## A walkthrough

This is `core/put-replace-with-if-match`, taken unchanged from the suite:

```yaml
schemaVersion: 1
id: core/put-replace-with-if-match
title: A conditional PUT replaces resource content and yields a fresh ETag
requirements:
  - https://example.org/touchstone/req/lws10-core/update-success-new-etag
steps:
  - name: create version one
    request:
      method: POST
      target: "${test.container}"
      headers:
        Content-Type: text/plain
      body: "note version one"
    expect:
      status: 201
    bind:
      created: "header:Location"

  - name: read the current ETag
    request:
      method: GET
      target: "${created}"
    expect:
      status: 200
    bind:
      etag: "header:ETag"

  - name: replace with If-Match
    request:
      method: PUT
      target: "${created}"
      headers:
        Content-Type: text/plain
        If-Match: "${etag}"
      body: "note version two"
    expect:
      status: [200, 204]
      headers:
        ETag: { present: true }

  - name: the content is replaced
    request:
      method: GET
      target: "${created}"
    expect:
      status: 200
      body:
        matches: "version two"
```

Step by step:

1. **It creates what it needs.** The test POSTs a resource into `${test.container}`, the
   fresh container the executor made for this test. The server chooses the new
   resource's URI, and `bind` stores it from the `Location` header as `${created}`.
2. **It reads state instead of assuming it.** The current `ETag` is bound as `${etag}`.
3. **It accepts what the specification accepts.** A successful PUT may answer `200` or
   `204`, so `status` lists both.
4. **It checks the effect, not just the status.** The last step reads the resource back.
5. **It cites its requirement.** The IRI must exist in the
   [requirements catalog](catalog.md), or the run is refused.

## Top-level fields

| Field | Required | Meaning |
|---|---|---|
| `schemaVersion` | yes | Always `1`. |
| `id` | yes | `<module>/<slug>`: lowercase letters, digits and hyphens. |
| `title` | yes | One line describing what the test proves. |
| `description` | no | The reasoning: which clauses apply, and why the test is built the way it is. |
| `requirements` | yes | Catalog requirement IRIs the test verifies. At least one. |
| `capabilities` | no | Capability keys the target must declare, or the test is skipped. |
| `tags` | no | Free-form labels. The MCP `list_tests` tool can filter on them. |
| `as` | no | The default identity for every step. Leave it out for tests that are not about authentication. |
| `steps` | yes | The ordered steps. At least one. |

## Steps

| Field | Meaning |
|---|---|
| `name` | Describes what the step establishes. Failure reports show it. |
| `as` | The identity for this step only. It overrides the manifest's `as`. |
| `request` | The HTTP request (below). |
| `rawRequest` | Verbatim HTTP/1.1 message text, for malformed requests. The schema reserves it, but the executor does not send raw requests yet, and a step that uses one ends the test in `ERROR`. |
| `expect` | Assertions on the response. Without `expect`, the step is setup only. |
| `bind` | Values to capture from the response for later steps. |
| `timeoutMillis` | This step's timeout. The default is 15 seconds. |

### Requests

| Field | Meaning |
|---|---|
| `method` | `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` or `OPTIONS`. |
| `target` | The URL, as a template. A relative URL resolves against the run root. |
| `headers` | A map from header name to a value, or to a list of values for a repeated header. Values are templates. |
| `body` | An inline body, as a template. |
| `bodyRef` | A body file, relative to the manifest file, sent byte for byte. Use either `body` or `bodyRef`, not both. |

The identity's credentials are added to every request. Redirects are not followed.

### Variables

Strings in `target`, header values, `body` and most expectations are templates. In a
template, `${name}` is replaced by a value:

| Variable | Value |
|---|---|
| `${test.container}` | A fresh container created for this test inside the run root. |
| `${run.root}` | The run's root container. |
| `${target.baseUrl}` | The `baseUrl` the target is registered with. |
| `${<name>}` | A value that an earlier step in the same test bound. |

An unresolved variable is an error: the test ends in `ERROR`. The executor never sends
the literal `${...}` text.

### Binding values

`bind` maps a variable name to an extractor:

| Extractor | Value |
|---|---|
| `header:<Name>` | The first value of that response header. A `Location` value is resolved to an absolute URL. |
| `link:<rel>` | The target of the `Link` header with that relation, resolved against the request URL. It follows RFC 8288: separate or comma-joined fields, quoted parameters, relation lists, case-insensitive matching. |
| `status` | The status code. |
| `body` | The response body as text. |

A missing header or link ends the test in `ERROR`: the step cannot be completed as
written. Binding runs after the step's assertions are evaluated.

## Assertions

Everything under `expect` is checked, and every check is reported separately.

### Status

```yaml
status: 201            # exactly this
status: [200, 204]     # any of these
```

### Headers

Header names are case-insensitive. A header may appear several times in one response
(`Link` often does). Every form below passes if **any** value of the header satisfies
it.

```yaml
headers:
  Content-Type: text/plain                  # shorthand: some value equals this exactly
  Location: { present: true }
  WWW-Authenticate: { absent: true }        # negative assertion
  ETag: { equals: "${etag}" }               # exact value, a template
  Link: { contains: 'rel="up"' }            # substring, a template
  Allow: { matches: '(?s)(?=.*GET)(?=.*PATCH)' }   # regular expression, found anywhere
```

`matches` uses Java regular-expression syntax and searches for a match anywhere in the
value; anchor with `^` and `$` to match the whole value. In YAML, put regular
expressions in single quotes, so that backslashes stay literal.

### JSON

`json` takes a list of assertions on [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901)
locations in the response body:

```yaml
json:
  - pointer: "/items"
    equals: []                 # deep equality; strings inside are templates
  - pointer: "/totalItems"
    equals: 1
  - pointer: "/items"
    count: 1                   # array length, or number of object members
  - pointer: "/id"
    equals: "${storage}"
  - pointer: "/type"
    matches: '(?s).*Storage.*' # regex over the value; arrays and objects are matched as JSON text
  - pointer: "/service/0/serviceEndpoint"
    exists: true
```

A `matches` against an array or object is tested against its JSON text. This lets one
assertion accept both `"Storage"` and `["Storage", ...]`, which the specification allows
in several places.

### RDF graphs

`graph` parses the response as RDF, using the `Content-Type` unless `parseAs` overrides
it, and checks it:

```yaml
graph:
  contains:
    - s: "${created}"
      p: "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
      o: "https://www.w3.org/ns/lws#DataResource"
  notContains:
    - { s: "${test.container}", p: "http://example.org/p", o: { value: "x", lang: "en" } }
  isomorphicTo: fixtures/expected.ttl     # blank-node-safe isomorphism with a fixture file
  shacl: shapes/container.ttl             # the graph must conform to these SHACL shapes
```

In a triple pattern, the value `_:any` matches any blank node. An object can also be a
literal, written as `{ value, lang, datatype }`. Fixture and shape files are relative to
the manifest. JSON-LD is parsed offline, and only the bundled LWS and CID contexts are
available.

### Body

```yaml
body:
  empty: true
  matches: "version two"        # regex over the body text
  equalsRef: bodies/note.txt    # byte-for-byte equality with a fixture file
```

### Content negotiation

```yaml
connegEquivalent:
  accepts: [application/lws+json, application/ld+json]
```

The executor sends the step's request again once for each listed media type. It checks
that each response is `2xx`, that its `Content-Type` echoes the requested type, and that
the bodies are **byte-identical**. When they differ, the report also says whether the
graphs are the same, so a re-serialised document can be told apart from different
content.

## Identities and capabilities

Tests about operations should not name an identity. The target's `defaultIdentity`
decides who they act as, so the same test runs against an open server and against a
protected one.

Tests about authentication name identities explicitly, and declare
`capabilities: [authentication]`:

```yaml
id: auth-oidc/expired-token-401
capabilities: [authentication]
as: alice-expired
steps:
  - name: an expired token is refused
    request:
      method: GET
      target: "${run.root}"
      headers:
        Accept: application/lws+json
    expect:
      status: 401
      headers:
        WWW-Authenticate: { contains: 'error="invalid_token"' }
```

An explicit `as: anonymous` always means "no credentials", even on a target with a
default identity.

## Rules the suite follows

These rules come from mistakes the project has already made and fixed. Follow them in new
tests.

- **Use only what the specification defines.** For example, the draft no longer defines
  `Slug`, so no manifest sends it.
- **Discover, never assume.** Created URIs come from `Location`. The linkset comes from
  `rel="linkset"` and the storage from `rel="https://www.w3.org/ns/lws#storage"`, both
  through `link:` bindings. A test that guesses a path tests one server's convention, not
  the specification.
- **One test, one level.** A failing test fails every requirement it cites, and a test
  that cites a MUST makes the run non-conformant. A SHOULD or MAY check belongs in its
  own test.
- **Match by content, not by position.** Do not assume the order of items in a listing,
  services in a description, or parameters in a challenge.
- **Accept every form the specification allows.** Use status sets, and patterns such as
  `rel="?linkset"?` where quoting is optional.
- **Negative tests check the effect.** After a refused request, check that it changed
  nothing.
- **Quote the clause.** A comment with the clause text next to the assertion makes
  review easy.

## Checking a new test

- **Schema:** Touchstone rejects a manifest that fails validation when it loads it, and
  the error names the offending property. `additionalProperties: false` applies
  throughout, so a misspelt key fails instead of being ignored.
- **Requirements:** `touchstone coverage` warns about IRIs that are not in the catalog.
  `touchstone run` refuses to start (exit `2`).
- **Against the reference server:** run the module against `ref`, or use the MCP
  `run_one` tool for a single test. A new test should pass against the reference server.
  If it cannot, extend the reference server first. A test that nothing can pass proves
  nothing.
- **In the build:** `./mvnw verify` checks that the shipped manifests validate, that
  their requirement IRIs resolve against the shipped catalog, and that the core suite
  passes against the reference server.

## Review

Tests enter the suite only through review: a pull request that a person approves. The
mapping from tests to requirements is what the reports' claims rest on. An agent may
draft a test, for example with the MCP `draft_test` prompt, but the draft goes through
schema validation, a dry run against the reference server, and a pull request. It is
never committed directly.

## Reference

- [The manifest JSON Schema](manifest-schema/manifest.schema.json), version `1-1-0`
- [Schema rationale](manifest-schema/RATIONALE.md): why the schema looks the way it does
- [A worked example](manifest-schema/example-container-containment.yaml) from the schema review
- [Porting Solid tests](harvest.md): how scenarios from the Solid test corpus become manifests
