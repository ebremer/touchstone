#!/bin/sh
# Writes Halcyon's settings.ttl and lws-oidc.json from the environment (../README.md), then starts it.
#
#   LWS_PUBLIC_URL            the public base URL, e.g. https://host.example/targets/halcyon. Every URI Halcyon
#                             mints starts with it (:ProxyHostName), the proxy strips its path, and it is the
#                             authorization server's issuer; the storage is LWS_PUBLIC_URL/lws/.
#   LWS_OWNER                 the CID URL given full control of the storage. Seeded into the root access control
#                             resource on the storage's first start only.
#   HALCYON_ALLOWED_ISSUERS   OpenID Providers whose ID Tokens are accepted, whole issuer URIs, space separated.
#                             Empty: any provider a CID document names.
#   HALCYON_OIDC              "off" disables LWS OpenID Connect credentials (lws-oidc.json); on by default.
#   HALCYON_ALLOWED_INTERNAL_HOSTS
#                             hosts on private addresses that Halcyon may still fetch from (identity documents, an
#                             OpenID Provider), space separated: lws-oidc.json's allowedInternalHosts. Empty by default.
#   LWS_EXTRA_CA              a PEM file of certificates to trust besides the JDK's (a private test CA).
#   JAVA_OPTS                 JVM options (default -Xmx4g).
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/halcyon}"
: "${LWS_OWNER:?LWS_OWNER must be the CID URL that owns the storage}"
base=${LWS_PUBLIC_URL%/}

issuers=
for issuer in ${HALCYON_ALLOWED_ISSUERS:-}; do
  issuers="$issuers${issuers:+ , }<$issuer>"
done

{
  cat <<EOF
# Written by the container's entrypoint at every start, from its environment; edits here are lost.
PREFIX :    <https://halcyon.is/ns/>
PREFIX lws: <https://www.w3.org/ns/lws#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

<http://localhost>  a  :HalcyonSettingsFile ;
    :RDFStoreLocation  "tdb2" ;
    :HostName          "$base" ;
    :ProxyHostName     "$base" ;
    :HostIP            "0.0.0.0" ;
    :HTTPPort          "8080"^^xsd:int ;
    :HTTPSPort         "8443"^^xsd:int ;
    :HTTPS2enabled     false ;
    :SPARQLport        "8887"^^xsd:int ;
    :LWSStoreLocation  "lws-tdb2" ;
    :LWSOwner          <$LWS_OWNER> ;
EOF
  [ -n "$issuers" ] && printf '    :AllowedIssuer     %s ;\n' "$issuers"
  cat <<EOF
    :hasLWSStorage [ a lws:Storage ;
                     :urlPath "/lws" ;
                     :storageRoot <file:///data/Storage/> ;
                     :namingPolicy "slug" ] ;
    :LWSUserDataStorage "/lws" .
EOF
} > settings.ttl

if [ "${HALCYON_OIDC:-on}" = off ]; then
  rm -f lws-oidc.json
else
  hosts=
  for host in ${HALCYON_ALLOWED_INTERNAL_HOSTS:-}; do
    hosts="$hosts${hosts:+, }\"$host\""
  done
  printf '{ "enabled": true, "allowedInternalHosts": [%s] }\n' "$hosts" > lws-oidc.json
fi
mkdir -p /data/Storage /data/tmp

# LWS_EXTRA_CA: certificates (PEM) to trust besides the JDK's own, e.g. a private test CA. The JDK's store is copied,
# the certificates added, and the copy made the default trust store.
if [ -n "${LWS_EXTRA_CA:-}" ]; then
  cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts && chmod 600 /tmp/cacerts
  keytool -importcert -noprompt -alias lws-extra-ca -file "$LWS_EXTRA_CA" -keystore /tmp/cacerts -storepass changeit >/dev/null
  JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -Djava.io.tmpdir=/data/tmp -jar /opt/halcyon/Halcyon.jar
