---
title: Command line
nav_order: 5
description: "Reference for the touchstone command: run, coverage and diff, their options, output and exit codes."
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

Runs one module's tests against a registered target and writes a report bundle.

```text
touchstone run -t <targetId> [-m <module>] [--targets <file>] [--manifests <dir>]
               [-c <catalogDir>] [--report-dir <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `-t`, `--target` | (required) | Target id from the registry. Never a URL. |
| `-m`, `--module` | `core` | The module to run: a subdirectory of the manifests directory, such as `core` or `auth-oidc`. |
| `--targets` | `targets.yaml` | The target registry file. |
| `--manifests` | `manifests` | The manifests directory. |
| `-c`, `--catalog` | `catalog` | The requirements catalog directory. |
| `--report-dir` | `runs` | Where the report bundle is written, as `<report-dir>/<stamp>-<runId>/`. |

Before sending any request, `run` checks that:

- the registry file exists and holds the target id;
- `<manifests>/<module>` exists and contains at least one manifest;
- every manifest validates against the manifest schema;
- every requirement IRI the manifests declare exists in the catalog.

Then it provisions the run and executes the tests in parallel. It prints one line per
test, then a summary and the report location. A test that did not pass is followed by an
indented description: the step that stopped it, each failed assertion's expected and
actual values, and the HTTP exchange.

The output below is abridged from a real run. The core suite ran against the secured
reference server with no `defaultIdentity` configured, so every request went out
anonymously and was refused:

```text
[FAILED] core/create-in-missing-container-404 (32 ms)
    core/create-in-missing-container-404 - FAILED (32 ms)
      step 1 'POST into a container that does not exist':
        FAILED status
          expected: [404]
          actual:   401
        exchange: POST http://localhost:49298/touchstone-run-58211573/t7-create-in-missing-container-404/does-not-exist/ -> 401
[ERROR ] core/put-replace-with-if-match (27 ms)
    core/put-replace-with-if-match - ERROR (27 ms)
      step 1 'create version one':
        error: bind 'created': response has no header Location
        FAILED status
          expected: [201]
          actual:   401
        exchange: POST http://localhost:49298/touchstone-run-58211573/t22-put-replace-with-if-match/ -> 401
...
0 passed, 3 failed, 21 errors, 0 skipped  (target secured-ref, run 58211573)
reports: runs/2026-09-23T162540Z-58211573 (run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml)
```

The second test is an `ERROR` rather than a `FAILED`. Its step could not bind the
`Location` header that later steps need, so the harness could not finish the test.

## `touchstone coverage`

Prints how many catalogued requirements have at least one test, for each spec module and
level.

```text
touchstone coverage [-c <catalogDir>] [--manifests <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `-c`, `--catalog` | `catalog` | The requirements catalog directory. |
| `--manifests` | `manifests` | The manifests directory. Every module in it counts. |

`coverage` is a report, not a gate. If a manifest names a requirement that is not in the
catalog, it prints a warning and still exits `0`.

## `touchstone diff`

Compares two runs.

```text
touchstone diff <before> <after>
```

Each argument is a run directory or the `run.json` inside one. The output has these
sections:

- **regressions:** tests that passed before and now fail or error;
- **fixes:** tests that failed or errored before and now pass;
- **other outcome changes:** for example, passed before and skipped now;
- **added tests** and **removed tests:** tests present in only one of the two runs;
- the count of tests whose outcome did not change.

The output below comes from comparing the run above with a rerun that set
`defaultIdentity`:

```text
diff 58211573 (2026-09-23T16:25:40.165666900Z) -> b9868d06 (2026-09-23T16:26:15.952515600Z)
no regressions
fixes:
  core/conditional-get-304: ERROR -> PASSED
  core/conditional-get-stale-validator-200: ERROR -> PASSED
  ...
  core/storage-description-discovery: ERROR -> PASSED
no other outcome changes
0 unchanged
```

`diff` exits with `1` when there is at least one regression, so a CI job can gate on it,
and with `2` when it cannot read one of the runs.

## Exit codes

Exit code `1` is always a verdict about the server under test. Exit code `2` always means
there is no verdict. A CI job can therefore tell "the server does not conform" apart from
"the job is broken" by the exit code alone. Each command's `--help` lists its codes.

| Code | `run` | `coverage` | `diff` |
|---|---|---|---|
| `0` | Every test passed or was skipped. | The matrix was printed. | No regressions. |
| `1` | At least one test failed or errored. | | At least one regression. |
| `2` | No verdict. Either the harness is misconfigured, or the run could not start on the target; see below. | The catalog or the manifests could not be read. | A run could not be read. |

For `run`, the harness is misconfigured when:
- the registry is missing, or the target id is unknown;
- the module has no manifests;
- a manifest fails schema validation;
- a manifest cites a requirement the catalog does not hold.

The run could not start on the target when the server is unreachable, or when it refused
to create the run root.

In every case that exits with `2`, the reason is printed on standard error and no report
bundle is written:

```text
$ touchstone run --target ref --module core
cannot run against target 'ref': cannot create container 'touchstone-run-d3f45e93' under http://localhost:4711/
  caused by: java.net.ConnectException
  caused by: java.nio.channels.ClosedChannelException
```

An exception that nothing anticipated also exits with `2`, with a stack trace. Such an
exception is a harness bug, not a verdict about the server.

The exit code of `run` counts every failure, whatever its requirement level. The
conformance verdict in the reports counts only failures of MUST-level requirements, so a
run with one failing SHOULD test exits `1` while its report says CONFORMANT.
[Reports and verdicts](reports.md#the-verdict) explains the difference.
