# syntax=docker/dockerfile:1
# Factoidal (github.com/danbri/factoidal-core: RDF and LWS in Lean 4, compiled to one wasm module run by Node.js) as a
# target server: `factoidal lws-serve --authz`, the LWS server and its authorization server in one process on one port.
# It reads both forms of every path, with and without the path of LWS_PUBLIC_URL, so the router strips the prefix (the
# default mode). See ../README.md for the contract and entrypoint.sh for the settings. Adapted from factoidal-core's own
# deploy/lws-target.
#
# The source is the published package @factoidal/core at FACTOIDAL_VERSION (the release v0.26.0 of factoidal-core,
# 7a58d95; `lws-serve --authz` is in 0.18.0 and later), checked against FACTOIDAL_INTEGRITY, the registry's
# dist.integrity. To build a local checkout instead (factoidal-core's npm/factoidal, after its wasm build):
#   docker build --build-context source=/path/to/factoidal-core/npm/factoidal targetservers/factoidal

ARG FACTOIDAL_VERSION=0.26.0
ARG FACTOIDAL_INTEGRITY=sha512-nhD4McJwZknfND6kB9ytokKm6iTqNMJ+gceuoaSrnkotc1OCagkuk7EFtEGlABxHC0pQDHrj6+iXoW21hqCxdg==

# ---- source: the package, unpacked ----
FROM node:24.21.0-trixie-slim AS fetch
ARG FACTOIDAL_VERSION
ARG FACTOIDAL_INTEGRITY
WORKDIR /fetch
RUN npm pack --silent "@factoidal/core@${FACTOIDAL_VERSION}" > name.txt \
 && node -e ' \
      const fs = require("fs"), crypto = require("crypto"); \
      const [alg, want] = process.argv[1].split("-"); \
      const got = crypto.createHash(alg).update(fs.readFileSync(fs.readFileSync("name.txt", "utf8").trim())).digest("base64"); \
      if (got !== want) { console.error("integrity mismatch: " + alg + "-" + got); process.exit(1) }' \
      "$FACTOIDAL_INTEGRITY" \
 && mkdir /src && tar -xzf "$(cat name.txt)" -C /src --strip-components=1

FROM scratch AS source
COPY --from=fetch /src/ /

# ---- runtime ----
# The package has no runtime dependencies: everything is in it, the wasm module included.
FROM node:24.21.0-trixie-slim
ARG FACTOIDAL_VERSION
LABEL org.opencontainers.image.title="Factoidal (LWS target server)" \
      org.opencontainers.image.source="https://github.com/danbri/factoidal-core" \
      org.opencontainers.image.revision="${FACTOIDAL_VERSION}"
COPY --from=source / /opt/factoidal/
RUN ln -s /opt/factoidal/bin/factoidal.mjs /usr/local/bin/factoidal \
 && mkdir -p /data && chown node:node /data
COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
# /data/storage is the storage; /data/as the authorization server's state, its signing keys among it (read them with
# `factoidal authz-keys /data/as --private`).
VOLUME /data
USER node
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
