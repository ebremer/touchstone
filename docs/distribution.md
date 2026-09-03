# Distribution

Touchstone ships as a Docker image and a reusable GitHub Action so a third-party LWS
server implementation gets a conformance report on every push by adding **one workflow
file**.

## Docker image

```
docker build -t touchstone-harness .
docker run --rm touchstone-harness --version
```

The image carries the shaded CLI plus the requirements catalog and test manifests. Run a
conformance report against a running server by mounting a work directory that holds the
target registry and receives the reports:

```
mkdir -p work
cat > work/targets.yaml <<'EOF'
targets:
  sut:
    baseUrl: http://host.docker.internal:3000/
    adapter: env
EOF

# The image runs as a non-root user; --user makes reports land owned by you rather than
# by the uid baked into the image.
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/work:/work" touchstone-harness \
  run --target sut --module core \
      --targets /work/targets.yaml --report-dir /work/runs \
      --catalog catalog --manifests manifests
```

Reports land in `work/runs/<timestamp>-<runId>/` as `run.json`, `earl.ttl`, `junit.xml`,
`report.html`, `report.json`, `report.md` and `report.pdf`.

Exit codes distinguish the two ways a run ends badly, and CI should too:

| Code | Meaning |
|---|---|
| 0 | the run completed and the target conformed |
| 1 | the target is **non-conformant** — a test failed or errored |
| 2 | the **harness** is misconfigured: unknown target, missing registry, or a manifest naming a requirement the catalog does not hold |

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
| `module` | `core` | Test module to run |
| `fail-on-nonconformance` | `true` | Fail the job on any failure or error |
| `report-path` | `touchstone-reports` | Workspace-relative output directory |

## Auth modules in CI

`--module auth-oidc` (and the other auth suites) need the harness to own the identity
provider and the server to trust it — a bundled reference scenario rather than a
third-party SUT. For an external server, run `--module core` in CI; auth conformance is
exercised by the harness's own self-test loop (DECISIONS.md D-0017).
