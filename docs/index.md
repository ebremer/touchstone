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
- **Tests are data.** A test is a short YAML-LD [definition](writing-tests.md): one request
  or a flow of steps, with declarative expectations on status codes, headers, links,
  challenges, JSON and JWTs. No test code is written in Java. The format mirrors and extends
  the LWS test group's own JSON-LD suite, and exports to it without loss.
- **Owns the identity providers.** To check that a server *rejects* bad credentials,
  Touchstone mints its own: access tokens with one defect each, CID credentials for
  HTTPS and did:key subjects, ID Tokens from its own OpenID Provider, and signed SAML assertions. See
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
| Test your LWS client, from a browser or from its own CI | [Testing a client](testing-a-client.md) |
| Test your own LWS server | [Targets and credentials](targets.md), then [Command line](cli.md) |
| Run the suite against every known LWS server implementation | [Every known LWS server](getting-started.md#every-known-lws-server) |
| Get a conformance report on every push to your server's repository | [Distribution](distribution.md) |
| Let an AI agent run the suite and fix your server | [MCP server](mcp.md) |
| Understand how a run works | [How it works](how-it-works.md) |
| Write or review tests | [Writing tests](writing-tests.md) and [Requirements catalog](catalog.md) |
| Understand the YAML-LD test format, and how it relates to the LWS test group's suite | [YAML-LD definitions](definitions.md) |

## Status

As of October 2026:

- **Implemented:** the engine, the reporting, the reference servers, the CLI, the MCP server,
  the Docker image and the GitHub Action.
- **Client testing:** a service that tests LWS clients against sessions of their own, judged by
  53 client rules, with EARL, JUnit XML and JSON reports, a proxy mode in front of a real server,
  and read-only MCP tools for a coding agent. See [Testing a client](testing-a-client.md).
- **Requirements catalog:** 321 requirements (254 MUST, 29 SHOULD, 38 MAY) from the four
  current LWS 1.0 documents (the core protocol and the OpenID Connect, SAML 2.0 and
  Controlled Identifier authentication suites) and the editor's drafts of the webhook
  notification suite and the search and type index services.
- **Tests:** 221 [YAML-LD definitions](definitions.md) (181 MUST, 29 SHOULD, 11 MAY),
  format 0.14.0, frozen. They follow the 19 October 2026 Working Draft and the editor's drafts
  of the webhook notification suite and the search and type index services, cover all 27 tests
  of the LWS test group's `lws-test-suite`, and cover 243 of the 272 catalogued requirements
  that bind a server or an authorization server. Run `touchstone coverage` for the current figures.
- **Authentication:** the access-token negative matrix, RFC 8693 token exchange, access
  grants, and the OpenID Connect, CID (HTTPS and did:key subjects) and SAML suites all
  run end to end. Against the reference deployment, 218 of the 221 definitions pass; the other
  three are inapplicable to it: the single-page pagination test, since the reference splits that
  container into pages, and two move tests for a storage that moves less than it does.
- **Target servers:** the seven LWS server implementations Touchstone is run against (Halcyon,
  lws-server, sparq-lws-core, the Community Solid Server, lws-drupal, lwsd and Factoidal), each built from
  source into a container. One script builds and tests them all on one machine with nothing but
  Docker, and another tests every [lws-client](https://github.com/ebremer/lws-client) library
  against every one of them; see [Every known LWS server](getting-started.md#every-known-lws-server).
- **Catalog baseline:** the catalog and the definitions follow the drafts W3C published as
  of 19 October 2026: the 19 October core Working Draft and the 15 October OpenID Connect,
  SAML and CID ones. A weekly CI job fails when W3C publishes a newer version
  (see [Requirements catalog](catalog.md#tracking-the-draft)). The did:key suite, which
  W3C discontinued on 29 September 2026 in favour of the CID suite, is retired (D-0058).

{: .note }
LWS is a Working Draft and changes often. Touchstone tracks it deliberately: each
catalog entry records the dated draft it was taken from. The drift check tells you
when that draft has moved on.
