#!/bin/sh
# Starts the lws-client driver (driver/README.md) on port 18095, MCP at /mcp.
#
#   LWS_DRIVER_TOKEN            required: the bearer token /mcp requires
#   LWS_DRIVER_ALLOWED_TARGETS  URL prefixes the clients may be pointed at, comma separated (default: any)
#   LWS_DRIVER_PUBLIC_BASE_URL  the driver's URL as the storages reach it, for the clients' webhook inboxes
#                               (default http://clients:18095, its name on a compose network)
#   LWS_DRIVER_JAVA_OPTIONS     options for the driver's JVM (default -Xmx512m)
set -eu
: "${LWS_DRIVER_TOKEN:?LWS_DRIVER_TOKEN must be set: whoever reaches /mcp drives the clients}"
set -- --lws.driver.public-base-url="${LWS_DRIVER_PUBLIC_BASE_URL:-http://clients:18095}" "$@"
if [ -n "${LWS_DRIVER_ALLOWED_TARGETS:-}" ]; then
  set -- --lws.driver.allowed-targets="$LWS_DRIVER_ALLOWED_TARGETS" "$@"
fi
# shellcheck disable=SC2086
exec java ${LWS_DRIVER_JAVA_OPTIONS:--Xmx512m} -jar /opt/lws-client/driver/lws-client-driver.jar "$@"
