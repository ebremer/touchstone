package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.exec.Target;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Where the fixture host serves what it mints (EXECUTION.md section 5.3, D-0091): a document
 * whose keys are new each run is served at a URL that names the run, so a verifier's cached copy
 * of an earlier run's document never stands for the current one; what targets configure stays
 * where it was.
 */
class FixtureDocumentsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    @Test
    void documentsWithPerRunKeysAreServedAtUrlsUniqueToTheRun() throws Exception {
        try (StubTarget storage = StubTarget.start(exchange -> false)) {
            String fixtures = "http://127.0.0.1:" + freePort() + "/";
            Target target = new Target("stub", storage.base(), "env",
                    Map.of("fixtures.baseUrl", fixtures, "webid.alice", fixtures + "agents/alice"),
                    Set.of("ReachableFixtures"));

            Map<String, JsonNode> first;
            String firstKey;
            try (RunSession run = RunSession.open(target, definitions, "run-one")) {
                first = run.credentials().fixtureDocuments(new Scope(run, null));
                firstKey = first.get("agents/run-one/cid").path("authentication").path(0).path("publicKeyJwk").toString();
                assertThat(status(fixtures + "agents/run-one/cid")).isEqualTo(200);
                assertThat(status(fixtures + "agents/cid")).isEqualTo(404);
            }
            Map<String, JsonNode> second;
            try (RunSession run = RunSession.open(target, definitions, "run-two")) {
                second = run.credentials().fixtureDocuments(new Scope(run, null));
                assertThat(status(fixtures + "agents/run-two/cid")).isEqualTo(200);
                assertThat(status(fixtures + "agents/run-one/cid")).isEqualTo(404);
            }

            // The six controlled identifier documents carry the run's key, so each names its run.
            Set<String> cid = first.keySet().stream().filter(p -> p.contains("cid")).collect(Collectors.toSet());
            assertThat(cid).hasSize(6).allMatch(p -> p.startsWith("agents/run-one/cid"));
            assertThat(second.keySet().stream().filter(p -> p.contains("cid")))
                    .hasSize(6).allMatch(p -> p.startsWith("agents/run-two/cid"));
            JsonNode doc = second.get("agents/run-two/cid");
            assertThat(doc.path("id").asText()).isEqualTo(fixtures + "agents/run-two/cid");
            assertThat(doc.path("authentication").path(0).path("id").asText()).startsWith(fixtures + "agents/run-two/cid#");
            assertThat(doc.path("authentication").path(0).path("publicKeyJwk").toString()).isNotEqualTo(firstKey);

            // What a target configures stays put: the OpenID Provider's issuer, discovery and JWKS,
            // and the OpenID subject's document, which carries no key. alice is configured, not hosted.
            for (Map<String, JsonNode> docs : java.util.List.of(first, second)) {
                assertThat(docs).containsKeys("agents/oidc", "op/.well-known/openid-configuration", "op/jwks",
                        "rogue-op/.well-known/openid-configuration", "rogue-op/jwks");
                assertThat(docs.get("op/.well-known/openid-configuration").path("issuer").asText()).isEqualTo(fixtures + "op");
                assertThat(docs.get("agents/oidc").path("service").path(0).path("serviceEndpoint").asText())
                        .isEqualTo(fixtures + "op");
                assertThat(docs).doesNotContainKey("agents/alice");
            }
        }
    }

    @Test
    void aRunIdMustBeSafeInAUrl() throws Exception {
        try (StubTarget storage = StubTarget.start(exchange -> false)) {
            Target target = new Target("stub", storage.base(), "env", Map.of(), Set.of());
            assertThatThrownBy(() -> RunSession.open(target, definitions, "a/b"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static int status(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
