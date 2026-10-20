# syntax=docker/dockerfile:1
# lws-server (github.com/ebremer/lws-server) as a target server: its storage at the root, with its embedded
# authorization server. See ../README.md for the contract and entrypoint.sh for the settings.
#
# The source is the repository at LWS_SERVER_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/lws-server targetservers/lws-server

ARG LWS_SERVER_REPO=https://github.com/ebremer/lws-server.git
ARG LWS_SERVER_REF=2bee2c6291052dc428bc525068afc734c4a5956f

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG LWS_SERVER_REPO
ARG LWS_SERVER_REF
RUN git init -q /src && git -C /src remote add origin "$LWS_SERVER_REPO" \
 && git -C /src fetch -q --depth 1 origin "$LWS_SERVER_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- build ----
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package && cp target/lws-server.jar /lws-server.jar

# ---- runtime ----
FROM eclipse-temurin:25-jre
ARG LWS_SERVER_REF
LABEL org.opencontainers.image.title="lws-server (LWS target server)" \
      org.opencontainers.image.source="https://github.com/ebremer/lws-server" \
      org.opencontainers.image.revision="${LWS_SERVER_REF}"
RUN groupadd --system --gid 10001 lws \
 && useradd --system --uid 10001 --gid lws --home-dir /data --no-create-home lws \
 && mkdir -p /data && chown lws:lws /data
COPY --from=build /lws-server.jar /opt/lws-server/lws-server.jar
COPY --chmod=755 entrypoint.sh /opt/lws-server/entrypoint.sh
# lws-server reads ./lws.properties; its data directory (stores, content, and keys/oauth-es256.jwk, the
# authorization server's signing key) is /data/lws-data.
WORKDIR /data
VOLUME /data
USER lws
ENV JAVA_OPTS="-Xmx1g -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["/opt/lws-server/entrypoint.sh"]
