# Go starter kit for touchstone-driver/1

This kit makes your Go LWS client drivable by Touchstone. Touchstone's driver starts your adapter,
sends it operations such as "create this resource in that container" on stdin, and judges the
requests your client sends to a client session. The protocol is
[`driver/PROTOCOL.md`](../../PROTOCOL.md); this kit handles all of it, so you only map your client to
an interface.

| File | What it is |
|---|---|
| `adapter.go` | the `Adapter` interface: one method per operation of the protocol, and your client's name |
| `unsupported.go` | `Unsupported`, to embed: every operation but `configure` answers `unsupported` |
| `error.go` | `Error`, an outcome that is not a result, such as a 404 |
| `runner.go` | `Serve`, the protocol: the hello, one request a line, one response a line. Copy it as it is |
| `cmd/stub/main.go` | the starting point: implements only `configure` |

It uses the standard library only, and needs Go 1.22 or later.

## Make it yours

1. Copy this directory into your client's repository. Rename the module in `go.mod` (and the import
   in `cmd/stub/main.go`), and `go get` your client. Or move the files into your own module.
2. In `cmd/stub/main.go` (rename the type too), give your client's name, and build your client in
   `Configure` from the authentication it is given (section 5.1: `none`, `bearer`, `cid`, `openid`,
   `saml`). List the kinds your client supports in `Auth`.
3. Implement the operations your client has, one method each, on the type that embeds
   `lwsdriver.Unsupported`. Arguments and results are `map[string]any`, as `encoding/json` decodes
   them. Call your client's own operation with its own options, and return what it returns in the
   shape section 5 gives. Return an `*lwsdriver.Error` for an outcome that is not a result:
   `lwsdriver.HTTPError(404, "...")` for a 404, `TransportError` when there was no response. Any
   other error, or a panic, answers `internal`, and the adapter keeps running.
4. Name each operation you implement in `Operations`, such as `"read"`. Go cannot see which methods
   you wrote yourself and which come from `Unsupported`, so `Operations` is what the hello lists, and
   only those operations reach their methods. The others answer `unsupported`, and the rules that
   need them stay untested, never failed.
5. Be a thin, faithful wrapper. Never retry, fall back or fix anything up for your client: the point
   is to see what your client sends. Log to stderr; the stub's `main` sends whatever else is printed
   to stdout there too.

## Check it, then drive it

```sh
go build -o lws-driver-adapter ./cmd/stub
java -jar touchstone.jar driver check -- ./lws-driver-adapter
```

On Windows, build `lws-driver-adapter.exe`. The checker says whether your adapter speaks the
protocol. Then register it in Touchstone's `adapters.yaml`:

```yaml
clients:
  my-client:
    command: [./lws-driver-adapter]
    directory: ../my-client
```

A relative program is found in `directory`, on Windows too. Then drive it through a client session:
`touchstone drive --client my-client`. The results say, rule by rule, what your client does right
and what it gets wrong, with the clause of the specification behind each.
