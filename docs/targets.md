---
title: Targets and credentials
nav_order: 4
description: "Register the servers Touchstone may test, declare what they support, and give the harness the identities and tokens it needs."
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
| `baseUrl` | yes | A container in the storage under test. Touchstone creates its run root inside it, so alice must be allowed to create containers there. The storage root is the usual choice. Tests can read it as `${target.baseUrl}`. |
| `adapter` | no | The provisioning adapter. Default `env`, currently the only one. |
| `capabilities` | no | What the deployment provides that the server cannot reveal about itself: `Authentication`, `HarnessIssuedTokens`, `ReachableFixtures`, `SamlTrust`. A test that needs one the target does not list is inapplicable. [Authentication](auth.md#capabilities) says what each means. |
| `properties` | no | Settings, all strings. See below. |

`run` reads `targets.yaml` from the working directory. Use `--targets <file>` to point at
another registry.

## Identities

Tests never carry credentials. They name identities from
`definitions/lws10/identities.yamlld`:

- `anonymous` sends no credentials;
- `alice` owns the storage. She creates the run root and every test's container, and a
  step that names no identity acts as her;
- `bob` is a second authenticated agent, with no access to alice's resources unless a
  test grants it;
- fault identities, such as `alice-expired`, are a basis identity with one defect;
- subject credentials, such as `didkey` and `oidc`, are the credentials the authentication
  suites exchange for access tokens. The harness mints them.

## Properties

| Property | Meaning |
|---|---|
| `token.<name>` | A literal access token for `alice` or `bob`. Use this only for throwaway tokens; otherwise use the environment (below). |
| `webid.<name>` | The agent IRI of `alice` or `bob`, which grants name and harness-issued tokens carry as `sub`. Also `TOUCHSTONE_WEBID_<NAME>`. |
| `as.signingKey` | The authorization server's private JWK, with `HarnessIssuedTokens`. `as.clientId` sets the `client_id` its tokens carry (default `touchstone`). |
| `didkey.jwk.<name>` | A P-256 private JWK. For `alice` or `bob`, the engine exchanges a did:key credential for their token; for `didkey`, it pins the key the did:key suite uses. |
| `fixtures.baseUrl`, `fixtures.bind` | With `ReachableFixtures`: the URL the server reaches the harness's fixture host at, and where the harness listens if that differs (`host:port`). |
| `saml.idpKey`, `saml.idpCertificate` | With `SamlTrust`: the harness identity provider's RSA private JWK, and its PEM certificate if the server trusts it by certificate. |
| `timeout` | Seconds before a request is abandoned. Default `30`. |
| `parallelism` | How many tests run at once. Default `16`. |

Tokens are looked up in the target property `token.<name>`, then in the environment
variable `TOUCHSTONE_TOKEN_<NAME>`: the name upper-cased, `-` replaced by `_`. On a target
that does not declare `Authentication`, alice and bob send nothing when neither is set.
[Authentication](auth.md#where-tokens-come-from) gives the full order, including tokens the
harness mints or exchanges.

## Testing a protected server

Most real storages do not let anonymous clients create containers. Give alice a token, and
keep it out of the file:

```yaml
targets:
  mine:
    baseUrl: https://storage.my-lws-server.test/alice/
    adapter: env
    capabilities: []
```

```sh
export TOUCHSTONE_TOKEN_ALICE='<token>'
touchstone run --target mine
```

Without a token for alice, the run stops before any test: the run root cannot be created,
and the exit code is `2`. With a second agent's token in `TOUCHSTONE_TOKEN_BOB`, add
`Authentication` to `capabilities`, and the access-control tests apply too.

{: .warning }
Never write a real credential into `targets.yaml`. The file is checked in and ships with
the harness. Supply tokens through the environment, for example as CI secrets.

**For an LWS OpenID Connect server**, the token the storage expects is an OpenID Connect
**ID Token**, not an access token. Its `sub` must be the agent's WebID, and it must come
from the OpenID Provider that the WebID's controlled identifier document names. The
storage follows that chain to decide whether to trust the issuer. Which provider, which
client and which grant to use are facts about your deployment. They belong in your
environment, not in the repository.

ID Tokens are short-lived; five minutes is a common default. A run of every definition
takes seconds, so one token covers a run; mint a fresh one for each session.

## What the server must allow

alice must be able to:

- **create a container** under `baseUrl`, by POSTing with the `lws#Container` type link
  and receiving `201` with a `Location` header;
- **delete what she created.** Each test deletes its own container at the end, with
  `Depth: infinity` and `If-Match`. Recursive delete is optional in the draft; on a server
  without it, the harness deletes bottom-up by listing, and logs a warning naming anything
  left behind.

Touchstone also sends a `Slug` header when it creates a container, so run roots are easy
to recognise in your storage. The current draft does not define `Slug`, and nothing
depends on the server honouring it.
