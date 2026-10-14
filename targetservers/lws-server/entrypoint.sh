#!/bin/sh
# Writes lws-server's lws.properties from the environment (../README.md), then starts it.
#
#   LWS_PUBLIC_URL      the public base URL, e.g. https://host.example/targets/lws-server: the storage, and the
#                       authorization server's issuer. The proxy strips its path.
#   LWS_OWNER           the storage's owners: WebIDs and did:keys, space separated (lws.owners).
#   LWS_PAGE_SIZE       members per page of a container listing and of a type search (default 4, so that small
#                       containers span pages, as conformance tests of paging need).
#   LWS_REQUIRE_HTTPS   "false" for a plain-HTTP public URL (default true).
#   LWS_PROPERTIES      further lws.properties lines, appended as they are.
#   LWS_EXTRA_CA        a PEM file of certificates to trust besides the JDK's (a private test CA).
#   JAVA_OPTS           JVM options.
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/lws-server}"
: "${LWS_OWNER:?LWS_OWNER must name the storage owners (WebIDs or did:keys)}"
page=${LWS_PAGE_SIZE:-4}
cat > lws.properties <<EOF
# Written by the container's entrypoint at every start, from its environment; edits here are lost.
lws.base-uri=${LWS_PUBLIC_URL%/}
lws.listen-port=8080
lws.behind-proxy=true
lws.require-https=${LWS_REQUIRE_HTTPS:-true}
lws.data-dir=/data/lws-data
lws.owners=$LWS_OWNER
lws.container.page-size=$page
lws.search-index.page-size=$page
${LWS_PROPERTIES:-}
EOF
# LWS_EXTRA_CA: certificates (PEM) to trust besides the JDK's own, e.g. a private test CA. The JDK's store is copied,
# the certificates added, and the copy made the default trust store.
if [ -n "${LWS_EXTRA_CA:-}" ]; then
  cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts && chmod 600 /tmp/cacerts
  keytool -importcert -noprompt -alias lws-extra-ca -file "$LWS_EXTRA_CA" -keystore /tmp/cacerts -storepass changeit >/dev/null
  JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /opt/lws-server/lws-server.jar
