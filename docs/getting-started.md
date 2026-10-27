---
title: Getting started
nav_order: 2
description: "Build Touchstone, run the test definitions against the bundled reference servers, and read the reports."
---

# Getting started
{: .no_toc }

This page builds Touchstone and runs its test definitions against the bundled reference
servers: first an open one, then a secured deployment with an authorization server, where
every definition applies. Then it shows you where the reports are. It takes about five
minutes, most of them spent in the first build.

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
which runs every definition against the reference deployment and against its broken
twins. The last line should print `Touchstone 0.1.0-SNAPSHOT`.

To skip the tests and just produce the jars, run `./mvnw -q clean install -DskipTests`.
You need `install` once either way, so that later `-pl <module>` commands can find the
sibling modules.

{: .note }
The examples call the CLI as `touchstone`. Either define an alias, for example
`alias touchstone='java -jar "$PWD/harness-cli/target/touchstone.jar"'`, or type the
`java -jar` form. Run the CLI from the repository root. Its defaults for `catalog/`,
`definitions/`, `targets.yaml` and `runs/` are paths relative to the working directory.

## Run against the open reference server

Touchstone includes an in-memory LWS server that follows the 19 October 2026 Working
Draft. Start it in its own terminal:

```sh
./mvnw -q -pl harness-fixtures exec:java -Dexec.args=4711
```

It prints `reference LWS server listening at http://localhost:4711/` and runs until you
press Ctrl+C. The `ref` target in the checked-in `targets.yaml` already points at this
address. In a second terminal:

```sh
touchstone run --target ref
```

Each test prints one line as it finishes, with its outcome and level. A summary follows,
then the verdict and the location of the report bundle (abridged):

```text
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

This server authenticates nobody, so every test about authentication, authorization and
the authentication suites is inapplicable, and says why. The rest pass.

## Run every definition

The secured reference deployment adds a reference authorization server, and configures the
harness to hold its signing key and to host the identity documents it dereferences. Every
capability a definition can require is declared, so every definition applies. Start it,
and give it a file to write its target registry to:

```sh
./mvnw -q -pl harness-fixtures exec:java \
  -Dexec.mainClass=com.ebremer.touchstone.fixtures.SecuredRefScenarioMain \
  -Dexec.args=targets-secured.yaml
```

It prints the two servers' addresses and the command to run. In a second terminal:

```sh
touchstone run --target secured-ref --targets targets-secured.yaml
```

```text
[passed      ] MUST   core/discovery#discovery-get-links-storageDescription (68 ms)
...
[inapplicable] MUST   core/notifications#notification-service-advertised (16 ms)
    core/notifications#notification-service-advertised - inapplicable [MUST] (16 ms)
      inapplicable: precondition 'the storage advertises notifications (otherwise this test does not apply)' does not hold: ...
...
100 passed, 0 failed, 0 cantTell, 1 inapplicable  (target secured-ref, run c915cfee)
conformant: no MUST test failed or ended cantTell
```

The one inapplicable test checks the advertisement of a notification service, and the
reference server has none to advertise. `targets-secured.yaml` holds throwaway keys for
these local servers, and is git-ignored.

The exit code is `0` when no MUST test failed or ended cantTell, and `1` when one did. It
is `2` when there is no verdict, for example when the target id is unknown or the server
cannot be reached. The [command-line reference](cli.md#exit-codes) has the details.

## Read the reports

Each run writes a directory named by its start time and run id:

| File | What it is for |
|---|---|
| `report.html` | Open it in a browser: the verdict, every test with its level, and the requirement matrix with links to the specification. |
| `report.md` | The same report as Markdown, for pull requests, issues and terminals. |
| `report.pdf` | The same report as a printable document. |
| `report.json` | The same report as data, for dashboards and CI gates. |
| `earl.ttl` | W3C EARL assertions, the format W3C implementation reports use. |
| `junit.xml` | JUnit XML, which most CI systems display natively. |
| `run.json` | The evidence: every step and every HTTP exchange, redacted. `diff` and the MCP server read it. |

[Reports and verdicts](reports.md) explains what each report contains and how the
verdict is decided.

## Check coverage

`coverage` shows which of the requirements a server run answers for (those binding a server
or an authorization server) at least one test cites:

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
2. If the storage is not world-writable, supply alice's access token through
   `TOUCHSTONE_TOKEN_ALICE`: she creates the run's containers and makes every request a
   test does not make as someone else. With a second agent's token in
   `TOUCHSTONE_TOKEN_BOB`, declare the `Authentication` capability, and the access-control
   tests apply too.
3. Run `touchstone run --target <your-id>`.

Touchstone creates one container for the run under the registered URL. Each test gets
its own container inside it, and deletes it when it ends. When the run ends, Touchstone
deletes the run's container, and logs a warning if the server refuses.

## Every known LWS server

The LWS server implementations Touchstone is run against are in the repository too, in
[`targetservers/`]({% include src.html path="targetservers" kind="tree" %}): Halcyon,
lws-server, sparq-lws-core, the Community Solid Server, lws-drupal, lwsd and Factoidal, each built from
source at a pinned commit. One script tests them all. It needs Docker, with its compose
plugin, and nothing else, not even a JDK:

```sh
git clone https://github.com/ebremer/touchstone && cd touchstone
targetservers/run-all.sh                # or only some: targetservers/run-all.sh css lwsd
```

It builds the harness image and every server, and starts them on a private Docker network.
It also makes what a run needs there: keys, alice's and bob's identities, and a fixture host
with a certificate from a CA made for the run. It tests each server in turn, then stops them.
The results go in `targetservers/runs/<UTC time>/`:

- `reports/`: each run's report bundle, as described in [Read the reports](#read-the-reports);
- `summary.md`: the totals and the verdict of each server;
- `matrix.html`: every requirement against every server.

It exits `0` when every server is conformant, `1` when every server was tested and at least
one is not, and `2` when a server could not be tested. From a fresh clone, with nothing
cached, it took 17 minutes on a large cloud VM. The images take about 5 GB of disk, and
building them takes several times that again for base images and build cache.
`--keep` leaves the servers running afterwards, and `--module <m>` runs part of the suite.
The [targetservers README]({% include src.html path="targetservers/README.md" %}) describes
the containers' contract, how a run reaches the servers, and how to add an implementation.

### Every client against every server

`targetservers/interop-all.sh` tests the other side: which LWS clients work with which of
these servers. It builds the [lws-client](https://github.com/ebremer/lws-client) driver with
its eleven clients (Java, JavaScript, Python, Go, Rust, C++, C#, Swift, PHP, Kotlin, and the
Rust one as a WebAssembly component), and drives each through one scenario against each
server: discovery, signing in, creating, reading and updating resources, conditional requests,
JSON Patch, linksets, listings, type search, notifications, access requests and grants, and
deleting. Every storage belongs to one agent made for the run, and every client signs in as
that agent with a self-signed credential (the CID suite). The results go in
`targetservers/runs/<UTC time>-interop/`, with `matrix.html` showing the clients against the
servers. It exits `0` when every combination works and `1` when one does not.

## Next steps

- [How it works](how-it-works.md): what happens during a run.
- [Distribution](distribution.md): run the suite in CI with one workflow file.
- [MCP server](mcp.md): let an agent drive the harness.
- [Writing tests](writing-tests.md): add a test for a requirement that has none.
