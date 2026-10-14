#!/bin/sh
# Writes Halcyon's settings.ttl and lws-oidc.json from the environment (../README.md), then starts it.
#
#   LWS_PUBLIC_URL            the public base URL, e.g. https://host.example/targets/halcyon. Every URI Halcyon
#                             mints starts with it (:ProxyHostName), the proxy strips its path, and it is the
#                             authorization server's issuer; the storage is LWS_PUBLIC_URL/lws/.
#   LWS_OWNER                 the WebID given full control of the storage. Seeded into the root access control
#                             resource on the storage's first start only.
#   HALCYON_ALLOWED_ISSUERS   OpenID Providers whose ID Tokens are accepted, whole issuer URIs, space separated.
#                             Empty: any provider a WebID's document names.
#   HALCYON_OIDC              "off" disables WebID-OIDC credentials (lws-oidc.json); on by default.
#   JAVA_OPTS                 JVM options (default -Xmx4g).
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/halcyon}"
: "${LWS_OWNER:?LWS_OWNER must be the WebID that owns the storage}"
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
  printf '%s\n' '{ "enabled": true, "allowedInternalHosts": [] }' > lws-oidc.json
fi
mkdir -p /data/Storage /data/tmp

# shellcheck disable=SC2086
exec java $JAVA_OPTS -Djava.io.tmpdir=/data/tmp -jar /opt/halcyon/Halcyon.jar
