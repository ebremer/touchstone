# harness-driver

Touchstone's client driver (CLIENT-TESTING.md section 14). It drives an LWS client through a
client session, so that nobody has to sit at the session page:

1. it starts the client's **adapter**, a program the client's developers write, which speaks
   [`touchstone-driver/1`](../driver/PROTOCOL.md) on its standard streams;
2. it starts a session marked driven, sets the client up as alice, and has it find the storage;
3. it runs each client rule's `drive` (definitions/OBSERVATION.md section 12), in a container of
   its own, starting the rule's task and so arming its fault where the drive says;
4. it reads the session's results. The session judges the traffic exactly as when a person drives;
   the driver adds only a note beside each rule saying how its drive went.

Driving is a second way in. A client without an adapter is tested by hand, or from its own test
suite, as before.

## Running it

Register the adapter and the client-session service in [`adapters.yaml`](../adapters.yaml), then:

```sh
touchstone drive --client my-client [--service local] [--areas core,index] [--rule <name>]... [--out <dir>]
```

It writes `results.json` (the session's results, each rule with its drive's note), `earl.ttl` and
`junit.xml`, keeps each adapter's stderr in `adapter-logs/`, and exits `0` when no MUST rule failed,
`1` when one did, and `2` when there is no verdict. `--show-page` prints the session page with its
key.

The webhook rules need the session to reach the inbox the driver hosts. Against a local
client-session service started with `--allow-private-inboxes` that works as it is. A public service
needs the inbox at a public https URL: bind it with `--inbox-host`/`--inbox-port` and give that URL as
`--inbox-public-base`.

A local client-session service is plain http. The protocol leaves allowing that to the client
(PROTOCOL.md section 7): lws-client's adapters allow it when their environment sets
`LWS_DRIVER_INSECURE_HTTP=1`, which a client's `environment:` in `adapters.yaml` gives.

`touchstone driver check -- <command>` (`AdapterChecker`) checks that an adapter speaks the protocol,
with no session (PROTOCOL.md section 6); a correct adapter passes 34 of 34 checks. harness-mcp's
driving tools, in its stdio profile only, drive through this module too (`DriveSession`, D-0106).

## What it guarantees

- **Only registered adapters run** (section 14.3): the CLI and the tools take an id from the
  registry, never a command, and nothing a rule, an argument or an adapter says is ever run.
- **An adapter's output is untrusted.** Every line is checked against
  `driver/protocol.schema.json`; a line over 16 MiB, a line that is not JSON, a response to another
  request, a result that breaks the schema or silence past the timeout makes the adapter broken,
  which ends its drive and is never a finding about the client.
- **The session key stays in the driver.** It is never logged, put in a drive's variables or given
  to an adapter.

## Proving it

`DriverSelfTest` drives the reference client behind the reference adapter (harness-fixtures'
`RefAdapter`): it passes every rule and every drive runs to its end; and each of its 54 broken
twins, behind the same adapter, fails exactly the rules aimed at it (`fixtures.client.Twins`).
