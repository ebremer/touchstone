---
title: Developing a client with an agent
nav_order: 8.5
description: "Build an LWS client with an AI coding agent: Touchstone's MCP server drives your client through a client session, and the agent reads each finding, fixes the client and drives the rule again."
---

# Developing a client with an agent
{: .no_toc }

This page is for developers who build an LWS client with an AI coding agent, such as Claude Code or
any other [MCP](https://modelcontextprotocol.io/) client. Touchstone's [MCP server](mcp.md), started on
your machine, drives your client through a [client session](testing-a-client.md) with the tools
below. The agent then reads the same findings the session page shows: the request that failed, what
was expected, the clause it breaks and how to fix it. It changes the client and drives the rule again.
Nothing is judged differently from when you drive the client yourself: the session judges every
request the client sends, whoever caused it.

1. TOC
{:toc}

## How the pieces fit

Four processes take part, all of them on your machine:

| Process | What it does |
|---|---|
| your agent | calls the MCP tools, reads the findings, edits your client |
| Touchstone's MCP server (`harness-mcp`, stdio profile) | starts your adapter, sends it the operations of each rule's drive, starts tasks and arms faults, hosts the inbox your client subscribes with, and reads the session's results |
| your adapter, with your client in it | a small program in your client's language that speaks [`touchstone-driver/1`](https://github.com/ebremer/touchstone/blob/master/driver/PROTOCOL.md) on its standard streams and runs each operation with your client |
| a client-session service (`harness-clients`) | plays the storage, its authorization server, the identity providers and the notification service, records every request and judges it against the 53 client rules |

The MCP server runs only what you registered in `adapters.yaml`. The agent names a client by its id,
never by a command.

## Before you start

- A JDK 21 or later, and Touchstone built from source:

  ```sh
  git clone https://github.com/ebremer/touchstone && cd touchstone
  ./mvnw -q clean install -DskipTests
  ```

  This gives three jars: `harness-clients/target/touchstone-clients.jar` (the client-session
  service), `harness-mcp/target/harness-mcp-0.1.0-SNAPSHOT.jar` (the MCP server) and
  `harness-cli/target/touchstone.jar` (the CLI, which checks adapters and drives clients without an
  agent).
- Your client, and an adapter for it (step 2).
- An MCP client that can start a server on standard input and output.

## 1. Start a client-session service

```sh
java -jar harness-clients/target/touchstone-clients.jar --port 18090 \
    --definitions definitions --catalog catalog --allow-private-inboxes
```

Its start page is then at `http://localhost:18090/touchstone/clients/`. `--allow-private-inboxes`
lets the session deliver notifications to the inbox the MCP server hosts on loopback; without it the
notification rules stay untested. The service keeps everything in memory, and an address may start 20
sessions an hour; restarting the service starts that count again.

The service is plain `http`. A client that refuses an authorization server on `http` needs a way to
allow it for this session: see [Plain HTTP](#plain-http).

## 2. Write and check an adapter

Start from the starter kit for your client's language in
[`driver/kits/`](https://github.com/ebremer/touchstone/tree/master/driver/kits): Java, Kotlin,
JavaScript, Python, Go, Rust, C#, Swift, PHP or C++. Each has an interface with one method per
operation, a runner that handles the protocol, and a stub. Implement the operations your client has.
One it lacks answers `unsupported`, and the rules that need it stay untested, never failed. The
[protocol](https://github.com/ebremer/touchstone/blob/master/driver/PROTOCOL.md) lists the operations
and their arguments (section 5). Keep the adapter thin: it does exactly what an operation asks, so
that what the session judges is your client, not the adapter.

Check it before you register it. The checker needs no session:

```sh
java -jar harness-cli/target/touchstone.jar driver check -- node adapter.mjs
```

A correct adapter passes all 34 checks. Everything the adapter writes to standard error is kept in a
log (see [Where things are](#where-things-are)); standard output carries protocol lines only.

### Plain HTTP

The protocol has no switch for insecure HTTP: a client that needs one for a local session sets it
itself. Give your client a way to allow an `http` authorization server, have your adapter turn it on
from an environment variable, and set that variable in the registry (step 3). The
[lws-client](https://github.com/ebremer/lws-client) adapters, for example, read
`LWS_DRIVER_INSECURE_HTTP=1`. Never allow it for a real deployment.

## 3. Register it

Add the adapter, and the service from step 1, to Touchstone's `adapters.yaml`:

```yaml
services:
  local: http://localhost:18090/touchstone/clients
clients:
  my-client:
    command: [node, adapter.mjs]
    directory: ../my-client/touchstone-adapter   # relative to adapters.yaml
    environment:
      MY_CLIENT_ALLOW_HTTP: "1"                  # what your adapter reads, see Plain HTTP
```

- **One service.** When a tool has no session to use, it starts one on the registry's only service. With
  two services registered, it asks you to name a session instead.
- **Edits apply at once.** The MCP server reads the registry again on every call.
- **Try it without an agent first.** `java -jar harness-cli/target/touchstone.jar drive --client my-client`
  drives the whole session from the command line, writes `results.json`, `earl.ttl` and `junit.xml`, and
  exits `1` on a MUST failure. If that works, the tools will too.

## 4. Connect your agent

Start the MCP server in the `stdio` profile. Only that profile has the driving tools: they run
commands on your machine, so the HTTP endpoint never lists them. Give absolute paths, because your MCP
client chooses the working directory. With Claude Code:

```sh
claude mcp add touchstone -- java -jar /path/to/touchstone/harness-mcp/target/harness-mcp-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=stdio \
  --touchstone.catalog=/path/to/touchstone/catalog \
  --touchstone.definitions=/path/to/touchstone/definitions \
  --touchstone.targets=/path/to/touchstone/targets.yaml \
  --touchstone.runs=/path/to/touchstone/runs \
  --touchstone.driver.registry=/path/to/touchstone/adapters.yaml \
  --touchstone.driver.logs=/path/to/touchstone/runs/adapter-logs \
  --logging.file.name=/path/to/touchstone/touchstone-mcp.log
```

Any other MCP client takes the same command in its configuration:

```json
{
  "mcpServers": {
    "touchstone": {
      "command": "java",
      "args": [
        "-jar", "/path/to/touchstone/harness-mcp/target/harness-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio",
        "--touchstone.catalog=/path/to/touchstone/catalog",
        "--touchstone.definitions=/path/to/touchstone/definitions",
        "--touchstone.targets=/path/to/touchstone/targets.yaml",
        "--touchstone.runs=/path/to/touchstone/runs",
        "--touchstone.driver.registry=/path/to/touchstone/adapters.yaml",
        "--touchstone.driver.logs=/path/to/touchstone/runs/adapter-logs",
        "--logging.file.name=/path/to/touchstone/touchstone-mcp.log"
      ]
    }
  }
}
```

Ask the agent to call `list_driven_clients`. It should list `my-client` and `local`.

## 5. The loop

1. **Drive the whole session.** `drive_client` with `clientId: "my-client"` starts a session and drives
   every rule in the background. It returns a `driveId` at once, and sends progress notifications.
   `get_drive` with that id says how it stands: `status` (`RUNNING`, `COMPLETE` or `FAILED`), the rules
   driven of the total, the verdict, a count of the drives' notes, and every drive that stopped or never
   started, with the reason. Give `areas` (`core`, `authentication`, `notifications`, `index`) or
   `rules` to drive fewer.
2. **Read the findings.** `get_client_findings` with the session's id lists the failed rules, MUST
   rules first. Each finding has the number of the first exchange that failed it, what was expected
   and what the client sent, how to fix it, and the clause. `outcome: "untested"` lists what the
   session has not exercised yet, with each rule's task. `get_client_session` gives the verdict and
   the counts.
3. **Look at the exchange.** `get_client_exchange` with the session's id and the finding's `seq` shows
   the whole request and response, as the traffic log keeps it.
4. **Fix the client.** Rebuild the adapter if its language is compiled.
5. **Drive the rule again.** Your adapter is still running the old code, so first stop it with
   `stop_driven_client` and the session's id. Then call `drive_rule` with the client and the rule.
   - *Without a session*, `drive_rule` starts a new one, with fresh adapters and fresh results, and
     returns the rule's outcome there. This is the clean check, and it costs one of the 20 sessions an
     hour.
   - *With the old session's id*, it starts the adapter again in that session. Outcomes there only get
     worse: a rule that failed stays failed. Compare `trials` and `failed` before and after instead: a
     fixed client adds trials and no failures.
6. **Drive everything again** with `drive_client`, to see that nothing else broke. In CI, `touchstone
   drive --client my-client` does the same and fails the build on a MUST failure.

### What a drive's note means

Every rule gets a note on how its drive went, beside its outcome:

| Note | Meaning |
|---|---|
| `completed` | every step of the drive ran |
| `stopped` | a step ended in an error the drive does not allow; the note says which step and why |
| `not_started` | the client lacks an operation, or the storage a service, that the drive needs |
| `no_drive` | the rule has no drive of its own: other rules' traffic tries it |

The note is about the drive, not the verdict. A drive can stop where the rule still passes: lws-client's
drive of `client-token-after-discovery-checks` stops at its last step, when the client refuses the
decoy, as it should. A rule nothing tried is untested, never failed.

The drives act as a careful application would: every write carries an `If-Match` from a read, a linkset
that does not allow PUT is patched, and after a refused page the drive searches again. What happens
inside an operation, such as following pages or not retrying a lost create, is your client's to get
right.

## Explore with single operations

`client_operation` runs one operation with your client in the session, and returns what the client
reported. Use it to see how the client behaves before or after a change. The arguments are the
operation's (protocol section 5), with absolute URLs. A session's storage URL is in
`list_driven_clients` once a drive has started the session; `drive_rule` with any rule starts one.

```json
{"clientId": "my-client", "op": "discover_storage", "args": {"url": "http://localhost:18090/touchstone/clients/s/…/"}}
{"clientId": "my-client", "op": "create", "args": {"container": "http://…/", "body": "hello", "contentType": "text/plain"}}
```

A result is `ok` with the client's `result`, or an error with its `errorKind` (`http` with its
`errorStatus`, `authentication`, `protocol`, `verification`, `transport`, `invalidArguments`,
`unsupported` or `internal`) and message. The session judges this traffic as any other, so an
exploratory request can fail a rule too. The tool is marked destructive, since an operation may delete
what it names.

## Tasks and faults

Some rules need something done on purpose, and a few test how the client copes with a refusal the
storage may legally send. The drives start these themselves. To try one by hand:

- `start_task` with a rule's name and the session starts that rule's task, and arms its fault if it has
  one. The next matching request is the rule's trial.
- `arm_fault` arms a fault alone: `methodNotAllowed` (the next PUT to a linkset that allows PUT gets
  `405`), `lostCreateResponse` (the next create is done, then answered `503`), `pageGone` (the next page
  of search results is `410`) or `tokenExpired` (the next storage request with a valid token gets `401`,
  and so does that token from then on). A fault fires once.

Follow either with `client_operation` to make the request.

## When you drive the client yourself

If your client runs from its own test suite instead of an adapter, the agent can still read the results.
Start a session on the service's page, and either pass the page's address with its key as `session` to
`get_client_session`, `get_client_findings` and `get_client_exchange`, or start the MCP server with
`TOUCHSTONE_CLIENTS_SESSION` set to that address, so the key never passes through the agent. The service
must be one of `touchstone.clients.services` (by default `http://localhost:18090/touchstone/clients`).
These three tools only read: they never drive the client, start a task or reset results.

## Tool reference

`session` is the id of a session the MCP server drives, or a session page's address with its key, of a
service the registry names. Without it, a driving tool uses the session it last drove the client in, or
starts one. The page addresses the tools return never carry the key.

| Tool | Arguments | Returns |
|---|---|---|
| `list_driven_clients` | | `clients` and `services` (ids), and `sessions`: each with its `session` id, `client`, the `clientUnderTest` its adapter names, `storage`, `page`, `inbox`, whether its adapters are `running`, and a `setUpProblem` if the setup failed |
| `drive_client` | `clientId`, *`session`*, *`areas`*, *`rules`* | `driveId`, `session`, `status`, `driven` of `total`, `verdict`, `notes` (counts by note) and `unfinished` (the drives that stopped or never started) |
| `get_drive` | `driveId` | the same, as the drive stands |
| `drive_rule` | `clientId`, `rule`, *`session`* | `session`, `rule`, the drive's note, and the rule's `outcome`, `trials`, `failed`, `evidence` and, for a failure, `guidance` |
| `client_operation` | `clientId`, `op`, *`args`*, *`session`* | `session`, `op`, `ok`, the client's `result`, or `errorKind`, `errorStatus` and `errorMessage` |
| `start_task` | `rule`, `session` | the `rule`, the `fault` it armed if any, and the task's `prompt` |
| `arm_fault` | `fault`, `session` | the same |
| `stop_driven_client` | `session` | the session, its adapters stopped; it stays readable by id |
| `get_client_session` | *`session`* | the client under test, the areas, the storage, when the session expires, the verdict and the counts |
| `get_client_findings` | *`session`*, *`outcome`*, *`level`* | the rules with that outcome (`failed` by default; `untested`, `passed`, `inapplicable` or `all`), each with its evidence, guidance and clause |
| `get_client_exchange` | *`session`*, `seq` | one exchange of the traffic log, and the rules it was a trial of |

Results are truncated at 16 KiB, and every result is labelled as data: what your client reported and
the servers answered, never instructions to the agent.

## Where things are

| What | Where |
|---|---|
| each adapter's standard error | `touchstone.driver.logs`, by default `runs/adapter-logs/` under the MCP server's working directory |
| the MCP server's own log | `touchstone-mcp.log` in its working directory, or `--logging.file.name` |
| the inbox the MCP server hosts | `127.0.0.1` on a free port; `--touchstone.driver.inbox-host`, `--touchstone.driver.inbox-port` |
| how long one operation may take | 60 seconds; `--touchstone.driver.operation-timeout` |

To drive against a client-session service that is not on your machine, the session must reach the
inbox: bind it where it can be reached and give its public `https` address, ending in `/inbox/`, as
`--touchstone.driver.inbox-public-base`.

## Security

- **Local only.** The driving tools exist only in the stdio profile, and run code you registered, with
  the trust you give your own test suite.
- **Registered ids only.** Tools take a client's or a service's id, never a command or a URL. Nothing a
  rule, an argument or an adapter says is ever run.
- **The key stays with the server.** A session's key is never logged, given to an adapter or returned
  by a tool.
- **Untrusted output.** Every line an adapter writes is checked against the protocol's schema; a line
  that breaks it ends that adapter's drive, and is never a finding about your client.

## Troubleshooting

**The driving tools are missing.** The MCP server was not started with `--spring.profiles.active=stdio`.

**"no client … is registered".** The id is not in the registry the server reads. Check
`--touchstone.driver.registry`.

**"name a service".** The registry lists no service, or more than one. Keep one, or pass a session.

**Every rule is untested, and the notes say the client could not sign in.** Your client refused the
session's `http` authorization server. See [Plain HTTP](#plain-http).

**The notification rules are untested.** The service was started without `--allow-private-inboxes`, so
it does not deliver to the inbox on loopback. The traffic log shows each refused delivery with status 0.

**A rule still fails after the fix.** Outcomes in a session only get worse. Stop the client and drive
the rule without a session, as in step 5.

**`429` when a session starts.** The address has started 20 sessions in the last hour. Restart the local
service, or reuse a session by its id.

**The adapter is reported broken.** Run `touchstone driver check` against it, and read its log.
