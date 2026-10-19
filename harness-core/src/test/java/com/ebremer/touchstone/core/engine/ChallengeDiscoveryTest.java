package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.StepDefinition;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code ${as.uri}} and {@code ${as.resource}} (EXECUTION.md section 3, D-0100): from the RFC 9728
 * challenge an anonymous GET of the test container draws, the protected resource metadata it
 * names and the storage its Link names; from the challenge of the drafts before 19 October 2026
 * for a server that still sends it; and not at all from a 401 that names no storage.
 */
class ChallengeDiscoveryTest {

    private static final String LWS_STORAGE = "https://www.w3.org/ns/lws#storage";
    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    @Test
    void theAuthorizationServerComesFromTheProtectedResourceMetadataAndTheResourceFromTheLink() throws Exception {
        Storage storage = new Storage(Storage.Challenge.RESOURCE_METADATA);
        try (StubTarget stub = StubTarget.start(storage)) {
            TestResult result = run(stub);

            assertThat(result.outcome()).as(result.reason()).isEqualTo(Outcome.PASSED);
            assertThat(storage.probed).containsExactly(
                    "as=https://as.example/tenant res=" + stub.base().resolve("/storage/"));
            assertThat(storage.requests).contains("GET /.well-known/oauth-protected-resource/c2/ application/json");
        }
    }

    @Test
    void theChallengeOfEarlierDraftsIsStillRead() throws Exception {
        Storage storage = new Storage(Storage.Challenge.AS_URI_AND_REALM);
        try (StubTarget stub = StubTarget.start(storage)) {
            TestResult result = run(stub);

            assertThat(result.outcome()).as(result.reason()).isEqualTo(Outcome.PASSED);
            assertThat(storage.probed).containsExactly("as=https://as.example/old res=https://realm.example/");
            assertThat(storage.requests).noneMatch(r -> r.contains("oauth-protected-resource"));
        }
    }

    @Test
    void aChallengeWithoutAStorageLinkLeavesTheResourceUnknown() throws Exception {
        Storage storage = new Storage(Storage.Challenge.NO_STORAGE_LINK);
        try (StubTarget stub = StubTarget.start(storage)) {
            TestResult result = run(stub);

            assertThat(result.outcome()).isEqualTo(Outcome.CANT_TELL);
            assertThat(result.reason()).contains("no Link whose rel is " + LWS_STORAGE);
            assertThat(storage.probed).isEmpty();
        }
    }

    private static TestResult run(StubTarget stub) throws IOException {
        Target target = new Target("stub", stub.base(), "env", Map.of(), Set.of());
        RunResult run = Engine.run(target, definitions, List.of(probe()), null);
        return run.results().getFirst();
    }

    /** One step: a GET of {@code ${target.baseUrl}probe} naming what the variables resolved to. */
    private static TestDefinition probe() throws IOException {
        StepDefinition step = new StepDefinition("probe", null, false,
                Templates.JSON.readTree("{\"method\": \"GET\", \"url\": \"probe?as=${as.uri}&res=${as.resource}\"}"),
                Templates.JSON.readTree("{\"statusCode\": 200}"));
        return new TestDefinition("engine/challenge#probe", "engine/challenge", "probe",
                Definitions.BASE + "engine/challenge#probe", "ValidationTest", "the challenge is read", "MUST",
                "Proposed", List.of(), List.of("Authn"), List.of(), null, List.of(), null, List.of(step),
                Path.of("..", "definitions", "lws10"));
    }

    /** Challenges an anonymous GET of a container, serves the protected resource metadata, and records the probe. */
    private static final class Storage implements StubTarget.Handler {

        enum Challenge { RESOURCE_METADATA, AS_URI_AND_REALM, NO_STORAGE_LINK }

        private final Challenge challenge;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> probed = new CopyOnWriteArrayList<>();

        Storage(Challenge challenge) {
            this.challenge = challenge;
        }

        @Override
        public boolean handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String host = "http://" + exchange.getRequestHeaders().getFirst("Host");
            requests.add(method + " " + path + " " + exchange.getRequestHeaders().getFirst("Accept"));
            if (method.equals("GET") && path.startsWith("/.well-known/oauth-protected-resource/")) {
                String resource = host + path.substring("/.well-known/oauth-protected-resource".length());
                StubTarget.answer(exchange, 200, Map.of("Content-Type", "application/json"),
                        "{\"resource\": \"" + resource + "\", \"authorization_servers\": [\"https://as.example/tenant\"]}");
                return true;
            }
            if (method.equals("GET") && path.equals("/probe")) {
                String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                probed.add(query.replace('&', ' '));
                StubTarget.answer(exchange, 200, Map.of("Content-Type", "text/plain"), "ok");
                return true;
            }
            if (method.equals("GET") && path.endsWith("/")) {
                String link = "<" + host + "/storage/>; rel=\"" + LWS_STORAGE + "\"";
                switch (challenge) {
                    case RESOURCE_METADATA -> StubTarget.answer(exchange, 401, Map.of("WWW-Authenticate",
                            "Bearer resource_metadata=\"" + host + "/.well-known/oauth-protected-resource" + path + "\"",
                            "Link", link), null);
                    case AS_URI_AND_REALM -> StubTarget.answer(exchange, 401, Map.of("WWW-Authenticate",
                            "Bearer as_uri=\"https://as.example/old\", realm=\"https://realm.example/\""), null);
                    case NO_STORAGE_LINK -> StubTarget.answer(exchange, 401, Map.of("WWW-Authenticate",
                            "Bearer resource_metadata=\"" + host + "/.well-known/oauth-protected-resource" + path + "\""),
                            null);
                }
                return true;
            }
            return false;
        }
    }
}
