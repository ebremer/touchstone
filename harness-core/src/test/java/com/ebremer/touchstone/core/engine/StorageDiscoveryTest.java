package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
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
import com.ebremer.touchstone.core.exec.ProvisioningException;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.exec.TargetRegistry;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A target registered by its storage (EXECUTION.md section 4.1, D-0099): the engine reads the
 * storage description for the StorageRoot service and creates the run root there, against a stub
 * whose storage is {@code /storage/} and whose storage root is {@code /root/}.
 */
class StorageDiscoveryTest {

    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    private static final String DESCRIPTION = """
            {"@context": ["https://www.w3.org/ns/cid/v1", "https://www.w3.org/ns/lws/v1"],
             "id": "%s", "type": "Storage",
             "service": [{"type": "NotificationService", "serviceEndpoint": "/notifications/"},
                         {"type": "StorageRoot", "serviceEndpoint": "/root/"}]}""";

    @Test
    void theRunRootIsCreatedInTheStorageRootTheDescriptionNames() throws Exception {
        Storage storage = new Storage(DESCRIPTION, false);
        try (StubTarget stub = StubTarget.start(storage)) {
            RunResult result = Engine.run(byStorage(stub, Map.of()), definitions, List.of(probe()), null);

            URI root = stub.base().resolve("/root/");
            assertThat(result.results()).singleElement().extracting(r -> r.outcome()).isEqualTo(Outcome.PASSED);
            assertThat(result.targetBaseUrl()).isEqualTo(root.toString());
            // alice has no token here, so she too sends no credentials
            assertThat(storage.requests).startsWith("GET /storage/ application/lws+cid anonymous",
                    "POST /root/ anonymous");
        }
    }

    @Test
    void aDescriptionThatDrawsA401IsFetchedAgainAsAlice() throws Exception {
        Storage storage = new Storage(DESCRIPTION, true);
        try (StubTarget stub = StubTarget.start(storage)) {
            RunResult result = Engine.run(byStorage(stub, Map.of("token.alice", "t-alice")), definitions,
                    List.of(probe()), null);

            assertThat(result.results()).singleElement().extracting(r -> r.outcome()).isEqualTo(Outcome.PASSED);
            assertThat(storage.requests).startsWith("GET /storage/ application/lws+cid anonymous",
                    "GET /storage/ application/lws+cid alice", "POST /root/ alice");
        }
    }

    @Test
    void withoutAStorageRootThereIsNoRun() throws Exception {
        Storage storage = new Storage("""
                {"id": "%s", "type": "Storage", "service": [{"type": "NotificationService", "serviceEndpoint": "/n/"}]}""",
                false);
        try (StubTarget stub = StubTarget.start(storage)) {
            assertThatThrownBy(() -> Engine.run(byStorage(stub, Map.of()), definitions, List.of(probe()), null))
                    .isInstanceOf(ProvisioningException.class)
                    .hasMessageContaining("cannot find the storage root")
                    .hasMessageContaining("no StorageRoot service");
            assertThat(storage.requests).noneMatch(r -> r.startsWith("POST"));
        }
    }

    @Test
    void aDescriptionThatIsNotThereIsNoRun() throws Exception {
        try (StubTarget stub = StubTarget.start(exchange -> false)) {
            assertThatThrownBy(() -> Engine.run(byStorage(stub, Map.of()), definitions, List.of(probe()), null))
                    .isInstanceOf(ProvisioningException.class)
                    .hasMessageContaining("cannot find the storage root")
                    .hasMessageContaining("answered 404");
        }
    }

    @Test
    void aTargetNamesEitherABaseUrlOrAStorage(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("targets.yaml");
        Files.writeString(file, """
                targets:
                  by-base:
                    baseUrl: http://localhost:4711/
                  by-storage:
                    storage: https://storage.example/
                    capabilities: [Authentication]
                """);
        TargetRegistry registry = TargetRegistry.load(file);
        assertThat(registry.find("by-base").orElseThrow().storage()).isNull();
        Target byStorage = registry.find("by-storage").orElseThrow();
        assertThat(byStorage.baseUrl()).isNull();
        assertThat(byStorage.storage()).isEqualTo(URI.create("https://storage.example/"));
        assertThat(byStorage.capabilities()).containsExactly("Authentication");

        Files.writeString(file, "targets:\n  both:\n    baseUrl: http://a.example/\n    storage: http://a.example/\n");
        assertThatThrownBy(() -> TargetRegistry.load(file)).hasMessageContaining("not both");
        Files.writeString(file, "targets:\n  neither:\n    adapter: env\n");
        assertThatThrownBy(() -> TargetRegistry.load(file)).hasMessageContaining("not neither");
        Files.writeString(file, "targets:\n  relative:\n    storage: /storage/\n");
        assertThatThrownBy(() -> TargetRegistry.load(file)).hasMessageContaining("not an absolute http or https URL");
    }

    private static Target byStorage(StubTarget stub, Map<String, String> properties) {
        return new Target("stub", null, "env", properties, Set.of(), stub.base().resolve("/storage/"));
    }

    /** One step: a GET of {@code ${target.baseUrl}probe}, which only the storage root answers. */
    private static TestDefinition probe() throws IOException {
        StepDefinition step = new StepDefinition("probe", null, false,
                Templates.JSON.readTree("{\"method\": \"GET\", \"url\": \"probe\"}"),
                Templates.JSON.readTree("{\"statusCode\": 200}"));
        return new TestDefinition("engine/discovery#probe", "engine/discovery", "probe",
                Definitions.BASE + "engine/discovery#probe", "ValidationTest", "the storage root answers", "MUST",
                "Proposed", List.of(), List.of("Discovery"), List.of(), null, List.of(), null, List.of(step),
                Path.of("..", "definitions", "lws10"));
    }

    /** Serves the storage description, protected or not, and the probe under the storage root. */
    private static final class Storage implements StubTarget.Handler {
        private final String description;
        private final boolean protectedDescription;
        final List<String> requests = new CopyOnWriteArrayList<>();

        Storage(String description, boolean protectedDescription) {
            this.description = description;
            this.protectedDescription = protectedDescription;
        }

        @Override
        public boolean handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String who = auth == null ? "anonymous" : auth.equals("Bearer t-alice") ? "alice" : auth;
            if (method.equals("GET") && path.equals("/storage/")) {
                requests.add("GET /storage/ " + exchange.getRequestHeaders().getFirst("Accept") + " " + who);
                if (protectedDescription && auth == null) {
                    StubTarget.answer(exchange, 401, Map.of("WWW-Authenticate", "Bearer"), null);
                    return true;
                }
                String host = "http://" + exchange.getRequestHeaders().getFirst("Host");
                StubTarget.answer(exchange, 200, Map.of("Content-Type", "application/lws+cid"),
                        description.formatted(host + "/storage/"));
                return true;
            }
            if (method.equals("POST")) {
                requests.add("POST " + path + " " + who);
                return false;
            }
            if (method.equals("GET") && path.equals("/root/probe")) {
                StubTarget.answer(exchange, 200, Map.of("Content-Type", "text/plain"), "ok");
                return true;
            }
            return false;
        }
    }
}
