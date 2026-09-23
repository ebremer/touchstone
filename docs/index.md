---
title: Home
layout: home
nav_order: 1
permalink: /
description: "Touchstone is a conformance test harness for the W3C Linked Web Storage (LWS) protocol family."
---

# Touchstone
{: .fs-9 }

A conformance test harness for servers that implement the W3C Linked Web Storage (LWS) protocol.
{: .fs-6 .fw-300 }

[Get started](getting-started.md){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/ebremer/touchstone){: .btn .fs-5 .mb-4 .mb-md-0 }

---

Touchstone checks whether a server implements the
[W3C Linked Web Storage protocol](https://www.w3.org/TR/lws10-core/) correctly. It acts as
the client. It sends HTTP requests to the server under test, checks each response against
what the specification requires, and reports the result one requirement at a time.

A touchstone is the dark stone assayers once used to test gold against a known standard.

## What it does

- **Tests against the specification's own text.** Every normative clause (MUST, SHOULD or
  MAY) in the LWS drafts is in a [requirements catalog](catalog.md). Each clause has a
  stable IRI and a hash that detects when the draft changes. Every test names the
  requirements it verifies.
- **Tests are data.** A test is a short YAML [manifest](writing-tests.md): HTTP steps with
  declarative assertions on status codes, headers, JSON, RDF graphs and SHACL shapes. No
  test code is written in Java.
- **Owns the identity providers.** To check that a server *rejects* bad credentials,
  Touchstone runs its own OpenID Connect issuer and self-signed-key fixtures. They can mint
  expired, mis-signed or wrong-audience tokens on demand. See
  [authentication suites](auth.md).
- **Reports in standard formats.** Each run writes W3C [EARL](https://www.w3.org/TR/EARL10-Schema/)
  for implementation reports, plus HTML, Markdown, PDF, JSON and JUnit XML. See
  [reports and verdicts](reports.md).
- **One engine, three front ends.** The same engine runs behind a
  [command-line tool](cli.md), a [Docker image and GitHub Action](distribution.md) for CI,
  and an [MCP server](mcp.md). The MCP server lets an AI agent start runs, read failures
  and iterate on a server implementation.

## Where to start

| If you want to... | Read |
|---|---|
| Try Touchstone in five minutes against the bundled reference server | [Getting started](getting-started.md) |
| Test your own LWS server | [Targets and credentials](targets.md), then [Command line](cli.md) |
| Get a conformance report on every push to your server's repository | [Distribution](distribution.md) |
| Let an AI agent run the suite and fix your server | [MCP server](mcp.md) |
| Understand how a run works | [How it works](how-it-works.md) |
| Write or review tests | [Writing tests](writing-tests.md) and [Requirements catalog](catalog.md) |
| Follow the YAML-LD test format being developed with the LWS test group | [YAML-LD definitions](definitions.md) |

## Status

As of September 2026:

- **Implemented:** the engine, the reporting, the reference servers, the CLI, the MCP server,
  the Docker image and the GitHub Action.
- **Requirements catalog:** 232 requirements (188 MUST, 22 SHOULD, 22 MAY) from all five
  published LWS 1.0 documents: the core protocol and the OpenID Connect, SAML 2.0,
  Controlled Identifier and did:key authentication suites.
- **Tests:** 24 core-protocol tests and 9 OpenID Connect access-token tests. Together they
  cover 48 of the 232 requirements. Run `touchstone coverage` for the current figures.
- **did:key and CID suites:** proven at fixture level, with real Ed25519 credentials and a
  matrix of broken variants. **SAML:** catalogued, but its fixture is not built yet.
- **Spec baseline:** the catalog is baselined on the 21 August 2026 core Working Draft.
  The 21 September 2026 draft changed 14 catalogued clauses. Re-baselining is pending
  review (see [Requirements catalog](catalog.md#tracking-the-draft)).
- **Next test format:** a [YAML-LD test format](definitions.md), frozen at version 0.2.0. It
  mirrors and extends the LWS test group's JSON-LD suite, `lws-test-suite`, and has 101
  test definitions so far. Touchstone does not execute it yet.

{: .note }
LWS is a Working Draft and changes often. Touchstone tracks it deliberately: each
catalog entry records the dated draft it was taken from. The drift check tells you
when that draft has moved on.
