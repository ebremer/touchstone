---
title: Security model
nav_order: 13
description: "The rules Touchstone never breaks, and where each one is enforced."
---

# Security model
{: .no_toc }

Touchstone is deliberately an HTTP cannon. It sends malformed, hostile and high-volume
requests to the servers it tests, it handles credentials, and it can be driven by an AI
agent. Five rules keep that safe. They are not negotiable, and the code enforces each one.

1. TOC
{:toc}

## 1. Pre-registered targets only

**Rule:** Touchstone tests only servers listed in its target registry, and every
interface accepts a target **id**, never a URL.

- The CLI's `--target`, the MCP tools' `targetId`, and the run engine all resolve ids
  through `targets.yaml`. An unknown id is refused.
- The GitHub Action takes the target URL as an input, and writes the registry itself.
  The value reaches the script through an environment variable, never by expression
  interpolation. It must be an absolute `http(s)` URL without whitespace or quotes, and
  it is written with `printf`, so a crafted input cannot add targets or run shell
  commands.
- The Docker image runs as an unprivileged user.

## 2. Redact on the server side

**Rule:** credentials are removed before a trace exists, so no report, file or tool
response can leak one.

- Authorization, cookie, DPoP and API-key headers are replaced with `[REDACTED]`.
- OAuth, OpenID Connect and token-exchange parameters (`access_token`, `id_token`,
  `subject_token`, `client_secret`, `password`, and others) are blanked wherever they
  appear: as a JSON member, a form field or a query parameter.
- Bodies are truncated after 2,048 characters.

Name-based redaction cannot catch a secret under a non-standard name, which is one more
reason bodies are truncated. [Reports and verdicts](reports.md#redaction) has the full
list.

## 3. Responses from the server under test are untrusted

**Rule:** anything the server under test sends is data, never instructions or
configuration.

- Every MCP trace is labelled as untrusted server output, because agents read it, and a
  response body is a place to hide a prompt injection. Bodies in traces are truncated.
- JSON-LD contexts are never fetched. A context IRI in a response cannot make the harness
  send a request, or change the term mappings a verdict is computed from. Contexts
  Touchstone understands are bundled; any other one fails the assertion.
- Redirects are not followed, so a response cannot send the harness to another host.

## 4. People approve tests

**Rule:** a test enters the suite only through review.

The mapping from tests to requirements is what every verdict rests on. An agent can
draft a test, and the MCP `draft_test` prompt helps it do so. The draft must still pass
the definition checks and a run against the reference deployment, and then go through a
pull request. No tool writes to `definitions/`.

## 5. The MCP endpoint is local unless you secure it

**Rule:** the MCP server listens on `127.0.0.1` by default.

Its tools are unauthenticated and can start runs. To host it, set `server.address`
explicitly, and put an authenticating layer in front of it. The design calls for a Spring
Security OAuth2 resource server; that does not exist yet.

## Credentials in practice

- Never put a real token in `targets.yaml`. It is checked in, and it ships with the
  harness. Use `TOUCHSTONE_TOKEN_<NAME>` environment variables, such as CI secrets.
- Files that ship with the harness describe mechanisms, not deployments. Specific
  identity providers, WebIDs and OAuth clients belong in the operator's environment.
- Git ignores `runs/`. Treat report bundles from real servers as sensitive even after
  redaction.
