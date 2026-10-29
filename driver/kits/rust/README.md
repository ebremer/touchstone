# The Rust starter kit for `touchstone-driver/1`

Touchstone can drive your LWS client through a client session, instead of waiting for a person to.
It does so through an **adapter**: a small program in your client's language that runs the
operations Touchstone sends with your client, and reports what happened. This kit is that program
with the protocol already done ([`driver/PROTOCOL.md`](../../PROTOCOL.md)). You only map your
client to a trait.

| File | What it is |
|---|---|
| `src/adapter.rs` | `LwsDriverAdapter`, one method per operation, each answering `unsupported` until you implement it; and `ClientInfo` |
| `src/error.rs` | `AdapterError`: an outcome that is not a result, such as a 404 |
| `src/runner.rs` | the protocol: the hello, one request and one response a line, errors, panics, shutdown |
| `src/main.rs` | `StubAdapter`, which implements only `configure`. Start here |

Copy the library (`lib.rs`, `adapter.rs`, `error.rs`, `runner.rs`) as it is. Your adapter is
`src/main.rs`. The only dependency is `serde_json`.

## 1. Copy it

Copy this directory into your client's repository, for example as `driver/`, and add your client
as a dependency in `Cargo.toml`. If you rename the package, rename `lws_driver_adapter` in
`src/main.rs` with it.

## 2. Map your client

In `src/main.rs`:

- Name your client in `client()`. The name becomes the subject of the session's report.
- List the authentication kinds your client supports in `auth()`: `none`, `bearer`, `cid`,
  `openid`, `saml` (PROTOCOL.md section 5.1).
- Build your client in `configure`, from `args["auth"]`. The driver calls it first, and may call it
  again: replace the client each time. Answer `unsupported` for a kind you do not list.
- Implement each operation your client has, and name it in `operations()`. The hello lists exactly
  those, plus `configure`, and the runner answers every other one `unsupported`. Rust cannot see
  which methods you implemented, so this list is how the runner knows.
- Leave the rest. They answer `unsupported`, and the rules that need them stay untested, never
  failed.

Each method takes the operation's `args` and returns its result, as the JSON of PROTOCOL.md
section 5. Its doc comment gives both. `move` is `move_resource`, since `move` is a keyword.

Be thin and faithful. Call your client's own operation with its own options, and report its own
result or error. Never retry, fall back or fix anything up: the point is to see what your client
sends. Map your client's errors to `AdapterError` (section 4.2): `AdapterError::http(status, …)` for
a response the operation does not succeed with, `transport` for no response, `authentication`,
`protocol`, and so on. An argument your client cannot honour is `unsupported`: never drop one
silently.

A few rules of the road:

- The methods block. If your client is async, keep a runtime (tokio's `Runtime`, for example) in
  your adapter and `block_on` each call.
- stdout carries protocol lines only. Log with `eprintln!`, never `println!`.
- Keep panics unwinding, the default. The runner reports a panic as `internal` and keeps running.

## 3. Build and check it

From this directory, with `<touchstone>` the directory of a built Touchstone:

```sh
cargo build --release
java -jar <touchstone>/harness-cli/target/touchstone.jar driver check -- target/release/lws-driver-adapter
```

On Windows the binary is `target\release\lws-driver-adapter.exe`.

The check starts the adapter and checks its hello, every operation it lists, that every other one
answers `unsupported`, and its shutdown. It prints one line per check and ends with
`34 of 34 checks passed`. The stub passes as it is. Run it again after each operation you add.
`--log <file>` keeps the adapter's stderr (default `adapter-check.log`), and `--operation-timeout
<seconds>` bounds each operation (default 60).

A passing check says the adapter speaks the protocol. Whether your client is conformant is what a
drive says.

## 4. Register it and drive

Add your adapter to Touchstone's `adapters.yaml`. Touchstone runs only the adapters registered
there:

```yaml
clients:
  my-client:
    command: [/path/to/my-client/driver/target/release/lws-driver-adapter]
    # directory: ../my-client/driver   # working directory, relative to adapters.yaml
    # environment: {RUST_BACKTRACE: "1"}
```

A relative program is found in `directory`, on Windows too. Then start a
client-session service (the comments in `adapters.yaml` show how) and, from `<touchstone>`:

```sh
java -jar harness-cli/target/touchstone.jar drive --client my-client
```

It writes `results.json`, `earl.ttl` and `junit.xml`, and keeps your adapter's stderr in
`adapter-logs/`. See [`harness-driver/README.md`](../../../harness-driver/README.md) for its options.
