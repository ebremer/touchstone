#!/bin/sh
# Starts sparq-lws-core in its LWS 1.0 mode, configured from the environment (../README.md).
#
#   LWS_PUBLIC_URL          the public base URL, e.g. https://host.example/targets/sparq-lws: resource IRIs are it
#                           plus the request path (the proxy strips its path), the storage is it plus "/", and it is
#                           the authorization server's issuer.
#   LWS_OWNER               the agent that owns the storage.
#   LWS_PAGE_SIZE           members per page of a container listing (default 4, so that small containers span pages).
#   SPARQ_LWS_SAML_IDPS     optional JSON object of trusted SAML identity providers: entity ID -> PEM public key.
#   Any other SOLID_SERVER_* variable passes through to the server as it is.
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/sparq-lws}"
: "${LWS_OWNER:?LWS_OWNER must name the agent that owns the storage}"
export SOLID_SERVER_BIND=0.0.0.0:8080
export SOLID_SERVER_BASE_URL="${LWS_PUBLIC_URL%/}"
export SOLID_SERVER_PROTOCOL=lws
export SOLID_SERVER_LWS_OWNER="$LWS_OWNER"
export SOLID_SERVER_LWS_PAGE_SIZE="${LWS_PAGE_SIZE:-4}"
export SOLID_SERVER_LWS_AS_KEY_FILE=/data/as-key.json
export SOLID_SERVER_LWS_NOTIFY_KEY_FILE=/data/notify-key.json
# The router passes the client's address on in X-Forwarded-For: one trusted hop, for the per-IP rate limit.
export SOLID_SERVER_TRUSTED_PROXY="${SOLID_SERVER_TRUSTED_PROXY:-1}"
if [ -n "${SPARQ_LWS_SAML_IDPS:-}" ]; then
  printf '%s\n' "$SPARQ_LWS_SAML_IDPS" > /data/saml-idps.json
  export SOLID_SERVER_LWS_SAML_IDPS_FILE=/data/saml-idps.json
fi
exec /usr/local/bin/sparq-lws-core
