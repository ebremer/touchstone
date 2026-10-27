#!/usr/bin/env bash
# Every client against every server, on this machine, with nothing but Docker (README.md, "Every client against every
# server"): builds the target servers and the lws-client driver with the adapters of its eleven clients (clients/),
# starts them on a network of their own, and drives each client through one scenario against each server
# (tools/interop.mjs).
#
#   targetservers/interop-all.sh                    every client against every target
#   targetservers/interop-all.sh css lwsd           against these targets only
#
#   --languages <a,b>  only these clients: java, js, python, go, rust, cpp, csharp, swift, php, kotlin, wasm
#   --keep           leave the servers and the clients running afterwards
#   --no-build       use the images already built
#   --no-cache       build every image from scratch, as on a machine that never built them
#   --project <p>    the compose project, network and image prefix (default lws-local-interop)
#
# DOCKER overrides the docker command (for example DOCKER="sudo docker").
#
# Every storage belongs to one agent made for the run, whose controlled identifier document the service "fixtures"
# serves at https://fixtures/interop/agent or http://fixtures:18080/interop/agent, as each target's touchstone.env
# says (TS_FIXTURES, as for run-all.sh); its key stays in .local/<project>/. Each client signs in as that agent with a
# self-signed credential, makes a container of its own in the target's TS_BASE, works through the scenario there and
# deletes it. The results go in runs/<UTC time>-interop/: results.json, summary.md and matrix.html (every client
# against every server, and each step of each).
#
# Exit status: 0 when every combination works, 1 when one does not, 2 when the run could not be made.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
DOCKER=${DOCKER:-docker}
PROJECT=lws-local-interop
KEEP=0
BUILD=1
NOCACHE=()
LANGS=()
TARGETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --languages) LANGS=(--languages "$2"); shift 2 ;;
    --keep) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    --no-cache) NOCACHE=(--no-cache --pull); shift ;;
    --project) PROJECT=$2; shift 2 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) TARGETS+=("$1"); shift ;;
  esac
done
ALL=()
for f in "$HERE"/*/touchstone.env; do ALL+=("$(basename "$(dirname "$f")")"); done
[ ${#TARGETS[@]} -gt 0 ] || TARGETS=("${ALL[@]}")
for t in "${TARGETS[@]}"; do
  [ -f "$HERE/$t/touchstone.env" ] || { echo "no target $t (known: ${ALL[*]})" >&2; exit 2; }
done

ORIGIN=http://router:8080
NODE_IMAGE=node:24.21.0-trixie-slim
STATE=$HERE/.local/$PROJECT
OUT=$HERE/runs/$(date -u +%Y%m%dT%H%M%SZ)-interop
mkdir -p "$STATE/interop" "$OUT"
log() { printf '\n== %s\n' "$*"; }
compose() {
  $DOCKER compose -p "$PROJECT" -f "$HERE/compose.yaml" -f "$HERE/compose.local.yaml" -f "$HERE/compose.interop.yaml" \
    --env-file "$STATE/local.env" "$@"
}
tool() {
  local net=none
  $DOCKER network inspect "$PROJECT" >/dev/null 2>&1 && net=$PROJECT
  $DOCKER run --rm -i --network "$net" --user "$(id -u):$(id -g)" -v "$HERE/tools:/tools:ro" -v "$STATE:/state" \
    "$NODE_IMAGE" node /tools/lws.mjs "$@"
}
# The agent's URL for a target: its fixture host's scheme, as the target's touchstone.env gives it.
agent_of() { ( TARGET=$1 TS_FIXTURES=; source "$HERE/$1/touchstone.env"; echo "${TS_FIXTURES:-http://fixtures:18080}/interop/agent" ); }
# A target's URL: behind the router, unless it is tested at an origin of its own (TS_URL).
url_of() { ( TARGET=$1 TS_URL=; source "$HERE/$1/touchstone.env"; echo "${TS_URL:-$ORIGIN/targets/$1}" ); }

log "keys and settings ($STATE)"
tool keys /state
for url in https://fixtures/interop/agent http://fixtures:18080/interop/agent; do
  tool cid /state/alice.jwk "$url" > "$STATE/interop/agent.${url%%:*}.json"
done
chmod 644 "$STATE"/interop/*.json
if [ ! -s "$STATE/tls/fixtures.pem" ]; then   # the run's CA, and its certificate for the name "fixtures"
  mkdir -p "$STATE/tls"
  $DOCKER run --rm --network none --user "$(id -u):$(id -g)" -v "$STATE/tls:/tls" -w /tls python:3.13-slim sh -ec '
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca.key -out ca.pem -days 3650 \
      -subj "/CN=interop-all.sh fixture CA" -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign 2>/dev/null
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout fixtures.key -out fixtures.csr -subj /CN=fixtures 2>/dev/null
    printf "subjectAltName=DNS:fixtures\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\n" > ext
    openssl x509 -req -in fixtures.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out fixtures.pem -days 825 -extfile ext 2>/dev/null
    rm -f fixtures.csr ext ca.srl'
fi
[ -s "$STATE/driver-token" ] || (umask 077; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$STATE/driver-token")
{
  echo "# Written by interop-all.sh: the settings of compose.yaml, compose.local.yaml and compose.interop.yaml."
  echo "LWS_ORIGIN=$ORIGIN"
  echo "LWS_LOCAL_STATE=$STATE"
  echo "LWS_TARGETS_BIND=127.0.0.1:"
  echo "LWS_TARGETS_NETWORK=$PROJECT"
  echo "LWS_TARGETS_IMAGE_PREFIX=$PROJECT"
  echo "LWS_REQUIRE_HTTPS=false"
  echo "LWS_DRUPAL_DB_PASSWORD=$(cat "$STATE/db-password")"
  echo "LWS_DRIVER_TOKEN=$(cat "$STATE/driver-token")"
  for t in "${ALL[@]}"; do echo "$(echo "$t" | tr 'a-z-' 'A-Z_')_OWNER=$(agent_of "$t")"; done
} > "$STATE/local.env"
chmod 600 "$STATE/local.env"

if [ "$BUILD" = 1 ]; then
  log "building the servers and the clients (the first build of the clients takes a while: eleven toolchains)"
  compose build -q "${NOCACHE[@]}" router clients "${TARGETS[@]}"
fi

log "starting ${TARGETS[*]} and the clients"
compose down -v --remove-orphans >/dev/null 2>&1 || true
compose up -d router fixtures clients "${TARGETS[@]}"
for t in "${TARGETS[@]}"; do tool wait "$(url_of "$t")/" 900; done
tool wait http://clients:18095/mcp 300

log "the run's configuration"
labels() { $DOCKER image inspect -f "{{index .Config.Labels \"org.opencontainers.image.$2\"}}" "$PROJECT/$1" 2>/dev/null; }
json() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
{
  printf '{"driver": {"url": "http://clients:18095/mcp", "token": "%s"},\n' "$(cat "$STATE/driver-token")"
  printf ' "key": %s,\n' "$(cat "$STATE/alice.jwk")"
  printf ' "assignee": "%s",\n' "$(tool did /state/bob.jwk)"
  printf ' "insecure": true,\n'
  printf ' "lwsClient": {"version": "%s", "source": "%s"},\n' "$(labels clients revision | cut -c1-7)" "$(labels clients source)"
  printf ' "targets": ['
  sep=
  for t in "${TARGETS[@]}"; do
    TS_ISSUER= TS_STORAGE= TS_BASE=
    TARGET=$t; source "$HERE/$t/touchstone.env"
    url=$(url_of "$t")
    printf '%s\n  {"name": "%s", "label": "%s", "language": "%s", "version": "%s", "source": "%s",' "$sep" "$t" \
      "$(json "$TS_NAME")" "$(json "$TS_LANGUAGE")" "$(labels "$t" revision | cut -c1-7)" "$(labels "$t" source)"
    printf ' "base": "%s", "storage": "%s", "issuer": "%s", "agent": "%s"}' "$url/$TS_BASE" "$url/$TS_STORAGE" \
      "$url$TS_ISSUER" "$(agent_of "$t")"
    sep=,
  done
  printf '\n]}\n'
} > "$STATE/interop.json"
chmod 600 "$STATE/interop.json"

log "every client against ${TARGETS[*]}"
status=0
$DOCKER run --rm --network "$PROJECT" --user "$(id -u):$(id -g)" -v "$HERE/tools:/tools:ro" -v "$STATE:/state:ro" \
  -v "$OUT:/out" "$NODE_IMAGE" node /tools/interop.mjs --config /state/interop.json --out /out "${LANGS[@]}" \
  > "$OUT/interop.log" 2>&1 || status=$?
sed -n '/^# Every client/,$p' "$OUT/interop.log"
[ $status -le 1 ] || { echo "the run could not be made (exit $status): see $OUT/interop.log"; tail -5 "$OUT/interop.log"; }

if [ "$KEEP" = 1 ]; then
  echo "the servers and the clients keep running; stop them with: $DOCKER compose -p $PROJECT down -v"
else
  compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
echo "results: $OUT"
exit $status
