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
`2026-09-23T200806Z-9d5148bb`. Listing the directory therefore shows runs in the order
they happened. The format has no colons, so the name is valid on every filesystem,
Windows included.

| File | Contents |
|---|---|
| `run.json` | **The evidence.** Every test with its level, every step, every expectation with expected and actual values, and every HTTP exchange, redacted. `touchstone diff` and the MCP server read runs back from this file. |
| `report.json` | **The finding**, as data: run totals, coverage per level, the requirement matrix with a result per requirement, and each test's level, outcome and detail. Dashboards and CI gates read this file. |
| `report.html` | The finding as a web page. Each test links to the requirements it verifies, and each requirement links to its section of the specification. |
| `report.md` | The same page as Markdown, for pull requests, issues, wikis and terminals. Every requirement in the matrix links to its clause. |
| `report.pdf` | The same report as a printable document. |
| `earl.ttl` | [W3C EARL](https://www.w3.org/TR/EARL10-Schema/) in Turtle, one assertion per test. |
| `junit.xml` | JUnit XML, one test case per test, for CI systems to display. |

The four `report.*` files are rendered from one model, so they always agree on the
verdict.

## The verdict

Every test has exactly one level, and the verdict is the one
[`definitions/EXECUTION.md`]({% include src.html path="definitions/EXECUTION.md" %}),
section 9, defines:

> A target **conforms** when no **MUST** test failed and no MUST test ended **cantTell**.

A failed SHOULD or MAY test is advisory: it is reported, and counted, but it does not
change the verdict. An inapplicable test never counts against the server. An inapplicable
MUST test is coverage the run did not have, not evidence either way, so the reports count
those separately.

The same verdict decides `touchstone run`'s exit code, the `conformant` flag of the MCP
`get_run` tool, and `run.conformant` in `report.json`: a run is either conformant
everywhere or nowhere.

The requirement matrix assigns each catalogued requirement one result:

| Result | Meaning |
|---|---|
| `PASS` | At least one test that cites it passed, and none failed or ended cantTell. |
| `FAIL` | A test that cites it failed or ended cantTell. |
| `INAPPLICABLE` | Every test that cites it was inapplicable. |
| `UNCOVERED` | No test cites it yet. |

The matrix is about requirements and the verdict is about tests, so the two can read
differently: a SHOULD test that fails marks the requirements it cites as `FAIL` without
changing the verdict.

## Outcomes

| Outcome | EARL outcome | Meaning |
|---|---|---|
| `passed` | `earl:passed` | Every step passed. |
| `failed` | `earl:failed` | An expectation failed outside a precondition. |
| `cantTell` | `earl:cantTell` | The harness could not decide: a transport error, a timeout, a failed prerequisite. |
| `inapplicable` | `earl:inapplicable` | A capability, identity, service or precondition the test needs is absent. |

Run records written before the YAML-LD engine used `ERROR` and `SKIPPED`; they still load,
as `cantTell` and `inapplicable`, and count as MUST tests since they had no level.

## EARL

`earl.ttl` is the format W3C working groups use to collect implementation reports. Each
test is recorded as an `earl:Assertion`, with:

- `earl:subject`: the target's base URL;
- `earl:test`: the test's IRI,
  `https://example.org/touchstone/test/lws10/<manifest path>#<name>`. That is the IRI the
  test's JSON-LD gives it, and it is the same whether the definition is read as YAML-LD or
  as its JSON-LD export. The test case carries its label as `dcterms:title`, and links to
  the catalog requirements it covers through `touchstone:verifies`;
- `earl:assertedBy`: the harness, with its name and version;
- `earl:result`: the outcome and the run's start time.

This assertion and test case are taken from a real run against the broken twin:

```turtle
[ a                earl:Assertion;
  earl:assertedBy  <https://example.org/touchstone/harness>;
  earl:mode        earl:automatic;
  earl:result      [ a             earl:TestResult;
                     dcterms:date  "2026-09-23T20:08:06.388438600Z"^^<http://www.w3.org/2001/XMLSchema#dateTime>;
                     earl:outcome  earl:failed
                   ];
  earl:subject     <http://localhost:4712/>;
  earl:test        <https://example.org/touchstone/test/lws10/core/storage_authorization#getContainer-private-unauthorized>
] .

<https://example.org/touchstone/test/lws10/core/storage_authorization#getContainer-private-unauthorized>
        a                    earl:TestCase;
        dcterms:title        "An anonymous request for a protected container is refused with 401 and a conforming challenge";
        touchstone:verifies  <https://example.org/touchstone/req/lws10-core/authz-challenge-realm-param> ,
                             <https://example.org/touchstone/req/lws10-core/authz-challenge-as-uri-param> ,
                             <https://example.org/touchstone/req/lws10-core/authz-401-www-authenticate-challenge> .
```

{: .note }
The `https://example.org/touchstone/` namespace is a placeholder until the vocabulary has
a permanent home. Every file declares it in one place, so moving it is a prefix change.

## Redaction

Everything a run records is redacted when it is recorded, before any report or tool sees
it:

- **Headers:** the values of `Authorization`, `Proxy-Authorization`, `Cookie`,
  `Set-Cookie`, `DPoP`, `DPoP-Nonce`, `X-API-Key`, `API-Key`, `Authentication-Info` and
  `Proxy-Authenticate` are replaced with `[REDACTED]`. `WWW-Authenticate` is kept: it is
  the evidence a 401 test exists to capture.
- **Credential parameters** are replaced wherever they appear: as a JSON member, a form
  field or a query parameter. The names covered are `access_token`, `refresh_token`,
  `id_token`, `subject_token`, `actor_token`, `requested_token`, `client_secret`,
  `client_assertion`, `assertion`, `code`, `device_code`, `password` and `token`.
- **JWTs** are replaced wherever they appear, whatever they are called, by their shape.
- **Expectations** on a credential, such as a check that `/access_token` is a string,
  record that the check held, never the value checked.
- **Bodies** are truncated after 2,048 characters.

This redaction is a floor, not a guarantee. A server that returns a secret under a
non-standard name, in a form that is not a JWT, will not have it caught. Treat run bundles
from real deployments as sensitive. `runs/` is ignored by Git in this repository.

## Comparing runs

`touchstone diff` (and the MCP `diff_runs` tool) compares two runs by test id:

- a **regression** is a test that passed before and now fails or ends cantTell;
- a **fix** is a test that failed or ended cantTell before and now passes;
- every other change, such as a pass that became inapplicable, is listed as an **other
  outcome change**;
- tests present in only one of the two runs are listed as **added** or **removed**.

Only regressions set a non-zero exit code.
