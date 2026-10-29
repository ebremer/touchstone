# The C# starter kit for `touchstone-driver/1`

Touchstone can drive your LWS client through a client session, instead of waiting for a person to.
It does so through an **adapter**: a small program in your client's language that runs the
operations Touchstone sends with your client, and reports what happened. This kit is that program
with the protocol already done ([`driver/PROTOCOL.md`](../../PROTOCOL.md)). You only map your
client to an interface.

| File | What it is |
|---|---|
| `ILwsDriverAdapter.cs` | one method per operation, each answering `unsupported` until you implement it |
| `ClientInfo.cs` | the client under test, as the hello names it |
| `AdapterException.cs` | an outcome that is not a result, such as a 404 |
| `AdapterRunner.cs` | the protocol: the hello, one request and one response a line, errors, shutdown |
| `StubAdapter.cs` | an adapter that implements only `ConfigureAsync`, and `Main`. Start here |

Copy everything but `StubAdapter.cs` as it is. It needs .NET 8 or later, and no NuGet package.

## 1. Copy it

Copy this directory into your client's repository, for example as `driver/`. Rename the project
and the namespace if you like, and add your client to `LwsDriverAdapter.csproj`.

## 2. Map your client

In `StubAdapter.cs`, or a class of your own that implements `ILwsDriverAdapter`:

- Name your client in `Client`. The name becomes the subject of the session's report.
- List the authentication kinds your client supports in `Auth`: `none`, `bearer`, `cid`,
  `openid`, `saml` (PROTOCOL.md section 5.1).
- Build your client in `ConfigureAsync`, from `args["auth"]`. The driver calls it first, and may
  call it again: replace the client each time. Answer `unsupported` for a kind you do not list.
- Implement each operation your client has. The runner lists in the hello every method your class
  implements itself, rather than leaving the interface's default.
- Leave the rest. They answer `unsupported`, and the rules that need them stay untested, never
  failed.

Each method takes the operation's `args` and returns its result, as the JSON of PROTOCOL.md
section 5. Its doc comment gives both. The methods are async; a client that is not returns
`Task.FromResult(result)`.

Be thin and faithful. Call your client's own operation with its own options, and report its own
result or error. Never retry, fall back or fix anything up: the point is to see what your client
sends. Map your client's errors to `AdapterException` (section 4.2): `AdapterException.Http(status,
…)` for a response the operation does not succeed with, `Transport` for no response,
`Authentication`, `Protocol`, and so on. An argument your client cannot honour is `unsupported`:
never drop one silently.

The runner answers an `ArgumentException` as `invalidArguments`, and any other exception as
`internal`, with its stack trace on stderr, and keeps running. stdout carries protocol lines only:
the runner sends `Console.Out` to stderr, so log there. It finds your operations by reflection, so
do not trim the adapter (`PublishTrimmed`, `PublishAot`).

## 3. Build and check it

From this directory, with `<touchstone>` the directory of a built Touchstone:

```sh
dotnet build -c Release
java -jar <touchstone>/harness-cli/target/touchstone.jar driver check -- dotnet bin/Release/net8.0/lws-driver-adapter.dll
```

The build also makes a native launcher, `bin/Release/net8.0/lws-driver-adapter` (`.exe` on
Windows), which you can check and register instead of `dotnet …dll`.

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
    command: [dotnet, /path/to/my-client/driver/bin/Release/net8.0/lws-driver-adapter.dll]
    # directory: ../my-client/driver   # working directory, relative to adapters.yaml
    # environment: {DOTNET_CLI_TELEMETRY_OPTOUT: "1"}
```

A relative program is found in `directory`, on Windows too. Then start a
client-session service (the comments in `adapters.yaml` show how) and, from `<touchstone>`:

```sh
java -jar harness-cli/target/touchstone.jar drive --client my-client
```

It writes `results.json`, `earl.ttl` and `junit.xml`, and keeps your adapter's stderr in
`adapter-logs/`. See [`harness-driver/README.md`](../../../harness-driver/README.md) for its options.
