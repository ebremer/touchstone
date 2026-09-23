---
title: Authentication suites
nav_order: 11
description: "How Touchstone tests credential handling: harness-owned identity fixtures, the access-token negative matrix, token exchange, and the did:key, OpenID Connect, CID and SAML suites."
---

# Authentication suites
{: .no_toc }

1. TOC
{:toc}

## Why the harness owns the identity providers

The interesting authentication tests are the negative ones. Does the server refuse a token
that is expired, signed by the wrong key, meant for another audience, or signed with
`alg: none`? Does the authorization server refuse a credential whose signature does not
verify, or an ID Token from a provider the subject never named? And does the storage answer
`401` with a proper `WWW-Authenticate` challenge? A real identity provider will not mint
broken credentials on request, so Touchstone brings its own, and makes each one with exactly
one defect. The definitions name these as identities (`alice-expired`,
`didkey-bad-signature`, `oidc-untrusted-issuer`); `definitions/lws10/identities.yamlld`
says what each defect is.

## What is tested

| Area | Tests | What they exercise |
|---|---|---|
| Storage authorization (`core/storage_authorization`) | 17 | The 401 challenge, the owner's access, a non-owner refused, and the access-token negative matrix: expired, not yet valid, issued in the future, wrong audience, two audiences, wrong issuer, corrupted signature, unknown key, `alg: none`. |
| Authorization server (`core/authorization_server`) | 8 | RFC 8414 metadata at `/.well-known/lws-configuration`, and RFC 8693 token exchange: a valid exchange, an unknown or missing resource, a missing subject token, and the storage accepting the token it issued. |
| Access grants (`core/access_grants`) | 7 | The access grant service, public and per-agent grants, revocation, access requests. |
| did:key (`auth/did_key`) | 8 | Token exchange with a self-issued did:key credential, and seven defects: signature, `alg: none`, expired, mismatched claims, an audience without the authorization server, no `exp`, no `iat`. |
| OpenID Connect (`auth/oidc`) | 6 | Token exchange with an ID Token from the harness's OpenID Provider, which the subject's controlled identifier document names, and five defects, including a provider the subject never named. |
| Controlled Identifiers (`auth/cid`) | 6 | Token exchange with a self-issued credential whose key the verifier finds in a CID document the harness hosts, and five defects, including a `kid` that names no key. |
| SAML 2.0 (`auth/saml`) | 3 | Token exchange with a signed assertion from the harness identity provider, an altered one, and an unsigned one. |

All of them pass against the secured reference deployment, and the self-test loop shows
that each negative test fails against a broken twin that accepts what it should refuse.

## Capabilities

What a test needs from the deployment, beyond the server itself, is a capability the target
declares. A test that needs one the target lacks is inapplicable.

| Capability | Meaning | The target provides |
|---|---|---|
| `Authentication` | The storage challenges anonymous requests, and alice and bob are distinct authenticated agents. | Tokens for alice and bob (see below), and their agent IRIs as `webid.alice` and `webid.bob` when grants name them. |
| `HarnessIssuedTokens` | The storage trusts an authorization server whose signing key the harness holds, so the harness can mint valid tokens with one chosen defect. | `as.signingKey`: the authorization server's private JWK. |
| `ReachableFixtures` | The target can reach the harness's fixture host, which serves the CID and OpenID documents it dereferences. | `fixtures.baseUrl`: the URL the target uses for it, and `fixtures.bind` if the harness must listen elsewhere. |
| `SamlTrust` | The authorization server trusts the harness identity provider's signing key. | `saml.idpKey`: an RSA private JWK, and `saml.idpCertificate` if the trust is by certificate. |

## Where tokens come from

alice and bob send `Authorization: Bearer <token>`, from the first source that applies:

1. **Minted by the harness,** with `HarnessIssuedTokens`: an RFC 9068 access token signed
   with the authorization server's key, for the realm the storage's challenge names.
2. **Exchanged,** when `didkey.jwk.<name>` gives the identity a P-256 did:key: the engine
   exchanges a credential for it at the token endpoint, exactly as the token-exchange tests
   do.
3. **Static,** from `token.<name>` or `TOUCHSTONE_TOKEN_<NAME>`.
4. **None,** on a target that does not declare `Authentication`.

A fault identity starts from its basis's token. Three faults need no key, so they apply
to any target with real JWTs: a corrupted signature, `alg: none`, and a re-signature by a
key nobody publishes. The others need `HarnessIssuedTokens`, except `Expired`, which
without it waits for a real token to expire, when that is at most ten minutes away.

The subject credentials of the four suites are minted per test: a P-256 did:key per run,
a CID key published in a document the harness hosts, ID Tokens from the harness's own
OpenID Provider (and from a rogue one), and SAML assertions signed with enveloped XML
Signature by the JDK's own XML Signature API.

## Run it yourself

The **secured reference deployment** starts the reference authorization server and a
reference storage that trusts it, and writes a target registry that declares all four
capabilities. Build once (`./mvnw -q clean install -DskipTests`), then start it from the
repository root:

```sh
./mvnw -q -pl harness-fixtures exec:java \
  -Dexec.mainClass=com.ebremer.touchstone.fixtures.SecuredRefScenarioMain \
  -Dexec.args=targets-secured.yaml
```

The command writes `targets-secured.yaml` to the repository root; Git ignores that file,
which holds throwaway keys. It prints the two servers' addresses and keeps running. In a
second terminal:

```sh
touchstone run --target secured-ref --targets targets-secured.yaml --module auth
```

```text
[passed      ] MUST   auth/did_key/manifest#authn-didkey-valid-credential (194 ms)
[passed      ] MUST   auth/did_key/manifest#authn-didkey-invalid-signature (181 ms)
[passed      ] MUST   auth/did_key/manifest#authn-didkey-alg-none (125 ms)
...
23 passed, 0 failed, 0 cantTell, 0 inapplicable  (target secured-ref, run 19803b75)
conformant: no MUST test failed or ended cantTell
```

Leave out `--module` to run all 101 definitions against it.

Key rotation during a session is tested at fixture level: after the authorization server
retires its signing key, a token that was valid stops validating.

## Against a third-party server

- **Storage authorization.** With real tokens for alice and bob as
  `TOUCHSTONE_TOKEN_ALICE` and `TOUCHSTONE_TOKEN_BOB`, declare `Authentication`. The
  challenge, owner and non-owner tests apply, and so do the three signature faults if the
  tokens are JWTs. The other faults need `HarnessIssuedTokens`, which only a deployment
  whose authorization server shares its key with the harness can declare; the self-test
  loop shows those tests can tell a compliant server from a broken one.
- **Token exchange and did:key.** A did:key needs no trust set up in advance, so the
  authorization server tests and the did:key suite apply to any server whose metadata
  lists `did:key` among its subject identifier types. They are inapplicable otherwise.
- **CID and OpenID Connect.** The authorization server must reach the harness's fixture
  host: declare `ReachableFixtures` and set `fixtures.baseUrl` to the harness's address as
  the server sees it.
- **SAML.** The authorization server must trust the harness identity provider: declare
  `SamlTrust`, give its key as `saml.idpKey`, and configure the server to trust it.

[Targets and credentials](targets.md) shows the registry entries.
