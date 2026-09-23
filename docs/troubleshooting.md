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
| `unknown target 'x' (registered: [...])` | The id is not in the registry. Add the server to `targets.yaml`; see [Targets and credentials](targets.md). |
| `no manifests for module 'x' under manifests` | The module name is the name of a directory under `manifests/`, such as `core` or `auth-oidc`. |
| `manifest ... violates schema v1:` | The next lines name the property. Misspelt keys are the usual cause, because the schema allows no extra properties. |
| A list of manifests with requirement IRIs, then `Fix the manifest, or add the requirement to catalog.` | A manifest cites a requirement that is not in the catalog. Fix the IRI; the typo is usually in the slug. |
| `cannot run against target 'x': cannot create container 'touchstone-run-...' under ...`, then `caused by: java.net.ConnectException` | Nothing is listening at `baseUrl`. Check that the server is running, and that the URL, including the trailing slash, is right. |
| `cannot run against target 'x': container creation under ... returned 401 (expected 201)` | The run root is created as the provisioning identity, which defaults to `anonymous`. Set `defaultIdentity`, or `provisioner`, on the target, and supply its token. |
| `cannot run against target 'x': no credential configured for identity 'y' ...` | The provisioning identity has no token. Set `TOUCHSTONE_TOKEN_Y`, or the property `token.y`; the message names both. When a step's identity has no token instead, that test ends in `ERROR` with the same message. |

A stack trace with exit code `2` is a harness bug. Please report it.

## Every test fails or errors with 401

The tests are running anonymously against a protected server. Core tests declare no
identity, so they act as the target's `defaultIdentity`, and fall back to `anonymous`
when none is set. Set it:

```yaml
    properties:
      defaultIdentity: touchstone
```

Then export `TOUCHSTONE_TOKEN_TOUCHSTONE`. For LWS OpenID Connect servers, the token must
be an ID Token for the agent's WebID, and it expires quickly. See
[Targets and credentials](targets.md#testing-a-protected-server).

## A test is `ERROR` rather than `FAILED`

`ERROR` means the harness could not finish the test. Common causes:

- `bind 'x': response has no header Location` (or no matching `Link`): an earlier
  request did not produce the value later steps need, usually because that request was
  itself refused. Look at the status assertion in the same step.
- `unresolved template variable ${x}`: the manifest uses a variable that no earlier step
  binds.
- `transport error: ...`: the connection failed or timed out. The default timeout is 15
  seconds per request, and nothing is retried.

## A warning says the run root was left behind

The warning, on standard error, looks like this:

```text
run 9da51679: run root http://.../touchstone-run-9da51679/ left on the target - DELETE returned 409
```

Cleanup uses a recursive `DELETE` (`Depth: infinity`), which the draft makes optional. A
server without it answers `409` for a non-empty container. The run's results are
unaffected. Delete the container by hand, or ignore it.

## A graph assertion fails with a JSON-LD context error

Touchstone parses JSON-LD offline and bundles only the LWS and CID contexts. A response
that references any other remote context cannot be parsed, and the assertion fails with
a message listing the bundled contexts. This is deliberate; see
[Security model](security.md#3-responses-from-the-server-under-test-are-untrusted).

## An MCP client gets no answer over stdio

Everything the server writes to standard output must be a protocol message, so its logs
go to `touchstone-mcp.log` in the working directory, not to the terminal. Look there
first. Give the server absolute `--touchstone.*` paths too: the client, not you, chooses
its working directory. See [MCP server](mcp.md#over-stdio).

## A change to the code does not seem to take effect

After an interrupted build, a module jar can contain a stale copy of `harness-core`. Run
`./mvnw clean install`, and check the artifact rather than the source.
