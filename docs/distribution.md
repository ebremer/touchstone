# Distribution

Touchstone ships as a Docker image and a reusable GitHub Action so a third-party LWS
server implementation gets a conformance report on every push by adding **one workflow
file**.

## Docker image

```
docker build -t touchstone-harness .
docker run --rm touchstone-harness --version
```

The image carries the shaded CLI plus the requirements catalog and the test definitions. Run a
conformance report against a running server by mounting a work directory that holds the
target registry and receives the reports:

```
mkdir -p work
cat > work/targets.yaml <<'EOF'
targets:
  sut:
    baseUrl: http://host.docker.internal:3000/
    adapter: env
    capabilities: []
EOF

# The image runs as a non-root user; --user makes reports land owned by you rather than
# by the uid baked into the image.
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/work:/work" \
  -e TOUCHSTONE_TOKEN_ALICE -e TOUCHSTONE_TOKEN_BOB touchstone-harness \
  run --target sut \
      --targets /work/targets.yaml --report-dir /work/runs \
      --catalog catalog --definitions definitions
```

Reports land in `work/runs/<timestamp>-<runId>/` as `run.json`, `earl.ttl`, `junit.xml`,
`report.html`, `report.json`, `report.md` and `report.pdf`. The `-e` options pass alice's and
bob's tokens through from your environment when they are set; on a server that needs no
authentication, leave them out.

Exit codes distinguish the two ways a run ends badly, and CI should too:

| Code | Meaning |
|---|---|
| 0 | the run completed and the target conformed: no MUST test failed or ended cantTell |
| 1 | the target is **non-conformant**: a MUST test failed or ended cantTell |
| 2 | **no verdict**. The harness is misconfigured (unknown target, missing registry, invalid definitions, a requirement the catalog does not hold), or the server could not be reached or would not create the run root. The reason is on standard error, and no report is written. |

Collapsing 1 and 2 tells a server implementer their server failed when the workflow was
wrong; the bundled Action keeps them apart.

Targets are always referenced by **id**; the URL only ever comes from the registry file
you provide (DESIGN.md §7.1).

## GitHub Action

The composite action at `.github/actions/lws-conformance` builds the image, runs the
harness against a URL you pass, uploads the report artifact, and fails the job on
non-conformance. A server-implementation repo consumes it with the workflow in
[`example-conformance-workflow.yml`](ci/example-conformance-workflow.yml) — copy it to
`.github/workflows/conformance.yml`, point it at your server, done.

| Input | Default | Meaning |
|---|---|---|
| `target-url` | (required) | Base URL of the running server under test |
| `module` | `all` | What to run: `all`, a module (`core`, `auth`), a manifest (`core/containers`) or one test |
| `capabilities` | (none) | Comma-separated capabilities the target declares, such as `Authentication` |
| `fail-on-nonconformance` | `true` | Fail the job when a MUST test fails or ends cantTell |
| `report-path` | `touchstone-reports` | Workspace-relative output directory |

## Authentication in CI

A job that declares no capabilities runs every definition that needs none, and reports the
authentication tests as inapplicable. To test a protected server, give the step alice's
and bob's tokens as secrets in `TOUCHSTONE_TOKEN_ALICE` and `TOUCHSTONE_TOKEN_BOB`, and
`capabilities: Authentication`:

```yaml
      - uses: ebremer/touchstone/.github/actions/lws-conformance@master
        env:
          TOUCHSTONE_TOKEN_ALICE: ${{ secrets.LWS_TOKEN_ALICE }}
          TOUCHSTONE_TOKEN_BOB: ${{ secrets.LWS_TOKEN_BOB }}
        with:
          target-url: http://localhost:3000/
          capabilities: Authentication
```

The action passes the two variables to the harness by name, so their values never appear
in the log. The fault tests that need the authorization server's signing key
(`HarnessIssuedTokens`) and the suites that need the server to reach the harness or trust
its identity provider apply only to a deployment arranged for them; the harness's own
self-test loop exercises them against the reference deployment. See
[Authentication](auth.md#against-a-third-party-server).
