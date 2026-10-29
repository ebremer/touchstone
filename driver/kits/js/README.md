# JavaScript starter kit for touchstone-driver/1

This kit makes your JavaScript or TypeScript LWS client drivable by Touchstone. Touchstone's driver
starts your adapter, sends it operations such as "create this resource in that container" on stdin,
and judges the requests your client sends to a client session. The protocol is
[`driver/PROTOCOL.md`](../../PROTOCOL.md); this kit handles all of it, so you only map your client to
a class. It needs Node.js 18 or later, and nothing else.

| File | What it is |
|---|---|
| `lws-driver-adapter.mjs` | `LwsDriverAdapter`, one method per operation of the protocol, each answering `unsupported` until you override it; and `AdapterError`, an outcome that is not a result, such as a 404 |
| `lws-driver-adapter.d.mts` | its TypeScript types: each operation's arguments and result, the client info, the error |
| `adapter-runner.mjs`, `adapter-runner.d.mts` | the protocol: the hello, one request a line, one response a line. Copy it as it is |
| `stub.mjs` | the starting point: implements only `configure` |

## Make it yours

1. Copy this directory into your client's repository (as `lws-driver/`, say), and rename `stub.mjs`
   to `adapter.mjs`.
2. In it, give your client's name in `clientInfo()`, and build your client in `configure` from the
   authentication it is given (section 5.1: `none`, `bearer`, `cid`, `openid`, `saml`). Keep the
   client in a field. List the kinds your client supports in `authKinds()`.
3. Implement the operations your client has, one method each, named as the operation in camelCase:
   `list_container` is `listContainer`. A method may be async. Call your client's own operation with
   its own options, and return what it returns in the shape section 5 gives. Throw an `AdapterError`
   for an outcome that is not a result: `AdapterError.http(404, "...")` for a 404, `transport` when
   there was no response, `new AdapterError("verification", "...")` for a notification that fails
   verification. The runner lists the methods you implement in the hello; the others answer
   `unsupported`, and the rules that need them stay untested, never failed.
4. Be a thin, faithful wrapper. Never retry, fall back or fix anything up for your client: the point
   is to see what your client sends.
5. Keep stdout for the protocol. The runner sends `console.log` to stderr, but anything that writes
   to `process.stdout` itself breaks the adapter.

Writing TypeScript? The `.d.mts` files type the kit. Write `adapter.mts` beside them, and compile it
in place to `adapter.mjs` with `"module": "nodenext"`.

## Check it, then drive it

```sh
java -jar touchstone.jar driver check -- node adapter.mjs
```

The checker says whether your adapter speaks the protocol, and keeps its stderr in
`adapter-check.log`. Then register it in Touchstone's `adapters.yaml`:

```yaml
clients:
  my-client:
    command: [node, adapter.mjs]
    directory: ../my-client/lws-driver
```

and drive it through a client session: `touchstone drive --client my-client`. The results say,
rule by rule, what your client does right and what it gets wrong, with the clause of the
specification behind each.
