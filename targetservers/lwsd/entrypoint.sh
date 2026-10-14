#!/bin/sh
# Starts lwsd (JavaScriptSolidServer with the lws plugin) from the environment (../README.md), configured as lwsd's
# own Touchstone runner configures it for an authenticated run.
#
#   LWS_PUBLIC_URL   the public base URL, e.g. https://host.example/targets/lwsd. Its path is the plugin's mount prefix:
#                    the storage is LWS_PUBLIC_URL/ and the authorization server's issuer is LWS_PUBLIC_URL. Scheme and
#                    host come from each request (Host, X-Forwarded-Proto: JSS trusts the proxy).
#   LWS_OWNER        the storage's writers (LWS_WRITERS): agents with full control, comma or space separated.
#   LWS_PAGE_SIZE    members per page of a container listing (default 4, so that small containers span pages).
#   Any other LWS_* variable lwsd reads (LWS_PUBLIC_READ, LWS_ACCEPT_CREDENTIALS, LWS_AS_TOKEN_LIFETIME, ...) passes
#   through.
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/lwsd}"
: "${LWS_OWNER:?LWS_OWNER must name the agent that owns the storage}"
prefix=$(node -e 'console.log(new URL(process.argv[1]).pathname.replace(/\/+$/, ""))' "$LWS_PUBLIC_URL")
[ -n "$prefix" ] || { echo "LWS_PUBLIC_URL needs a path: lwsd mounts its storage below the server's root" >&2; exit 1; }

# The authorization server's key, made once and kept, so that it can be read (HarnessIssuedTokens) at a known path.
if [ ! -s /data/as-key.json ]; then
  umask 077
  node -e '
    const { generateKeyPairSync, createHash } = require("crypto");
    const k = generateKeyPairSync("ec", { namedCurve: "P-256" }).privateKey.export({ format: "jwk" });
    k.kid = createHash("sha256").update(JSON.stringify({ crv: k.crv, kty: k.kty, x: k.x, y: k.y })).digest("base64url");
    k.alg = "ES256";
    process.stdout.write(JSON.stringify(k));' > /data/as-key.json
  umask 022
fi
mkdir -p /data/jss

export LWS_WRITERS="$(echo "$LWS_OWNER" | tr ' ' ',')"
export LWS_PUBLIC_READ="${LWS_PUBLIC_READ:-0}"
export LWS_AS_KEY_JWK=/data/as-key.json
export LWS_PAGE_SIZE="${LWS_PAGE_SIZE:-4}"
# JSS's own access control is off (--public): the lws plugin decides every request itself.
exec /opt/lwsd/node_modules/.bin/jss start --port 8080 --host 0.0.0.0 --root /data/jss --public --no-git --no-idp \
  --plugin "/opt/lwsd/lws/plugin.js@$prefix"
