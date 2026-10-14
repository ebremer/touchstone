# syntax=docker/dockerfile:1
# lwsd (github.com/linkedwebstorage/lwsd, the LWS daemon: JavaScriptSolidServer with lwsd's lws plugin) as a target
# server: one storage, with lwsd's embedded authorization server. lwsd mounts its storage at a path prefix and builds
# every URL as <scheme>://<Host><prefix>, so it is served with its whole public path (the router's mode "whole"): the
# prefix is the path of LWS_PUBLIC_URL, and it answers its RFC 8414 metadata at /.well-known/lws-configuration<prefix>
# itself. See ../README.md for the contract and entrypoint.sh for the settings.
#
# The source is the repository at LWSD_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/lwsd targetservers/lwsd

ARG LWSD_REPO=https://github.com/linkedwebstorage/lwsd.git
ARG LWSD_REF=dd1871ba1ac7e5db3380b5b55bd28aaac4fb46f5

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG LWSD_REPO
ARG LWSD_REF
RUN git init -q /src && git -C /src remote add origin "$LWSD_REPO" \
 && git -C /src fetch -q --depth 1 origin "$LWSD_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- runtime ----
# lwsd has no lock file; its one runtime dependency, javascript-solid-server, is pinned to a narrow range in its
# package.json.
FROM node:24.21.0-trixie-slim
ARG LWSD_REF
LABEL org.opencontainers.image.title="lwsd (LWS target server)" \
      org.opencontainers.image.source="https://github.com/linkedwebstorage/lwsd" \
      org.opencontainers.image.revision="${LWSD_REF}"
WORKDIR /opt/lwsd
COPY --from=source / .
RUN --mount=type=cache,target=/root/.npm npm install --omit=dev --no-audit --no-fund \
 && mkdir -p /data && chown node:node /data
COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
# /data/as-key.json is the authorization server's signing key (made at the first start); /data/jss is the server's
# data directory, the storage's resources and the plugin's state in it.
VOLUME /data
USER node
ENV NODE_ENV=production
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
