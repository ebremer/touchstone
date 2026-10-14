# syntax=docker/dockerfile:1
# The Community Solid Server with Linked Web Storage (jeswr/CommunitySolidServer PR #126, on CSS's next major) as a
# target server: one pod, "touchstone", with the LWS authorization server. Unlike the other targets it is served with
# its public path kept (the router's keep list): CSS matches request paths against its base URL. See ../README.md for
# the contract and entrypoint.sh for the settings.
#
# The source is the repository at CSS_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/CommunitySolidServer targetservers/css

ARG CSS_REPO=https://github.com/jeswr/CommunitySolidServer.git
ARG CSS_REF=311b3677154256eb2f87add7dad50f1255cf50a3

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG CSS_REPO
ARG CSS_REF
RUN git init -q /src && git -C /src remote add origin "$CSS_REPO" \
 && git -C /src fetch -q --depth 1 origin "$CSS_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- build ----
# npm ci runs the package's prepare script: TypeScript and the Components.js generator.
FROM node:24.21.0-trixie-slim AS build
WORKDIR /community-server
COPY --from=source / .
RUN --mount=type=cache,target=/root/.npm npm ci --unsafe-perm

# ---- runtime ----
FROM node:24.21.0-trixie-slim
ARG CSS_REF
LABEL org.opencontainers.image.title="Community Solid Server with LWS (LWS target server)" \
      org.opencontainers.image.source="https://github.com/jeswr/CommunitySolidServer/pull/126" \
      org.opencontainers.image.revision="${CSS_REF}"
WORKDIR /community-server
COPY --from=build /community-server/package.json .
COPY --from=build /community-server/bin ./bin
COPY --from=build /community-server/config ./config
COPY --from=build /community-server/dist ./dist
COPY --from=build /community-server/node_modules ./node_modules
COPY --from=build /community-server/templates ./templates
COPY lws-targets.json ./lws-targets.json
COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
# The entrypoint adds the storage's owner to the pod's root ACL template before the pod is made, so the
# template is the runtime user's; the pristine copy stays beside it.
RUN cp templates/pod/wac/.acl.hbs templates/pod/wac/.acl.hbs.orig \
 && chown node:node templates/pod/wac/.acl.hbs \
 && mkdir -p /data && chown node:node /data
# /data/files holds the pods and CSS's internal data (accounts, and .internal/lws/keys/lws-jwks$.json, the LWS
# authorization server's signing key).
VOLUME /data
USER node
ENV NODE_ENV=production
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
