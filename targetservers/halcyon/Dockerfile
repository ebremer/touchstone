# syntax=docker/dockerfile:1
# Halcyon (github.com/halcyon-project/Halcyon) as a target server: its LWS storage (module HalcyonLWS) at /lws,
# with its embedded authorization server. See ../README.md for the contract and entrypoint.sh for the settings.
#
# The source is the repository at HALCYON_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/Halcyon targetservers/halcyon

ARG HALCYON_REPO=https://github.com/halcyon-project/Halcyon.git
ARG HALCYON_REF=00f79d5b4288addb59c1d8ce0a4132a387cbb725

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG HALCYON_REPO
ARG HALCYON_REF
RUN git init -q /src && git -C /src remote add origin "$HALCYON_REPO" \
 && git -C /src fetch -q --depth 1 origin "$HALCYON_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- build ----
# JDK 25, as the reactor's enforcer requires. The build reaches the first-party repository at
# cursus.bmi.stonybrookmedicine.edu and dcm4che.org besides Maven Central (pom.xml).
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
 && cp "$(ls Halcyon/target/Halcyon-*.jar | grep -v -e '-plain' -e '\.original' | head -1)" /Halcyon.jar

# ---- runtime ----
FROM eclipse-temurin:25-jre
ARG HALCYON_REF
LABEL org.opencontainers.image.title="Halcyon (LWS target server)" \
      org.opencontainers.image.source="https://github.com/halcyon-project/Halcyon" \
      org.opencontainers.image.revision="${HALCYON_REF}"
RUN groupadd --system --gid 10001 halcyon \
 && useradd --system --uid 10001 --gid halcyon --home-dir /data --no-create-home halcyon \
 && mkdir -p /data && chown halcyon:halcyon /data
COPY --from=build /Halcyon.jar /opt/halcyon/Halcyon.jar
COPY --chmod=755 entrypoint.sh /opt/halcyon/entrypoint.sh
# Halcyon reads settings.ttl and lws-oidc.json from its working directory, and keeps its stores, the
# storage's files, the authorization server's signing key (in the LWS store) and its logs there.
WORKDIR /data
VOLUME /data
USER halcyon
ENV JAVA_OPTS="-Xmx4g"
EXPOSE 8080
ENTRYPOINT ["/opt/halcyon/entrypoint.sh"]
