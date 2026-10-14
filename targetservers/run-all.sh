#!/usr/bin/env bash
# Run Touchstone against every target server on this machine, with nothing but Docker (README.md, "Testing them all").
#
#   targetservers/run-all.sh                    build the harness and the servers, start them, test them all, stop them
#   targetservers/run-all.sh css lwsd           only these targets
#
#   --module <m>     pass --module <m> to touchstone run (default: everything)
#   --keep           leave the servers running afterwards (the router is on a random loopback port; see the end)
#   --no-build       use the images already built
#   --no-cache       build every image from scratch, as on a machine that never built them
#   --project <p>    the compose project, network and image prefix (default lws-local), so that this set can run
#                    beside another, a deployment's included
#
# DOCKER overrides the docker command (for example DOCKER="sudo docker").
#
# The servers run in local mode (compose.local.yaml): they are at http://router:8080/targets/<name>/ on the project's
# network, and the harness runs there too. Its fixture host is the service "fixtures", in front of the harness:
# https://fixtures/<name>/ (a certificate from a CA made for the run, which the servers that use it trust) or
# http://fixtures:18080/<name>/, as each target's touchstone.env says. The servers are told to fetch from that
# private address, which they refuse by default. Keys made for the run (alice's, bob's, the SAML identity provider's,
# the CA's) stay in .local/<project>/; reports, the registry, a summary and a requirements matrix go in
# runs/<UTC time>/.
#
# Exit status: 0 when every target ran and is conformant, 1 when every target ran and one is not, 2 when a target
# could not be run.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
DOCKER=${DOCKER:-docker}
PROJECT=lws-local
MODULE=()
KEEP=0
BUILD=1
NOCACHE=()
TARGETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --module) MODULE=(--module "$2"); shift 2 ;;
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
HTTP_FIXTURES=http://fixtures:18080
NODE_IMAGE=node:24.21.0-trixie-slim
STATE=$HERE/.local/$PROJECT
OUT=$HERE/runs/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$STATE" "$OUT/reports"
log() { printf '\n== %s\n' "$*"; }
compose() {
  $DOCKER compose -p "$PROJECT" -f "$HERE/compose.yaml" -f "$HERE/compose.local.yaml" --env-file "$STATE/local.env" "$@"
}
# The helper (tools/lws.mjs) in a Node.js container, on the project's network once it exists.
tool() {
  local net=none
  $DOCKER network inspect "$PROJECT" >/dev/null 2>&1 && net=$PROJECT
  $DOCKER run --rm -i --network "$net" --user "$(id -u):$(id -g)" -v "$HERE/tools:/tools:ro" -v "$STATE:/state" \
    "$NODE_IMAGE" node /tools/lws.mjs "$@"
}

log "keys and settings ($STATE)"
tool keys /state
ALICE_DID=$(tool did /state/alice.jwk)
if [ ! -s "$STATE/tls/fixtures.pem" ]; then   # the run's CA, and its certificate for the name "fixtures"
  mkdir -p "$STATE/tls"
  $DOCKER run --rm --network none --user "$(id -u):$(id -g)" -v "$STATE/tls:/tls" -w /tls python:3.13-slim sh -ec '
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca.key -out ca.pem -days 3650 \
      -subj "/CN=run-all.sh fixture CA" -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign 2>/dev/null
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout fixtures.key -out fixtures.csr -subj /CN=fixtures 2>/dev/null
    printf "subjectAltName=DNS:fixtures\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\n" > ext
    openssl x509 -req -in fixtures.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out fixtures.pem -days 825 -extfile ext 2>/dev/null
    rm -f fixtures.csr ext ca.srl'
fi
fixtures_of() { ( TARGET=$1 TS_FIXTURES=; source "$HERE/$1/touchstone.env"; echo "${TS_FIXTURES:-$HTTP_FIXTURES}/$1" ); }
{
  echo "# Written by run-all.sh: the settings of compose.yaml and compose.local.yaml for project $PROJECT."
  echo "LWS_ORIGIN=$ORIGIN"
  echo "LWS_LOCAL_STATE=$STATE"
  echo "LWS_TARGETS_BIND=127.0.0.1:"
  echo "LWS_TARGETS_NETWORK=$PROJECT"
  echo "LWS_TARGETS_IMAGE_PREFIX=$PROJECT"
  echo "LWS_REQUIRE_HTTPS=false"
  echo "LWS_DRUPAL_DB_PASSWORD=$(cat "$STATE/db-password")"
  echo "SPARQ_LWS_SAML_IDPS='$(tool samlidps "$(fixtures_of sparq-lws)/idp" /state/saml-idp.pem)'"
  for t in "${ALL[@]}"; do
    ( TARGET=$t TS_AUTH=; source "$HERE/$t/touchstone.env"
      owner=$([ "$TS_AUTH" = didkey ] && echo "$ALICE_DID" || echo "$(fixtures_of "$t")/agents/alice")
      echo "$(echo "$t" | tr 'a-z-' 'A-Z_')_OWNER=$owner" )
  done
} > "$STATE/local.env"

if [ "$BUILD" = 1 ]; then
  log "building the harness ($PROJECT/touchstone) and the servers"
  $DOCKER build -q "${NOCACHE[@]}" -t "$PROJECT/touchstone" "$REPO" >/dev/null
  compose build -q "${NOCACHE[@]}" router "${TARGETS[@]}"
fi

log "starting ${TARGETS[*]}"
compose down -v --remove-orphans >/dev/null 2>&1 || true
compose up -d router fixtures "${TARGETS[@]}"
for t in "${TARGETS[@]}"; do tool wait "$ORIGIN/targets/$t/" 900; done

log "registry ($OUT/targets.yaml)"
echo "targets:" > "$OUT/targets.yaml"
chmod 600 "$OUT/targets.yaml"   # it holds the servers' signing keys
for t in "${TARGETS[@]}"; do
  TS_ISSUER= TS_STORAGE= TS_BASE= TS_WARMUP= TS_SAML=no TS_KEY_CMD=() TS_KEY_FORMAT=jwk TS_PROPERTIES=() TS_FIXTURES=
  TARGET=$t; source "$HERE/$t/touchstone.env"
  url=$ORIGIN/targets/$t
  base=$url/$TS_BASE
  fx=$(fixtures_of "$t")
  caps="$TS_CAPABILITIES, ReachableFixtures"
  props=("fixtures.baseUrl: '$fx/'" "fixtures.bind: '0.0.0.0:18080'")
  if [ "$TS_AUTH" = didkey ]; then
    props+=("didkey.jwk.alice: '$(cat "$STATE/alice.jwk")'" "didkey.jwk.bob: '$(cat "$STATE/bob.jwk")'")
    token=$(tool exchange /state/alice.jwk "$url$TS_ISSUER" "$url/$TS_STORAGE")
  else
    [ -z "$TS_WARMUP" ] || tool wait "$url/$TS_WARMUP" 60 >/dev/null
    key=$(compose exec -T "$t" "${TS_KEY_CMD[@]}" | tool key "$TS_KEY_FORMAT")
    props+=("as.signingKey: '$key'" "webid.alice: '$fx/agents/alice'" "webid.bob: '$fx/agents/bob'")
    token=$(tool mint "$key" "$url$TS_ISSUER" "$url/$TS_STORAGE" "$fx/agents/alice" "$fx/")
  fi
  if [ "$TS_SAML" = yes ]; then
    caps="$caps, SamlTrust"
    props+=("saml.idpKey: '$(cat "$STATE/saml-idp.jwk")'")
  fi
  props+=("${TS_PROPERTIES[@]}")
  tool ensure "$token" "$base"
  {
    echo "  $t:"
    echo "    baseUrl: '$base'"
    echo "    adapter: env"
    echo "    capabilities: [$caps]"
    echo "    properties:"
    for p in "${props[@]}"; do echo "      $p"; done
  } >> "$OUT/targets.yaml"
done

# The harness reads its own fixture host back (the webhook sink), over HTTPS for some targets: it trusts the run's
# CA too, through a copy of its JDK's trust store with the CA added.
if [ ! -s "$STATE/tls/harness-truststore" ]; then
  $DOCKER run --rm --network none --user "$(id -u):$(id -g)" -v "$STATE/tls:/tls" --entrypoint sh "$PROJECT/touchstone" -c '
    cp "$JAVA_HOME/lib/security/cacerts" /tls/harness-truststore && chmod 600 /tls/harness-truststore &&
    keytool -importcert -noprompt -alias run-ca -file /tls/ca.pem -keystore /tls/harness-truststore -storepass changeit >/dev/null'
fi

status=0
for t in "${TARGETS[@]}"; do
  log "Touchstone against $t"
  rc=0
  $DOCKER run --rm --network "$PROJECT" --network-alias harness --name "$PROJECT-touchstone" \
    --user "$(id -u):$(id -g)" -v "$OUT:/work" -v "$STATE/tls:/tls:ro" \
    -e JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tls/harness-truststore -Djavax.net.ssl.trustStorePassword=changeit" \
    "$PROJECT/touchstone" \
    run --target "$t" --targets /work/targets.yaml --report-dir /work/reports "${MODULE[@]}" > "$OUT/$t.log" 2>&1 || rc=$?
  grep -E 'passed, |conformant' "$OUT/$t.log" | tail -2 || true
  case $rc in 0) ;; 1) [ $status = 2 ] || status=1 ;; *) status=2; echo "no verdict for $t (exit $rc): see $OUT/$t.log" ;; esac
done

log "summary ($OUT/summary.md)"
{
  echo "# Touchstone against the target servers, $(basename "$OUT")"
  echo
  echo "Touchstone $(git -C "$REPO" describe --always --dirty 2>/dev/null || echo '(not a git checkout)'), local mode."
  echo
  $DOCKER run --rm --network none --user "$(id -u):$(id -g)" -v "$HERE/tools:/tools:ro" -v "$OUT:/out" "$NODE_IMAGE" \
    node /tools/lws.mjs summary /out/reports
} > "$OUT/summary.md"
cat "$OUT/summary.md"

# The requirements matrix, one column per target, as the published conformance page has it.
args=()
for dir in "$OUT"/reports/*/; do
  t=$(sed -n 's/.*"targetId" *: *"\([^"]*\)".*/\1/p' "$dir/run.json" | head -1)
  [ -n "$t" ] || continue
  name=$( (TARGET=$t; source "$HERE/$t/touchstone.env"; echo "$TS_NAME") )
  lang=$( (TARGET=$t; source "$HERE/$t/touchstone.env"; echo "$TS_LANGUAGE") )
  labels() { $DOCKER image inspect -f "{{index .Config.Labels \"org.opencontainers.image.$1\"}}" "$PROJECT/$t" 2>/dev/null; }
  version="$(labels revision | cut -c1-7)|$(labels source)"
  args+=(--server "$name" "/out/reports/$(basename "$dir")" "$version" "$lang")
done
if [ ${#args[@]} -gt 0 ]; then
  $DOCKER run --rm --network none --user "$(id -u):$(id -g)" -v "$HERE/tools:/tools:ro" -v "$OUT:/out" python:3.13-slim \
    python3 /tools/matrix.py "${args[@]}" > "$OUT/matrix.html" && echo "matrix: $OUT/matrix.html"
fi

if [ "$KEEP" = 1 ]; then
  echo "the servers keep running: $(compose port router 8080 | sed 's/^/router at http:\/\//')/targets/<name>/ (inside the"
  echo "network they are http://router:8080/...); stop them with: $DOCKER compose -p $PROJECT down -v"
else
  compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
echo "results: $OUT"
exit $status
