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

Runs test definitions against a registered target and writes a report bundle.

```text
touchstone run -t <targetId> [-m <selector>] [--targets <file>] [--definitions <dir>]
               [-c <catalogDir>] [--report-dir <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `-t`, `--target` | (required) | Target id from the registry. Never a URL. |
| `-m`, `--module` | `all` | What to run: `all`; a module, `core` or `auth`; a manifest, such as `core/containers` or `auth/oidc`; or one test, by id (`core/containers#getContainer`) or by name. |
| `--targets` | `targets.yaml` | The target registry file. |
| `--definitions` | `definitions` | The definitions directory, holding `lws10/` and `schema/`. |
| `-c`, `--catalog` | `catalog` | The requirements catalog directory. |
| `--report-dir` | `runs` | Where the report bundle is written, as `<report-dir>/<stamp>-<runId>/`. |

Before sending any request, `run` checks that:

- the registry file exists, parses, and holds the target id;
- the definitions are in the format this engine implements (0.2.0);
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
    core/storage_authorization#getContainer-private-unauthorized - inapplicable [MUST] (0 ms)
      inapplicable: the target does not declare Authentication
...
45 passed, 0 failed, 0 cantTell, 56 inapplicable  (target ref, run d0b828c6)
conformant: no MUST test failed or ended cantTell
reports: runs/2026-09-23T200803Z-d0b828c6 (run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml)
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
        exchange: GET http://localhost:4712/touchstone-run-9d5148bb/getcontainer-private-unauthorized/ -> 200
        response body: {"@context":"https://www.w3.org/ns/lws/v1","id":"http://localhost:4712/...","type":"Container","totalItems":0,"items":[]}
[passed      ] MUST   core/storage_authorization#getContainer-authenticated-owner (39 ms)
[failed      ] MUST   core/storage_authorization#createDataResource-unauthorized (37 ms)
...
1 passed, 7 failed, 0 cantTell, 9 inapplicable  (target broken, run 9d5148bb)
NOT conformant: 7 MUST test(s) failed or ended cantTell
```

Its registry declares `Authentication` and gives alice and bob placeholder tokens, which
that server never checks. The nine inapplicable tests need a real JWT to derive a fault
from, or the authorization server's key.

## `touchstone coverage`

Prints how many catalogued requirements at least one test cites, for each spec module and
level.

```text
touchstone coverage [-c <catalogDir>] [--definitions <dir>]
```

| Option | Default | Meaning |
|---|---|---|
| `-c`, `--catalog` | `catalog` | The requirements catalog directory. |
| `--definitions` | `definitions` | The definitions directory. Every test counts. |

```text
$ touchstone coverage
Requirements coverage: 127 of 232 covered by 101 test(s)

module                   level    covered/total
lws10-authn-openid       MUST     8/8
lws10-authn-saml         MUST     7/7
lws10-authn-ssi-cid      MUST     14/14
lws10-authn-ssi-did-key  MUST     12/12
lws10-core               MUST     79/147
lws10-core               SHOULD   6/22
lws10-core               MAY      1/22
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
$ touchstone diff runs/2026-09-23T200821Z-9ca42641 runs/2026-09-23T200806Z-9d5148bb
diff 9ca42641 (2026-09-23T20:08:21.206763500Z) -> 9d5148bb (2026-09-23T20:08:06.388438600Z)
regressions:
  core/storage_authorization#getContainer-private-unauthorized: passed -> failed
  core/storage_authorization#createDataResource-unauthorized: passed -> failed
  ...
no fixes
other outcome changes:
  core/storage_authorization#authz-expired-token-rejected: passed -> inapplicable
  ...
1 unchanged
```

`diff` exits with `1` when there is at least one regression, so a CI job can gate on it,
and with `2` when it cannot read one of the runs.

## Exit codes

Exit code `1` is always a verdict about the server under test. Exit code `2` always means
there is no verdict. A CI job can therefore tell "the server does not conform" apart from
"the job is broken" by the exit code alone. Each command's `--help` lists its codes.

| Code | `run` | `coverage` | `diff` |
|---|---|---|---|
| `0` | Conformant: no MUST test failed or ended cantTell. SHOULD and MAY failures are advisory. | The matrix was printed. | No regressions. |
| `1` | Not conformant: a MUST test failed or ended cantTell. | | At least one regression. |
| `2` | No verdict. Either the harness is misconfigured, or the run could not start on the target; see below. | The catalog or the definitions could not be read. | A run could not be read. |

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
no test matches 'core/nothing'; try all, a module (core, auth), a manifest (core/discovery, core/containers, core/data_resources, core/conditional_requests, core/linksets, core/storage_authorization, core/authorization_server, core/access_grants, core/notifications, auth/did_key, auth/oidc, auth/cid, auth/saml) or a test id
```

An exception that nothing anticipated also exits with `2`, with a stack trace. Such an
exception is a harness bug, not a verdict about the server.

The exit code of `run` follows the verdict in the reports: only a MUST test that failed or
ended cantTell makes it `1`. A run whose only failures are SHOULD or MAY tests exits `0`,
and says how many there were. [Reports and verdicts](reports.md#the-verdict) explains the
verdict.
