# Factoidal's LWS client, through touchstone-driver/1

`adapter.mjs` lets Touchstone drive the LWS client of [Factoidal](https://github.com/danbri/factoidal-core)
(Dan Brickley; RDF and LWS written in Lean 4, compiled to one wasm module run by Node.js) through a
client session. It speaks [`touchstone-driver/1`](../../PROTOCOL.md), and is built on the
[JavaScript starter kit](../../kits/js), which it imports from `driver/kits/js`. Touchstone keeps it
because Factoidal's own adapter (`deploy/lws-client-adapter` in its repository) speaks lws-client's
earlier protocol, `lws-driver/1` (D-0109).

The client is the package `@factoidal/core` 0.18.0 (factoidal-core `v0.18.0`, `788ee43`), pinned with
its integrity in `package-lock.json`, the same package as the Factoidal target server
(`targetservers/factoidal`). Its module `@factoidal/core/lws/client` is a socket around a Lean state
machine (`formal/lean4/L4Factoidal/LWS/Client/` there), which chooses and builds every request: the
authorization flow and its discovery checks, preconditions, paging, linksets, and whether a
notification verifies.

## Run it

It needs Node.js 22.12 or later.

```sh
npm ci
java -jar ../../../harness-cli/target/touchstone.jar driver check -- node adapter.mjs
```

`adapters.yaml` registers it as `factoidal`, so, with a client-session service running (see the top
of `adapters.yaml`):

```sh
touchstone drive --client factoidal
```

`FACTOIDAL_CLIENT_MODULE` names another copy of `lws/client.mjs`, a local build of Factoidal's
`npm/factoidal` for one; the version in the hello is read from the `package.json` above it.

Results on 2026-10-29, against the local client-session service:
- `touchstone driver check`: 34 of 34;
- `touchstone drive --client factoidal`: 52 rules passed, none failed, one untested,
  `client-query-baseline-after-415`, which has no drive (a client that sends only the baseline
  query format never meets the 415). That is lws-client's result too.

## What the adapter decides

Nothing about the wire, and nothing about reading a response: the client reports each result in the
shapes of `lws-driver/1` (its `report`), which `touchstone-driver/1` kept, and names each error. The
adapter only:

1. turns each operation into one run of the client (the table below), and a `body` (`text`, `base64`
   or `json`) into bytes;
2. makes the client's error names the protocol's kinds: `AuthenticationError` is `authentication`,
   `ProtocolError` `protocol`, `SignatureVerificationError` `verification`, `TransportError`
   `transport`, and a status-specific error (`NotFoundError`, `ConflictError`, …) is `http` with
   its status;
3. cuts a listing or search the client followed to its end at `limit`, and says `truncated`;
4. gives `request_access` and `grant_access` their document's policy as the client's members
   (actions, assignee, targets, constraint, inbox): the client writes the document itself;
5. gives `subscribe` its first topic as the operation's URL. Factoidal 0.18.0 subscribes from a
   resource of the storage: it finds the storage description from the resource, and POSTs to the
   notification service that names, which is `serviceUrl`. Factoidal's main branch takes the
   endpoint itself (`endpoint`);
6. gives the client's `fetch` the `userAgent` and `timeoutSeconds` of `configure`, nothing else.

| Operation | The client's operation |
|---|---|
| `discover_storage`, `head`, `read` | `discover`, `head`, `read` |
| `list_container`, `list_subscriptions`, `list_access_requests`, `list_access_grants` | `list`, every page |
| `create`, `create_container` | `create` (`container: true` for a container); `links` as `linkObjects` |
| `update`, `patch`, `delete`, `move` | `update` (`links` as `linkObjects`), `patch`, `delete`, `move` |
| `read_linkset`, `update_linkset`, `patch_linkset` | `readLinkset`, `putLinkset`, `updateLinkset` |
| `request_access`, `grant_access` | `requestAccess`, `grantAccess` |
| `get_access_request`, `get_access_grant` | `getAccess` |
| `cancel_access_request`, `revoke_access_grant` | `delete`, `revokeAccess` |
| `subscribe`, `get_subscription`, `unsubscribe` | `subscribe`, `getSubscription`, `unsubscribe` |
| `verify_notification` | `verifyNotification`; a refused delivery is a `verification` error |
| `list_types`, `search` | `typeIndex`, `search`, at the service endpoint |

## Authentication

| `auth` | The client's session |
|---|---|
| `none` | nothing |
| `bearer` | `bearer`: the token, sent with every request |
| `cid` | `selfIssued`: the client signs its own credentials as the agent with the key (P-256 or Ed25519), and exchanges them |
| `openid` | `idToken`, exchanged |
| `saml` | `subjectToken`, of the type `urn:ietf:params:oauth:token-type:saml2`, exchanged |

`configure` builds a new client each time, so the tokens of the last one go with it.

## What it answers `unsupported`

- a write's `ifNoneMatch` other than `*`, and `ifMatch` with `ifNoneMatch`: 0.18.0 sends one
  precondition, `If-Match` with a tag or `If-None-Match: *`;
- an access document with more than one policy: 0.18.0 writes one. A document with none is
  `invalidArguments`.

Factoidal's main branch, past 0.18.0, lifts all three (`ifNoneMatchTags`, `policies`) and takes
`subscribe`'s endpoint. Moving to a release with them is `package.json`, `package-lock.json` and
those three places in `adapter.mjs`.
