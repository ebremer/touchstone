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

docker run --rm -v "$PWD/work:/work" touchstone-harness \
  run --target sut --module core \
      --targets /work/targets.yaml --report-dir /work/runs \
      --catalog catalog --manifests manifests
```

Reports land in `work/runs/<runId>/` as `run.json`, `earl.ttl`, `junit.xml`, and
`report.html`. The process exits non-zero when the run is non-conformant, so it gates CI.

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
