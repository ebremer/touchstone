---
title: Command line
nav_order: 5
description: "Reference for the touchstone command: run, coverage, diff, drive and driver check, their options, output and exit codes."
---

# Command line
{: .no_toc }

1. TOC
{:toc}

## Running the CLI

The build produces one runnable jar:

```sh
java -jar harness-cli/target/touchstone.jar <command> [options]
```

The [Docker image](distribution.md) has the same jar as its entry point, so
`docker run --rm touchstone-harness <command> [options]` takes the same arguments.

The global options are `-h`/`--help` and `-V`/`--version`. Every command also accepts
`--help`.

Relative paths, including every default below, resolve against the working directory.
Run the CLI from the repository root, or pass explicit paths.

Logging goes to standard error at level `WARN` and above. Standard output carries only
results, so the output can be piped.

## `touchstone run`

Runs test definitions against a registered target and writes a report bundle.

```text
touchstone run -t <targetId> [-m <selector>] [--targets <file>] [--definitions <dir>]
               [-c <catalogDir>] [--report-dir <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `-t`, `--target` | (required) | Target id from the registry. Never a URL. |
| `-m`, `--module` | `all` | What to run: `all`; a module, `core`, `auth`, `index` or `notifications`; a manifest, such as `core/containers` or `auth/oidc`; or one test, by id (`core/containers#getContainer`) or by name. |
| `--targets` | `targets.yaml` | The target registry file. |
| `--definitions` | `definitions` | The definitions directory, holding `lws10/` and `schema/`. |
| `-c`, `--catalog` | `catalog` | The requirements catalog directory. |
| `--report-dir` | `runs` | Where the report bundle is written, as `<report-dir>/<stamp>-<runId>/`. |

Before sending any request, `run` checks that:

- the registry file exists, parses, and holds the target id;
- the definitions are in the format this engine implements (0.16.0);
- every definition parses as YAML 1.2, validates against the schema, expands as JSON-LD in
  safe mode, and passes the lint, including that every requirement IRI it cites is in the
  catalog;
- the selection matches at least one test.

Then it creates the run root on the target and runs the tests in parallel. It prints one
line per test, with its outcome and level, then a summary, the verdict and the report
location. A test that did not pass is followed by an indented description: why, or the
step that stopped it, each failed expectation's expected and actual values, and the HTTP
exchange.

Against the open reference server, the definitions that need authentication are
inapplicable, and everything else passes (abridged):

```text
$ touchstone run --target ref
[passed      ] MUST   core/discovery#discovery-get-links-storageDescription (40 ms)
[passed      ] MUST   core/discovery#discovery-link-storage-on-data-resource (44 ms)
...
[inapplicable] MUST   core/storage_authorization#getContainer-private-unauthorized (0 ms)
    core/storage_authorization#getContainer-private-unauthorized - inapplicable [MUST]
      inapplicable: the target does not declare Authentication
...
78 passed, 0 failed, 0 cantTell, 143 inapplicable  (target ref, run 71e346ce)
conformant: no MUST test failed or ended cantTell
reports: runs/2026-10-29T224506Z-71e346ce (run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml)
```

Against the broken twin, a server that claims to protect its resources but never refuses
anything, the access-control tests fail (abridged):

```text
$ touchstone run --target broken --targets targets-broken.yaml --module core/storage_authorization
[failed      ] MUST   core/storage_authorization#getContainer-private-unauthorized (39 ms)
    core/storage_authorization#getContainer-private-unauthorized - failed [MUST] (39 ms)
      step 'An anonymous request for a protected container is refused with 401 and a conforming challenge':
        FAILED status code
          expected: 401
          actual:   200
        exchange: GET http://localhost:4712/touchstone-run-bdeb9fbe/getcontainer-private-unauthorized/ -> 200
        response body: {"@context":"https://www.w3.org/ns/lws/v1","id":"http://localhost:4712/...","type":"Container","totalItems":0,"items":[]}
...
[passed      ] MUST   core/storage_authorization#getContainer-authenticated-owner (39 ms)
[failed      ] MUST   core/storage_authorization#createDataResource-unauthorized (37 ms)
...
1 passed, 10 failed, 0 cantTell, 8 inapplicable  (target broken, run bdeb9fbe)
NOT conformant: 10 MUST test(s) failed or ended cantTell
```

Its registry declares `Authentication` and gives alice and bob placeholder tokens, which
that server never checks. The eight inapplicable tests need a real JWT to derive a fault
from, or the authorization server's key.

## `touchstone coverage`

Prints how many catalogued requirements at least one test cites, for each spec module and
level. It counts the requirements a server run answers for, those that bind a server or an
authorization server, and says how many others it left out ([roles](catalog.md#who-a-requirement-binds)).

```text
touchstone coverage [-c <catalogDir>] [--definitions <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `-c`, `--catalog` | `catalog` | The requirements catalog directory. |
| `--definitions` | `definitions` | The definitions directory. Every test counts. |

```text
$ touchstone coverage
Requirements coverage: 243 of 272 covered by 221 test(s)

module                   level    covered/total
lws10-authn-openid       MUST     2/2
lws10-authn-saml         MUST     1/1
lws10-authn-ssi-cid      MUST     5/5
lws10-core               MUST     148/155
lws10-core               SHOULD   15/20
lws10-core               MAY      8/19
lws10-index              MUST     37/39
lws10-index              SHOULD   4/4
lws10-index              MAY      6/10
lws10-notifications-webhook MUST     12/12
lws10-notifications-webhook SHOULD   1/1
lws10-notifications-webhook MAY      4/4

49 more requirement(s) bind only clients, receivers, identity providers or other specifications, which a server run cannot break, and are not counted.
```

`coverage` is a report, not a gate. If a definition cites a requirement that is not in the
catalog, it prints a warning and still exits `0`.

## `touchstone diff`

Compares two runs.

```text
touchstone diff <before> <after>
```

Each argument is a run directory or the `run.json` inside one. The output has these
sections:

- **regressions:** tests that passed before and now fail or end cantTell;
- **fixes:** tests that failed or ended cantTell before and now pass;
- **other outcome changes:** for example, passed before and inapplicable now;
- **added tests** and **removed tests:** tests present in only one of the two runs;
- the count of tests whose outcome did not change.

Comparing the secured reference deployment with the broken twin, on the same manifest
(abridged):

```text
$ touchstone diff runs/2026-10-29T224834Z-64fec08d runs/2026-10-29T224839Z-bdeb9fbe
diff 64fec08d (2026-10-29T22:48:34.041465913Z) -> bdeb9fbe (2026-10-29T22:48:39.449070875Z)
regressions:
  core/storage_authorization#getContainer-private-unauthorized: passed -> failed
  core/storage_authorization#protected-resource-metadata: passed -> failed
  ...
no fixes
other outcome changes:
  core/storage_authorization#authz-expired-token-rejected: passed -> inapplicable
  ...
1 unchanged
```

`diff` exits with `1` when there is at least one regression, so a CI job can gate on it,
and with `2` when it cannot read one of the runs.

## `touchstone drive`

Drives a client through a client session, through the adapter its developers wrote
([Let Touchstone drive your client](testing-a-client.md#let-touchstone-drive-your-client)).

```text
touchstone drive --client <id> [--service <id>] [--areas core,index] [--rule <name>]... [--out <dir>]
                 [--registry <file>] [--inbox-host <host>] [--inbox-port <port>]
                 [--inbox-public-base <url>] [--operation-timeout <s>] [--show-page]
                 [--catalog <dir>] [--definitions <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `--client` | (required) | The client's id in the adapter registry. |
| `--service` | the registry's only service | The client-session service's id in the registry. |
| `--areas` | all | The areas in scope: `core`, `authentication`, `notifications`, `index`. |
| `--rule` | every rule | Drive only this rule; repeatable. |
| `--out` | `runs/clients/<time>-<client>` | Where the results are written. |
| `--registry` | `adapters.yaml` | The adapter registry file. |
| `--inbox-host`, `--inbox-port` | `127.0.0.1`, a free port | Where the driver's inbox listens. |
| `--inbox-public-base` | (none) | The inbox as the session reaches it, ending in `/inbox/`; needed for a public service. |
| `--operation-timeout` | `60` | Seconds one operation may take. |
| `--show-page` | off | Print the session page with its key, which opens it. |
| `--catalog`, `--definitions` | `catalog`, `definitions` | As for `run`. |

The client and the service are ids in the adapter registry, `adapters.yaml` (`--registry`),
never a command or a URL. It writes `results.json` (the session's results, each rule with a note
on how its drive went), `earl.ttl` and `junit.xml`, and keeps the adapter's stderr in
`adapter-logs/`. It exits with `0` when no MUST rule failed, `1` when one failed or could not be
decided, and `2` when there is no verdict.

## `touchstone driver check`

Checks that an adapter speaks `touchstone-driver/1`, without a session.

```text
touchstone driver check [--operation-timeout <s>] [--log <file>] -- <the adapter's command line>
```

It prints one line per check: the hello, `configure`, an answer to every operation the adapter
lists, `unsupported` for the rest, `invalidArguments` for a line that is not a JSON object, and a
clean exit after `shutdown`. The adapter's stderr goes to `--log` (default `adapter-check.log`),
and `--operation-timeout` (default `60`) is in seconds. It exits with `0` when every check passed,
`1` when one failed, and `2` when the adapter could not be started.

## Exit codes

Exit code `1` is always a verdict about what is under test: a server for `run`, a client for
`drive`, an adapter for `driver check`. Exit code `2` always means there is no verdict. A CI job can therefore tell "the server does not conform" apart from
"the job is broken" by the exit code alone. Each command's `--help` lists its codes.

| Code | `run` | `coverage` | `diff` | `drive` | `driver check` |
|---|---|---|---|---|---|
| `0` | Conformant: no MUST test failed or ended cantTell. SHOULD and MAY failures are advisory. | The matrix was printed. | No regressions. | No MUST rule failed. | Every check passed. |
| `1` | Not conformant: a MUST test failed or ended cantTell. | | At least one regression. | A MUST rule failed, or could not be decided. | A check failed. |
| `2` | No verdict. Either the harness is misconfigured, or the run could not start on the target; see below. | The catalog or the definitions could not be read. | A run could not be read. | No verdict. | The adapter could not be started. |

For `run`, the harness is misconfigured when:
- the registry is missing or does not parse, or the target id is unknown;
- the definitions are invalid, are in another format version, or cite a requirement the
  catalog does not hold;
- the selection matches no test.

The run could not start on the target when the server is unreachable, or when it refused
to create the run root.

In every case that exits with `2`, the reason is printed on standard error and no report
bundle is written:

```text
$ touchstone run --target unconfigured --targets targets-unconfigured.yaml
cannot run against target 'unconfigured': cannot create the run root: POST http://localhost:50094/ as alice answered 401 without a Location, not 201

$ touchstone run --target ref --module core/nothing
no test matches 'core/nothing'; try all, a module (core, auth, index, notifications), a manifest (core/discovery, core/containers, core/data_resources, core/conditional_requests, core/linksets, core/moving, core/storage_authorization, core/authorization_server, core/access_grants, core/notifications, core/pagination, auth/oidc, auth/cid, auth/saml, notifications/webhook, index) or a test id
```

An exception that nothing anticipated also exits with `2`, with a stack trace. Such an
exception is a harness bug, not a verdict about the server.

The exit code of `run` follows the verdict in the reports: only a MUST test that failed or
ended cantTell makes it `1`. A run whose only failures are SHOULD or MAY tests exits `0`,
and says how many there were. [Reports and verdicts](reports.md#the-verdict) explains the
verdict.
