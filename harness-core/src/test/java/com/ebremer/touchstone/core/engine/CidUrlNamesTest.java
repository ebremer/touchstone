package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.exec.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Format 0.15.0 (D-0101) names an agent's identifier by its LWS term, the CID URL: a target sets
 * {@code cidUrl.<name>}, and a template reads {@code ${identity.<name>.cidUrl}}. The names before
 * it, {@code webid.<name>} and {@code ${identity.<name>.webid}}, still work, so a target or a
 * definition written earlier means what it meant.
 */
class CidUrlNamesTest {

    private static final String ALICE = "https://ids.example/alice#me";
    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    @Test
    void aTargetNamesItsAgentsByCidUrl() throws IOException {
        try (StubTarget storage = StubTarget.start(exchange -> false);
                RunSession run = RunSession.open(target(storage, Map.of("cidUrl.alice", ALICE)), definitions, "run")) {
            Scope scope = new Scope(run, null);
            assertThat(run.credentials().cidUrl("alice", scope)).isEqualTo(ALICE);
            assertThat(scope.resolve("identity.alice.cidUrl").asText()).isEqualTo(ALICE);
            assertThat(scope.resolve("identity.alice.webid").asText()).isEqualTo(ALICE);
        }
    }

    @Test
    void theNameBeforeFormat015StillWorks() throws IOException {
        try (StubTarget storage = StubTarget.start(exchange -> false);
                RunSession run = RunSession.open(target(storage, Map.of("webid.alice", ALICE)), definitions, "run")) {
            assertThat(run.credentials().cidUrl("alice", new Scope(run, null))).isEqualTo(ALICE);
        }
    }

    @Test
    void theNewNameWinsWhenATargetSetsBoth() throws IOException {
        try (StubTarget storage = StubTarget.start(exchange -> false);
                RunSession run = RunSession.open(target(storage,
                        Map.of("cidUrl.alice", ALICE, "webid.alice", "https://old.example/alice#me")),
                        definitions, "run")) {
            assertThat(run.credentials().cidUrl("alice", new Scope(run, null))).isEqualTo(ALICE);
        }
    }

    private static Target target(StubTarget storage, Map<String, String> properties) {
        return new Target("stub", storage.base(), "env", properties, Set.of());
    }
}
