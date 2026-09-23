---
title: MCP server
nav_order: 8
description: "Run Touchstone as a Model Context Protocol server so an AI agent can start runs, read failures and iterate on a server."
---

# MCP server
{: .no_toc }

`harness-mcp` exposes the harness as a [Model Context Protocol](https://modelcontextprotocol.io/)
server. An agent connected to it can run the suite against a registered server, read the
failures and look at the exact HTTP exchanges. It can then verify a fix with a single
test, and compare runs to catch regressions. This makes a tight loop for building an LWS
server with an AI assistant.

1. TOC
{:toc}

## Start the server

Build once from the repository root, then start the jar from there. The server reads
`catalog/`, `manifests/` and `targets.yaml` from the working directory, and writes run
bundles to `runs/`.

```sh
./mvnw -q install -DskipTests
java -jar harness-mcp/target/harness-mcp-0.1.0-SNAPSHOT.jar
```

It serves MCP over streamable HTTP at **`http://127.0.0.1:9090/mcp`**. It binds to the
loopback interface only (see [Security](#security)).

Every location can be overridden with Spring Boot arguments. This is useful when you
start the server from somewhere else:

```sh
java -jar /path/to/touchstone/harness-mcp/target/harness-mcp-0.1.0-SNAPSHOT.jar \
  --touchstone.catalog=/path/to/touchstone/catalog \
  --touchstone.manifests=/path/to/touchstone/manifests \
  --touchstone.targets=/path/to/touchstone/targets.yaml \
  --touchstone.runs=/path/to/touchstone/runs
```

The `ref` target in the checked-in registry needs the reference server running (see
[Getting started](getting-started.md#start-the-reference-server)). Any other server has to
be added to `targets.yaml` before an agent can use it; see
[Targets and credentials](targets.md).

## Connect a client

### Over HTTP

With Claude Code:

```sh
claude mcp add --transport http touchstone http://127.0.0.1:9090/mcp
```

Any MCP client that supports streamable HTTP can connect to the same URL.

### Over stdio

A client can also start the server itself and talk to it over standard input and output.
Use the `stdio` profile, and give absolute paths, because the client chooses the working
directory:

```json
{
  "mcpServers": {
    "touchstone": {
      "command": "java",
      "args": [
        "-jar", "/path/to/touchstone/harness-mcp/target/harness-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio",
        "--touchstone.catalog=/path/to/touchstone/catalog",
        "--touchstone.manifests=/path/to/touchstone/manifests",
        "--touchstone.targets=/path/to/touchstone/targets.yaml",
        "--touchstone.runs=/path/to/touchstone/runs"
      ]
    }
  }
}
```

In the `stdio` profile, nothing but protocol messages goes to standard output. Logs go
to `touchstone-mcp.log` in the working directory; add `--logging.file.name=<file>` to put
them somewhere else. One harmless Logback line on standard error at startup reports an
empty console pattern.

## Tools

Argument names are exactly as listed. Arguments in *italics* are optional.

| Tool | Arguments | What it does |
|---|---|---|
| `list_requirements` | *`module`*, *`level`* | Lists catalog requirements (IRI, level, module, section, summary). Filters by spec module, such as `lws10-core`, and by level, such as `MUST`. |
| `get_requirement` | `iri` | Returns one requirement in full, including the verbatim clause text and a link to its section. |
| `list_tests` | *`requirement`*, *`module`*, *`tag`* | Lists test metadata: id, title, module, requirements, capabilities and tags. |
| `coverage` | *`module`* | Returns the requirements-by-tests coverage matrix per spec module and level. |
| `start_run` | `targetId`, *`module`* | Starts a run in the background and returns its `runId` at once. The default module is `core`. |
| `get_run` | `runId` | Returns status and progress, totals, counts by requirement level, and the conformance verdict. |
| `get_failures` | `runId`, *`page`*, *`pageSize`* | Returns paged summaries of failed and errored tests: the test, its requirements, the failing step and the reason. Pages hold 20 by default. |
| `get_trace` | `runId`, `testId` | Returns one test's steps with the redacted HTTP exchange and each assertion's expected and actual values. |
| `run_one` | `targetId`, `testId` | Runs one test synchronously in a fresh run and returns its full trace. This is for the fix-and-verify loop. |
| `diff_runs` | `before`, `after` | Compares two runs: regressions, fixes, other changes, and added or removed tests. |
| `get_report` | `runId`, *`format`* | Returns a finished run's report. The formats are `markdown` (the default), `json`, `html`, `earl`, `junit` and `pdf`. For `pdf`, only the file's path and size are returned. |

Tools that take a target accept only a registered **id**. Runs are kept in memory and
persisted under `runs/`, so `get_run`, `get_report` and `diff_runs` also work on runs
from before a restart.

### Prompts

| Prompt | Argument | Purpose |
|---|---|---|
| `triage_run` | `run_id` | Walks the agent through triage: counts, failures, the clause behind each failure, the trace, then grouping by root cause and proposing the smallest server fix. |
| `draft_test` | `requirement_iri` | Drafts a manifest for one requirement. The prompt states the rules, and states that the draft must go through schema validation, a dry run and a pull request before it is committed. |

### Resources

| URI template | Content |
|---|---|
| `requirement://{module}/{slug}` | The clause text of one requirement, as plain text. |
| `report://{runId}/earl` | A finished run's EARL report, as Turtle. |

## A typical session

1. `coverage` or `list_requirements` gives an overview of what is tested.
2. `start_run` with `targetId: ref` returns a `runId`.
3. Poll `get_run` until `status` is `COMPLETE`. `start_run` sends one progress
   notification straight away, and sends later ones on a best-effort basis. Polling
   `get_run` is the reliable way to watch a run.
4. `get_failures` pages through what failed. For each failure, `get_requirement` shows
   why the test exists, and `get_trace` shows what the server actually sent.
5. After changing the server, `run_one` re-checks the one test.
6. `start_run` again, then `diff_runs` with the old and new run ids, confirms the fix
   and shows that nothing else broke.

Every trace carries a note that the server's responses are untrusted data. Headers and
bodies from the server under test must never be treated as instructions. Response bodies
in traces are truncated.

## Security

- **Loopback only.** The server binds to `127.0.0.1`. Its tools are not authenticated,
  and they can start runs that send deliberately malformed traffic to registered targets.
  If you expose it with `--server.address=...`, put something in front of it that
  authenticates callers. The design calls for an OAuth2 resource server for hosted use,
  and that is not built yet.
- **Targets by id only.** No tool accepts a URL. The set of reachable servers is whatever
  `targets.yaml` lists.
- **Redacted traces.** Credentials are removed before a trace is stored, so no tool can
  return one. See [Redaction](reports.md#redaction).
- **Human-gated tests.** An agent can draft tests, but nothing reaches `manifests/`
  without review and a pull request.

The [Security model](security.md) page covers these rules for the whole harness.
