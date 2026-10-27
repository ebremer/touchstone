#!/bin/sh
# Starts Factoidal's LWS server with its authorization server on port 8080, one process (`factoidal lws-serve --authz`),
# from the environment (../README.md). Which requests are the authorization server's is decided by Factoidal's engine;
# this script only turns the settings into command-line options.
#
#   LWS_PUBLIC_URL   the public base URL, e.g. https://host.example/targets/factoidal. The storage is LWS_PUBLIC_URL/
#                    and the authorization server's issuer is LWS_PUBLIC_URL; the server reads every request path with
#                    or without the URL's path.
#   LWS_OWNER        the agent (CID URL or WebID) with full control of the storage.
#   LWS_PAGE_SIZE    members per page of a container listing (default 4, so that small containers span pages).
#   LWS_STORAGE      files (default) or snapshot.
#   LWS_ALLOW_PRIVATE_FETCH=1
#                    the authorization server may fetch identity documents over http and from private addresses (a
#                    test network only: compose.local.yaml).
#   FACTOIDAL_LWS_KEY
#                    if set, the storage and the signing keys are sealed at rest.
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/factoidal}"
: "${LWS_OWNER:?LWS_OWNER must name the agent that owns the storage}"
set -- /data/storage --host 0.0.0.0 --port 8080 --base "${LWS_PUBLIC_URL%/}/" \
  --page-size "${LWS_PAGE_SIZE:-4}" --storage "${LWS_STORAGE:-files}" \
  --authz /data/as --owner "$LWS_OWNER"
[ "${LWS_ALLOW_PRIVATE_FETCH:-0}" = 1 ] && set -- "$@" --allow-http --allow-private-fetch
mkdir -p /data/storage
exec factoidal lws-serve "$@"
