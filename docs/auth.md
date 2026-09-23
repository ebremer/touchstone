---
title: Authentication suites
nav_order: 11
description: "How Touchstone tests credential handling: harness-owned identity fixtures, the OpenID Connect negative matrix, did:key, CID and SAML."
---

# Authentication suites
{: .no_toc }

1. TOC
{:toc}

## Why the harness owns the identity providers

The interesting authentication tests are the negative ones. Does the server refuse a token
that is expired, signed by the wrong key, meant for another audience, or signed with
`alg: none`? And does it answer `401` with a proper `WWW-Authenticate` challenge? A real
identity provider will not mint broken credentials on request, so Touchstone brings its
own. The fixtures are library-level fakes, built on Nimbus JOSE+JWT and BouncyCastle, that
issue valid credentials and deliberately broken ones.

## Status by suite

| Suite | Catalog | How it is tested |
|---|---|---|
| Access tokens (core authorization) and OpenID Connect | `lws10-core` authorization clauses, `lws10-authn-openid` | The `auth-oidc` module: 9 manifests run against a storage server that trusts Touchstone's issuer. The self-test loop proves they pass against a compliant server and fail against a broken one. |
| did:key | `lws10-authn-ssi-did-key` | At fixture level: real Ed25519 keys and self-issued JWTs. A valid credential verifies, and every broken variant is rejected. |
| Controlled Identifiers (CID) | `lws10-authn-ssi-cid` | At fixture level, as for did:key. The verifier dereferences a CID document that the harness hosts, and picks the key by `kid`. |
| SAML 2.0 | `lws10-authn-saml` | Catalogued only. The fixture needs OpenSAML, and is a recorded seam that has not been built yet. |

## The OpenID Connect module

The `auth-oidc` manifests declare `capabilities: [authentication]`, so they run only
against targets that list that capability.

| Test | Identity | Expects |
|---|---|---|
| `valid-token-access` | `alice` | `200`: the owner's valid token is accepted. |
| `anonymous-request-401-challenge` | `anonymous` | `401` with a `WWW-Authenticate` challenge carrying `as_uri` and `realm`. |
| `expired-token-401` | `alice-expired` | `401` with `error="invalid_token"`. |
| `bad-signature-401` | `alice-bad-signature` | `401` with `error="invalid_token"`. |
| `wrong-audience-401` | `alice-wrong-audience` | `401` with a challenge. |
| `wrong-issuer-401` | `alice-wrong-issuer` | `401` with a challenge. |
| `unknown-key-401` | `alice-unknown-key` | `401` with a challenge, for a `kid` that is not in the issuer's JWKS. |
| `alg-none-401` | `alice-alg-none` | `401` with a challenge, for an unsigned token. |
| `non-owner-403` | `bob` | `403` for a valid identity that is not the owner. |

Every test requests the run root, which `alice` owns because she provisioned it.

Key rotation during a session is tested at fixture level: after the issuer retires its
signing key, a token that was valid stops validating.

### Run it yourself

The **secured reference scenario** starts Touchstone's OpenID Connect issuer and a
reference storage server that trusts it. Then it writes a target registry with freshly
minted tokens for every identity above. Build once (`./mvnw -q install -DskipTests`), then
start the scenario from the repository root:

```sh
./mvnw -q -pl harness-fixtures exec:exec -Dexec.executable=java \
  "-Dexec.args=-cp %classpath com.ebremer.touchstone.fixtures.SecuredRefScenarioMain ../targets-secured.yaml"
```

The `exec:exec` goal runs in the `harness-fixtures` directory, which is why the path
starts with `../`. The command writes `targets-secured.yaml` to the repository root.
Git ignores that file. The scenario prints the issuer and server addresses and keeps
running. In a second terminal, run:

```sh
touchstone run --target secured-ref --module auth-oidc --targets targets-secured.yaml
```

```text
[PASSED] auth-oidc/alg-none-401 (18 ms)
[PASSED] auth-oidc/anonymous-request-401-challenge (18 ms)
...
9 passed, 0 failed, 0 errors, 0 skipped  (target secured-ref, run eaaf22bc)
```

The tokens are minted once, when the scenario starts, and expire five minutes later.
Restart the scenario to get fresh ones.

The same target also runs the core suite, but only once the suite acts as the owner.
Add `defaultIdentity: alice` under the target's `properties`; without it, every core
request is anonymous and is refused.

{: .note }
`SecuredRefScenarioMain`'s own Javadoc suggests `exec:java -Dexec.mainClass=...`. That
does not work: the module's POM fixes `exec:java` to the plain reference server, which
then tries to parse the file name as a port number. Use the `exec:exec` form above.

### Against a third-party server

The negative tests need tokens that a third-party server trusts but that are broken in
controlled ways. Only an issuer the server already trusts can mint those, so the harness
would have to be that server's authorization server. For a third-party server, therefore:

- run `--module core` with a `defaultIdentity` and a real token (see
  [Targets and credentials](targets.md#testing-a-protected-server));
- rely on the self-test loop for the negative matrix. It shows that the tests themselves
  can tell a compliant server from a broken one.

You can also supply real tokens as `TOUCHSTONE_TOKEN_<NAME>` and declare
`capabilities: [authentication]` on your target. The tests that need no broken token are
then meaningful: `valid-token-access` and `non-owner-403` with real tokens for `alice`
and `bob` (with `alice` as the `provisioner`), and `anonymous-request-401-challenge`.

## did:key and CID

Both suites use **self-signed JWT** credentials. The subject, the issuer and the client
id are all one URI; `alg` is never `none`; `exp` is in the future; and the signature
verifies against a key derived from the identifier. For did:key, the key is decoded from
the identifier itself. For CID, it comes from the controlled identifier document, found
by `kid`.

The fixtures (`DidKey`, `SelfIssuedJwt`, `SelfIssuedCredentials`, `IdentityDocumentHost`
and `SelfIssuedVerifier`) generate real Ed25519 keys and sign with them. The negative
matrix checks that a valid credential verifies. It also checks that the verifier rejects
each broken variant: `alg: none`, a bad signature, an expired token, mismatched claims,
and a wrong audience.

The step from credential to token exchange to storage access has not been built for
these suites yet. The [YAML-LD definitions](definitions.md) specify it: 8 did:key tests
and 6 CID tests, including the token exchange.

## SAML

The seven SAML requirements are catalogued. Building the fixture means adding OpenSAML 5
from the Shibboleth repository and implementing signed assertions and their broken
variants, mirroring the other fixtures. Until it exists, the SAML suite is not tested.
The YAML-LD definitions include three SAML tests that are ready for when it arrives.
