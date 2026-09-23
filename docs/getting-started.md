---
title: Getting started
nav_order: 2
description: "Build Touchstone, run the core suite against the bundled reference server, and read the reports."
---

# Getting started
{: .no_toc }

This page builds Touchstone and runs the core test suite against the bundled reference
server. Then it shows you where the reports are. It takes about five minutes, most of them
spent in the first build.

1. TOC
{:toc}

## Requirements

- **JDK 21 or newer.** CI uses Eclipse Temurin 21.
- **Git.**
- **Docker** is optional. You need it only for the [container image](distribution.md).

Maven is not needed; the repository includes the Maven wrapper (`./mvnw`, or `mvnw.cmd`
on Windows).

## Build

```sh
git clone https://github.com/ebremer/touchstone.git
cd touchstone
./mvnw -B verify
java -jar harness-cli/target/touchstone.jar --version
```

`verify` compiles all four modules and runs Touchstone's own test suite. That suite
includes the [self-test loop](how-it-works.md#reference-servers-and-the-self-test-loop),
which runs every core test against the reference server. The last line should print
`Touchstone 0.1.0-SNAPSHOT`.

To skip the tests and just produce the jars, run `./mvnw -q install -DskipTests`. You
need `install` once either way, so that later `-pl <module>` commands can find the
sibling modules.

{: .note }
The examples call the CLI as `touchstone`. Either define an alias, for example
`alias touchstone='java -jar "$PWD/harness-cli/target/touchstone.jar"'`, or type the
`java -jar` form. Run the CLI from the repository root. Its defaults for `catalog/`,
`manifests/`, `targets.yaml` and `runs/` are paths relative to the working directory.

## Start the reference server

Touchstone includes an in-memory LWS server. It implements the draft's happy-path
behaviour, which makes it a known-good target. Start it in its own terminal:

```sh
./mvnw -q -pl harness-fixtures exec:java -Dexec.args=4711
```

It prints `reference LWS server listening at http://localhost:4711/` and runs until you
press Ctrl+C. The `ref` target in the checked-in `targets.yaml` already points at this
address.

## Run the core suite

In a second terminal:

```sh
touchstone run --target ref --module core
```

Each test prints one line as it finishes. A summary line follows, then the location of
the report bundle:

```text
[PASSED] core/conditional-get-304 (39 ms)
[PASSED] core/conditional-get-stale-validator-200 (50 ms)
[PASSED] core/conditional-if-match-mismatch-412 (49 ms)
...
[PASSED] core/storage-description-discovery (41 ms)

24 passed, 0 failed, 0 errors, 0 skipped  (target ref, run 9da51679)
reports: runs/2026-09-23T161022Z-9da51679 (run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml)
```

When a test does not pass, the lines under it show the failing step, each assertion's
expected and actual values, and the HTTP exchange.

The exit code is `0` when every test passed or was skipped, and `1` when any failed or
errored. It is `2` when there is no verdict, for example when the target id is unknown or
the server cannot be reached. The [command-line reference](cli.md#exit-codes) has the
details.

## Read the reports

Each run writes a directory named by its start time and run id:

| File | What it is for |
|---|---|
| `report.html` | Open it in a browser: the verdict, every test, and the requirement matrix with links to the specification. |
| `report.md` | The same report as Markdown, for pull requests, issues and terminals. |
| `report.pdf` | The same report as a printable document. |
| `report.json` | The same report as data, for dashboards and CI gates. |
| `earl.ttl` | W3C EARL assertions, the format W3C implementation reports use. |
| `junit.xml` | JUnit XML, which most CI systems display natively. |
| `run.json` | The evidence: every step and every HTTP exchange, redacted. `diff` and the MCP server read it. |

[Reports and verdicts](reports.md) explains what each report contains and how the
verdict is decided.

## Check coverage

`coverage` shows which catalogued requirements have at least one test:

```text
$ touchstone coverage
Requirements coverage: 48 of 232 covered by 33 manifest(s)

module           level    covered/total
lws10-authn-openid MUST     2/8
lws10-authn-saml MUST     0/7
lws10-authn-ssi-cid MUST     0/14
lws10-authn-ssi-did-key MUST     0/12
lws10-core       MUST     44/147
lws10-core       SHOULD   2/22
lws10-core       MAY      0/22
```

## Compare two runs

Run the suite again, then compare the two report directories:

```sh
touchstone diff runs/<first-run-dir> runs/<second-run-dir>
```

`diff` lists the regressions (passed before, fails now), the fixes, other outcome
changes, and any tests added or removed. It exits with `1` if there is a regression,
which makes it usable as a CI gate.

## Test your own server

1. Add your server to `targets.yaml` under an id of your choice, as described in
   [Targets and credentials](targets.md). Touchstone never accepts a URL on the command
   line, only a registered id.
2. If the storage is not world-writable, name an identity for the suite to act as, and
   supply its token through an environment variable.
3. Run `touchstone run --target <your-id> --module core`.

Touchstone creates one container for the run under the registered URL. Each test gets
its own container inside it. When the run ends, Touchstone tries to delete the run's
container, and logs a warning if the server refuses.

## Next steps

- [How it works](how-it-works.md): what happens during a run.
- [Distribution](distribution.md): run the suite in CI with one workflow file.
- [MCP server](mcp.md): let an agent drive the harness.
- [Writing tests](writing-tests.md): add a test for a requirement that has none.
