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

| Message | Cause and fix |
|---|---|
| `target registry not found: targets.yaml` | The CLI resolves paths against the working directory. Run it from the repository root, or pass `--targets <file>`. |
| `unknown target 'x' (registered: [...])` | The id is not in the registry. Add the server to `targets.yaml`; see [Targets and credentials](targets.md). |
| `no manifests for module 'x' under manifests` | The module name is the name of a directory under `manifests/`, such as `core` or `auth-oidc`. |
| A list of manifests with requirement IRIs, then `Fix the manifest, or add the requirement to catalog.` | A manifest cites a requirement that is not in the catalog. Fix the IRI; the typo is usually in the slug. |

## The run stops with a stack trace (exit code 1)

| First line | Cause and fix |
|---|---|
| `InvalidManifestException: manifest ... violates schema v1:` | The next lines name the property. Misspelt keys are the usual cause, because the schema allows no extra properties. |
| `ProvisioningException: cannot create container 'touchstone-run-...' under ...` | The server is unreachable at `baseUrl`. Check that it is running, and that the URL, including the trailing slash, is right. |
| `ProvisioningException: container creation under ... returned 401 (expected 201)` | The run root is created as the provisioning identity, which defaults to `anonymous`. Set `defaultIdentity`, or `provisioner`, on the target, and supply its token. |
| `ProvisioningException: no credential configured for identity 'x' ...` | The provisioning identity has no token. Set `TOUCHSTONE_TOKEN_X`, or the property `token.x`; the message names both. When a step's identity has no token instead, that test ends in `ERROR` with the same message. |

These cases exit with `1`, the same code as a non-conforming server. If CI needs to tell
the two apart, check whether a report bundle was written: a run that stops with a stack
trace writes none.

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

## The MCP server started over stdio but answers nothing

Add `--spring.ai.mcp.server.protocol=STREAMABLE` to its arguments. The shipped `stdio`
profile sets a protocol value that Spring AI 2.0 does not recognise, and that disables
the MCP server entirely. See [MCP server](mcp.md#over-stdio).

## `exec:java` runs the wrong main class

`./mvnw -pl harness-fixtures exec:java` always starts the plain reference server,
whatever `-Dexec.mainClass` says, because the module's POM fixes the main class. A
`NumberFormatException` for your argument is the symptom. For other launchers, use
`exec:exec`, as shown for the
[secured reference scenario](auth.md#run-it-yourself).

## A change to the code does not seem to take effect

After an interrupted build, a module jar can contain a stale copy of `harness-core`. Run
`./mvnw clean install`, and check the artifact rather than the source.
