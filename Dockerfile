# Touchstone conformance harness image.
# Multi-stage: build the CLI from source with the Maven wrapper, then ship a slim JRE
# runtime carrying the shaded jar plus the catalog and manifests, so a third-party server
# implementer can run a conformance report with one `docker run`.

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# All four module POMs are needed for the aggregator to parse; warm the dependency cache
# on them first, then bring in only the sources the CLI build reaches (core + cli).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY harness-core/pom.xml harness-core/
COPY harness-fixtures/pom.xml harness-fixtures/
COPY harness-cli/pom.xml harness-cli/
COPY harness-mcp/pom.xml harness-mcp/
RUN ./mvnw -q -B -ntp -pl harness-cli -am dependency:go-offline || true
COPY harness-core/ harness-core/
COPY harness-cli/ harness-cli/
# maven.test.skip (not skipTests) so the CLI's test sources — which reference the
# fixtures module, deliberately left out of this reactor — are not even compiled.
RUN ./mvnw -q -B -ntp -pl harness-cli -am -Dmaven.test.skip=true package

# ---- runtime ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /opt/touchstone
LABEL org.opencontainers.image.title="Touchstone" \
      org.opencontainers.image.description="Conformance test harness for the W3C Linked Web Storage (LWS) protocol family" \
      org.opencontainers.image.source="https://github.com/ebremer/touchstone"
COPY --from=build /src/harness-cli/target/touchstone.jar touchstone.jar
COPY catalog/ catalog/
COPY manifests/ manifests/
# Reports and the target registry live in a mounted /work by convention (see the GitHub Action).
ENTRYPOINT ["java", "-jar", "/opt/touchstone/touchstone.jar"]
CMD ["--help"]
