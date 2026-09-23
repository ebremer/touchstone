---
title: Troubleshooting
nav_order: 14
description: "Common errors, what they mean, and what to do about them."
---

# Troubleshooting
{: .no_toc }

1. TOC
{:toc}

## The run refuses to start (exit code 2)

Exit code `2` means there is no verdict: nothing about the server was concluded, and no
report bundle was written. The reason is printed on standard error.

| Message | Cause and fix |
|---|---|
| `target registry not found: targets.yaml` | The CLI resolves paths against the working directory. Run it from the repository root, or pass `--targets <file>`. |
| `cannot read the target registry: ...` | The registry is not valid YAML. The lines after it say where. |
| `unknown target 'x' (registered: [...])` | The id is not in the registry. Add the server to `targets.yaml`; see [Targets and credentials](targets.md). |
| `no test matches 'x'; try all, a module (core, auth), a manifest (...) or a test id` | `--module` takes `all`, a module, a manifest path, or a test's id or name. The message lists the manifests. |
| `no definitions at ... (expected lws10/manifest.yamlld)` | `--definitions` must name the `definitions/` directory. Run from the repository root, or pass the path. |
| `... violates the definitions schema:` | The next lines name the file and the property. Misspelt keys are the usual cause, because the schema allows no extra properties. |
| `the definitions fail the lint (EXECUTION.md section 2.5):` | The next lines name each test and its defect: a requirement IRI the catalog does not hold, an unbound variable, an example host, an unknown identity. |
| `the definitions are written in format ..., but this engine implements ...` | The definitions and the engine are from different format versions. Use the definitions that came with the harness. |
| `cannot run against target 'x': cannot create the run root: POST ... as alice failed: java.net.ConnectException` | Nothing is listening at `baseUrl`. Check that the server is running, and that the URL, including the trailing slash, is right. |
| `cannot run against target 'x': cannot create the run root: POST ... as alice answered 401 ..., not 201` | alice has no credential the server accepts. Supply her token as `TOUCHSTONE_TOKEN_ALICE`. |
| `cannot run against target 'x': cannot create the run root as alice: no credential for alice ...` | The target declares `Authentication` but gives alice no token. Set `TOUCHSTONE_TOKEN_ALICE`, or declare `HarnessIssuedTokens` with `as.signingKey`. |

A stack trace with exit code `2` is a harness bug. Please report it.

## Many tests are inapplicable

`inapplicable` is not a failure: the test needs something this target does not have, and
the report says what. The usual reasons:

- `the target does not declare Authentication` (or another capability): declare it in the
  target's `capabilities` once the deployment provides it; see
  [Authentication](auth.md#capabilities).
- `precondition '...' does not hold`: the server does not offer an optional feature the
  test is about, such as notifications.
- `the storage description at ... advertises no AccessGrantService`: the tests that need
  access granted cannot set it up.
- `no credential for bob ...` or `no agent IRI for bob ...`: supply
  `TOUCHSTONE_TOKEN_BOB`, and `webid.bob` for tests that grant bob access.

An inapplicable MUST test does not make a server non-conformant, but it is coverage the run
did not have, and the report lists it.

## A test ends `cantTell` rather than `failed`

`cantTell` means the harness could not decide. Common causes:

- `cannot create the test container: ...` or `prerequisite: create x: POST ... answered
  N, not 201`: setting up the test failed. The tests about creating resources report that
  failure themselves.
- A transport error or timeout: the connection failed, or a response took longer than 30
  seconds (the target property `timeout` changes it). Nothing is retried.
- `${storage}: GET ... as alice answered N with no Link whose rel is
  https://www.w3.org/ns/lws#storage`: a variable derived from the server could not be
  derived. The discovery tests report the missing link as a failure.

A MUST test that ends `cantTell` makes the run non-conformant: a server cannot be declared
conformant on evidence the harness could not gather.

## A warning says something was left behind

The warning, on standard error, names what could not be deleted:

```text
run 9da51679: http://.../touchstone-run-9da51679/ left on the target: it could not be deleted
```

Each test deletes its own container, with a recursive `DELETE` (`Depth: infinity`), which
the draft makes optional. When a server refuses it, the harness deletes the container's
members one by one, and warns only if that fails too. The run's results are unaffected.
Delete the container by hand, or ignore it.

## A conneg test reports different bytes

`connegEquivalent` wants byte-identical bodies for every media type it asks for. When
they differ, the report says whether their JSON-LD graphs are still the same: "isomorphic"
means the server re-serialised one graph, "not isomorphic" that it returned different
content. Touchstone parses JSON-LD offline, and knows only the LWS and CID contexts; a
response naming any other context cannot be compared. This is deliberate; see
[Security model](security.md#3-responses-from-the-server-under-test-are-untrusted).

## An MCP client gets no answer over stdio

Everything the server writes to standard output must be a protocol message, so its logs
go to `touchstone-mcp.log` in the working directory, not to the terminal. Look there
first. Give the server absolute `--touchstone.*` paths too: the client, not you, chooses
its working directory. See [MCP server](mcp.md#over-stdio).

## A change to the code does not seem to take effect

The CLI jar is shaded in place, and an incremental build can shade the previous jar
again, keeping its old classes. Build with `clean`: `./mvnw clean install`, and check the
artifact rather than the source.
