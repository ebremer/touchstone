---
title: Targets and credentials
nav_order: 4
description: "Register the servers Touchstone may test, and give it the identities and tokens it needs."
---

# Targets and credentials
{: .no_toc }

1. TOC
{:toc}

## The target registry

Touchstone tests only servers registered in advance. `targets.yaml` maps a short id to a
server. Every front end takes that id and never a URL: the `--target` option, the MCP
tools' `targetId` argument, and the GitHub Action, which writes a registry for you.

This is a security boundary rather than a convenience. Touchstone sends malformed and
hostile requests on purpose. If it accepted arbitrary URLs, anyone who could reach it
could aim it at someone else's server. Only the person who edits the registry decides
which servers can be tested.

```yaml
targets:
  ref:
    baseUrl: http://localhost:4711/
    adapter: env
    capabilities: []
    properties: {}
```

| Key | Required | Meaning |
|---|---|---|
| `baseUrl` | yes | A container in the storage under test. Touchstone creates its run root inside it, so the provisioning identity must be allowed to create containers there. The storage root is the usual choice. Tests can read it as `${target.baseUrl}`. |
| `adapter` | no | The provisioning adapter. Default `env`, currently the only one. |
| `capabilities` | no | Capability keys the server supports. Tests that require a capability the target does not list are skipped. The auth tests require `authentication`. |
| `properties` | no | Adapter settings, all strings. See below. |

`run` reads `targets.yaml` from the working directory. Use `--targets <file>` to point at
another registry.

## Identities

Tests refer to abstract identities: `alice`, `bob`, `alice-expired`, and so on. The name
`anonymous` is reserved and means no credentials. Every other name is resolved by the
target's adapter.

A step's identity is chosen in this order: the step's `as`, then the manifest's `as`,
then the target's `defaultIdentity`, then `anonymous`. The core tests declare no identity,
because the operations they test are not about authentication. On an open server they
run anonymously. On a protected one they act as whatever `defaultIdentity` names.

## The `env` adapter

The `env` adapter does two things:

- **It creates the run root.** It POSTs a new container to `baseUrl`, acting as the
  provisioning identity. It tries to delete the run root again when the run ends.
- **It turns identities into bearer tokens.** For an identity `name`, it sends
  `Authorization: Bearer <token>` and looks the token up in this order:
  1. the target property `token.<name>`;
  2. the environment variable `TOUCHSTONE_TOKEN_<NAME>`, with the name upper-cased and `-`
     replaced by `_`. For example, the token for `alice-expired` is read from
     `TOUCHSTONE_TOKEN_ALICE_EXPIRED`.

  If neither is set, the step ends in `ERROR` with a message naming both places.

| Property | Meaning |
|---|---|
| `defaultIdentity` | The identity for every step whose test declares none. Set it when the storage is not world-readable and world-writable. |
| `provisioner` | The identity that creates and deletes the run root and the per-test containers. It defaults to `defaultIdentity`, then to `anonymous`. |
| `token.<name>` | A literal token for identity `<name>`. Use this only for throwaway tokens, such as the ones the secured reference scenario generates. |

## Testing a protected server

Most real storages do not let anonymous clients create containers. Name one identity for
the suite to act as, and keep its token out of the file:

```yaml
targets:
  mine:
    baseUrl: https://storage.example/alice/
    adapter: env
    properties:
      defaultIdentity: touchstone
```

```sh
export TOUCHSTONE_TOKEN_TOUCHSTONE='<token>'
touchstone run --target mine --module core
```

Without `defaultIdentity`, every core step would run anonymously. The run would stop at
provisioning, or every test would fail with `401`.

{: .warning }
Never write a real credential into `targets.yaml`. The file is checked in and ships with
the harness. Supply tokens through the environment, for example as CI secrets.

**For an LWS OpenID Connect server**, the token the storage expects is an OpenID Connect
**ID Token**, not an access token. Its `sub` must be the agent's WebID, and it must come
from the OpenID Provider that the WebID's controlled identifier document names. The
storage follows that chain to decide whether to trust the issuer. Which provider, which
client and which grant to use are facts about your deployment. They belong in your
environment, not in the repository.

ID Tokens are short-lived; five minutes is a common default. A core run against a remote
server takes on the order of a minute, so mint a fresh token for each session.

## What the server must allow

The provisioning identity must be able to:

- **create a container** under `baseUrl`, by POSTing with the `lws#Container` type link
  and receiving `201` with a `Location` header;
- **delete a non-empty container** with `Depth: infinity`. Recursive delete is optional
  in the draft. On a server without it, run roots are left behind, and each run logs a
  warning naming its run root.

Touchstone also sends a `Slug` header when it creates the run root, so the container is
easy to recognise in your storage. The current draft does not define `Slug`, and nothing
depends on the server honouring it.
