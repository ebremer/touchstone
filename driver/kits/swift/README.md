# Swift starter kit for `touchstone-driver/1`

Touchstone tests an LWS client by driving it through an **adapter**: a small program, written in the
client's language, that runs the operations Touchstone sends and reports what happened. The adapter
speaks [`touchstone-driver/1`](../../PROTOCOL.md) on stdin and stdout. This kit does the protocol, so
your adapter is only your client.

| File | What it is |
|---|---|
| `Sources/LwsDriver/LwsDriverAdapter.swift` | the protocol your adapter conforms to: one method per operation of PROTOCOL.md section 5, each answering `unsupported` until you implement it, and `DriverOperation` |
| `Sources/LwsDriver/AdapterError.swift` | an outcome that is not a result, such as a 404 (section 4.2) |
| `Sources/LwsDriver/ClientInfo.swift` | the client under test, as the hello names it |
| `Sources/LwsDriver/AdapterRunner.swift` | the protocol: hello, framing, dispatch, errors, shutdown. Copy it as it is |
| `Sources/stub/main.swift` | your adapter: only `configure` so far, and the entry point |

JSON is `[String: Any]`, as Foundation's `JSONSerialization` reads and writes it. The package needs a
Swift 6 toolchain (tested with 6.4.1), on Linux or macOS 13 and later. Methods are `async throws`, so
you can await an async client; a synchronous method satisfies them too.

## 1. Copy it

Copy this directory into your client's repository, for example as `lws-driver-adapter/`. Rename the
`stub` target if you like, and the client's name in `StubAdapter`. Add your client library to
`Package.swift`, as a dependency of the `stub` target.

## 2. Implement your client's operations

An adapter is a class: keep your client in a property that `configure` builds. Then implement the
method of each operation your client has, and **add the operation to `operations`**. Swift cannot see
which methods a type implements, so you say it: the runner lists `operations` in the hello, and
answers every other operation `unsupported` without calling it. Call your client's own operation with
its own options, and turn its result into the result shape of PROTOCOL.md section 4.1 and its errors
into an `AdapterError`:

```swift
let operations: Set<DriverOperation> = [.head]

func head(_ args: [String: Any]) async throws -> [String: Any] {
    let url = args["url"] as? String ?? ""
    do {
        let m = try await client.head(url)        // your client's own operation
        var result: [String: Any] = ["url": m.url, "status": m.status]
        if let etag = m.etag { result["etag"] = etag }
        return result
    } catch let e as MyClientHTTPError {
        throw AdapterError.http(e.status, "HEAD \(url) -> \(e.status)")
    }
}
```

- Be thin and faithful. Never retry, fall back or fix anything up: the point is to see what your
  client sends.
- An optional argument your client cannot honour is `unsupported`. Never drop one silently.
- Report what your client exposes, and leave out the rest: an optional result member may be absent
  (PROTOCOL.md section 4.1).
- Log to stderr. The runner points file descriptor 1 at stderr, so a stray `print` cannot break the
  protocol.
- Any error that is not an `AdapterError` answers `internal`, and the adapter keeps running. A trap,
  such as force-unwrapping nil, ends the adapter: throw instead.
- The driver may run a second process of your adapter, to verify notifications. Do not assume yours
  is the only one.

## 3. Build

```sh
swift build -c release --product stub
.build/release/stub
```

No Swift here? Build and run it in `swift:6.4.1-noble`, for example from a Dockerfile that copies this
directory, runs the build, and has `.build/release/stub` as its entry point.

## 4. Check it

`touchstone` below is Touchstone's CLI, `java -jar harness-cli/target/touchstone.jar` in a Touchstone
checkout. The checker starts your adapter and checks that it speaks the protocol (PROTOCOL.md
section 6):

```sh
touchstone driver check -- .build/release/stub
```

It prints one line per check and ends with `34 of 34 checks passed`. Your adapter's stderr goes to
`adapter-check.log` (`--log` to change it). In a container, give `docker run --rm -i <image>` as the
command. A passing check says the adapter speaks the protocol; whether your client is conformant is
what a drive says.

## 5. Register it and drive your client

Touchstone runs only adapters registered in its `adapters.yaml`:

```yaml
clients:
  my-client:
    command: [.build/release/stub]
    directory: ../my-client/lws-driver-adapter   # relative to adapters.yaml
```

Start a client-session service, then drive (see `harness-driver/README.md`):

```sh
touchstone drive --client my-client
```
