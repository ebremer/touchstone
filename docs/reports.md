---
title: Reports and verdicts
nav_order: 6
description: "The files a run writes, what each is for, and how Touchstone decides whether a server conforms."
---

# Reports and verdicts
{: .no_toc }

1. TOC
{:toc}

## The report bundle

Every run writes one directory, `<report-dir>/<stamp>-<runId>/`. The stamp is the run's
start time in UTC, written in ISO 8601 basic form, for example
`2026-09-23T161022Z-9da51679`. Listing the directory therefore shows runs in the order
they happened. The format has no colons, so the name is valid on every filesystem,
Windows included.

| File | Contents |
|---|---|
| `run.json` | **The evidence.** Every test, every step, every assertion with expected and actual values, and every HTTP exchange, redacted. `touchstone diff` and the MCP server read runs back from this file. |
| `report.json` | **The finding**, as data: run totals, coverage per level, the requirement matrix with a result per requirement, and each test's outcome and failure detail. Dashboards and CI gates read this file. |
| `report.html` | The finding as a web page. Each test links to the requirements it verifies, and each requirement links to its section of the specification. |
| `report.md` | The same page as Markdown, for pull requests, issues, wikis and terminals. Every requirement in the matrix links to its clause. |
| `report.pdf` | The same report as a printable document. |
| `earl.ttl` | [W3C EARL](https://www.w3.org/TR/EARL10-Schema/) in Turtle, one assertion per test. |
| `junit.xml` | JUnit XML, one test case per test, for CI systems to display. |

The four `report.*` files are rendered from one model, so they always agree on the
verdict.

## The verdict

The reports compute one conformance verdict:

> A run is **CONFORMANT** when no test that verifies a **MUST** requirement has failed
> or errored.

A failing test that cites only SHOULD or MAY requirements is advisory. It is still
reported, but it does not change the verdict. A skipped test never counts against the
server.

The requirement matrix assigns each catalogued requirement one result:

| Result | Meaning |
|---|---|
| `PASS` | At least one test verifies it, and none of them failed or errored. |
| `FAIL` | A test that verifies it failed or errored. |
| `SKIPPED` | Every test that verifies it was skipped. |
| `UNCOVERED` | No test verifies it yet. |

A failing test marks every requirement it cites as `FAIL`. For that reason, a test that
checks a MUST should not also check a SHOULD. One failing SHOULD assertion inside it
would fail the MUST. The [YAML-LD definitions](definitions.md) enforce this rule by
giving every test exactly one level.

### How the verdict relates to exit codes and the MCP server

The three front ends read the same results, but they answer slightly different
questions:

| Where | Counts as not conforming |
|---|---|
| Reports (`report.*`) | A failed or errored test that cites at least one MUST requirement. |
| `touchstone run` exit code | Any failed or errored test, whatever its level. The exit code is `1`. |
| MCP `get_run` | A failed or errored test that cites a MUST requirement, or none of whose requirements are in the loaded catalog. `get_run` also reports not conforming until the run is complete. |

In practice, a SHOULD-only failure produces a CONFORMANT report together with exit code
`1`. When the exit code gates CI, a SHOULD failure fails the job. If the job should fail
only on MUST failures, gate on `report.json` instead, where `run.conformant` holds the
verdict.

## EARL

`earl.ttl` is the format W3C working groups use to collect implementation reports. Each
test is recorded as an `earl:Assertion`, with:

- `earl:subject`: the target's base URL;
- `earl:test`: the test's IRI, `https://example.org/touchstone/test/<test id>`. The test
  links to the catalog requirements it covers through `touchstone:verifies`;
- `earl:assertedBy`: the harness, with its name and version;
- `earl:result`: the outcome and the run's start time.

| Touchstone outcome | EARL outcome |
|---|---|
| `PASSED` | `earl:passed` |
| `FAILED` | `earl:failed` |
| `ERROR` | `earl:cantTell` |
| `SKIPPED` | `earl:inapplicable` |

This assertion is taken from a real run:

```turtle
[ a                earl:Assertion;
  earl:assertedBy  <https://example.org/touchstone/harness>;
  earl:mode        earl:automatic;
  earl:result      [ a             earl:TestResult;
                     dcterms:date  "2026-09-23T16:25:40.165666900Z"^^<http://www.w3.org/2001/XMLSchema#dateTime>;
                     earl:outcome  earl:failed
                   ];
  earl:subject     <http://localhost:49298/>;
  earl:test        <https://example.org/touchstone/test/core/create-in-missing-container-404>
] .
```

{: .note }
The `https://example.org/touchstone/` namespace is a placeholder until the vocabulary has
a permanent home. Every file declares it in one place, so moving it is a prefix change.

## Redaction

HTTP exchanges are redacted when they are recorded, before any report or tool sees them:

- **Headers:** the values of `Authorization`, `Proxy-Authorization`, `Cookie`,
  `Set-Cookie`, `DPoP`, `DPoP-Nonce`, `X-API-Key`, `API-Key`, `Authentication-Info` and
  `Proxy-Authenticate` are replaced with `[REDACTED]`.
- **Credential parameters** are replaced wherever they appear: as a JSON member, a form
  field or a query parameter. The names covered are `access_token`, `refresh_token`,
  `id_token`, `subject_token`, `actor_token`, `requested_token`, `client_secret`,
  `client_assertion`, `assertion`, `code`, `device_code`, `password` and `token`.
- **Bodies** are truncated after 2,048 characters.

This redaction is a floor, not a guarantee. A server that returns a secret under a
non-standard name will not have it caught. Treat run bundles from real deployments as
sensitive. `runs/` is ignored by Git in this repository.

## Comparing runs

`touchstone diff` (and the MCP `diff_runs` tool) compares two runs by test id:

- a **regression** is a test that passed before and now fails or errors;
- a **fix** is a test that failed or errored before and now passes;
- every other change, such as a pass that became a skip, is listed as an **other
  outcome change**;
- tests present in only one of the two runs are listed as **added** or **removed**.

Only regressions set a non-zero exit code.
