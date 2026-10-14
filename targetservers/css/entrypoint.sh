#!/bin/sh
# Starts the Community Solid Server with LWS, configured from the environment (../README.md).
#
#   LWS_PUBLIC_URL   the public base URL, e.g. https://host.example/targets/css. CSS serves it with the path kept,
#                    and it is the authorization server's issuer (with a trailing slash). The storage is the pod
#                    LWS_PUBLIC_URL/<CSS_POD>/.
#   LWS_OWNER        an agent given Read, Write and Control over the whole pod, beside the pod's own account
#                    WebID. Written into the pod's root ACL when the pod is made, at the first start.
#   CSS_POD          the pod's name (default touchstone).
#   CSS_SEED_EMAIL   the email of the account that holds the pod (default touchstone@css.invalid). Its password is
#                    random, kept in /data/seed.json; nobody signs in to it (accounts are switched off).
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/css}"
: "${LWS_OWNER:?LWS_OWNER must name the agent that controls the pod}"
pod=${CSS_POD:-touchstone}
mkdir -p /data/files

# The pod's root ACL as CSS writes it, plus the owner.
{
  cat templates/pod/wac/.acl.hbs.orig
  cat <<EOF

# The storage's owner (LWS_OWNER), added by the container's entrypoint.
<#lws-owner>
    a acl:Authorization;
    acl:agent <$LWS_OWNER>;
    acl:accessTo <./>;
    acl:default <./>;
    acl:mode
        acl:Read, acl:Write, acl:Control.
EOF
} > templates/pod/wac/.acl.hbs

# The account and its pod, made at the first start only.
seed=
if [ ! -d "/data/files/$pod" ]; then
  if [ ! -f /data/seed.json ]; then
    umask 077
    password=$(node -e 'console.log(require("crypto").randomBytes(24).toString("base64url"))')
    printf '[{"email": "%s", "password": "%s", "pods": [{"name": "%s"}]}]\n' \
      "${CSS_SEED_EMAIL:-touchstone@css.invalid}" "$password" "$pod" > /data/seed.json
    umask 022
  fi
  seed="--seedConfig /data/seed.json"
fi

# shellcheck disable=SC2086
exec node bin/server.js --config lws-targets.json --mainModulePath /community-server \
  --rootFilePath /data/files --baseUrl "${LWS_PUBLIC_URL%/}/" --port 8080 $seed \
  --loggingLevel "${CSS_LOGGING_LEVEL:-info}" --workers 1
