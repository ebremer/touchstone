package com.ebremer.touchstone.core.definitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientRulesLoaderTest {

    static final Path DEFINITIONS = Path.of("..", "definitions");
    static final List<Requirement> CATALOG = CatalogRepository.load(Path.of("..", "catalog"));

    @Test
    void loadsEveryClientRuleInTraversalOrder() {
        ClientRules rules = DefinitionLoader.loadClientRules(DEFINITIONS, CATALOG);
        assertThat(rules.rules()).hasSize(30);
        assertThat(rules.rules().getFirst().id()).isEqualTo("clients/core#client-token-in-authorization-header");
        assertThat(rules.rules().stream().map(RuleDefinition::area).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("core", "notifications", "index");
        RuleDefinition r = rules.find("client-query-baseline-after-415").orElseThrow();
        assertThat(r.observe().path("after").path("statusCode").asInt()).isEqualTo(415);
        assertThat(r.iri()).isEqualTo(Definitions.BASE + "clients/index#client-query-baseline-after-415");
        assertThat(r.guidance()).contains("application/lws-query+json");
        assertThat(r.task()).isNull();
        RuleDefinition lost = rules.find("client-no-blind-retry-of-create").orElseThrow();
        assertThat(lost.task().arm()).isEqualTo("lostCreateResponse");
        assertThat(lost.taskTriggered()).isFalse();
        assertThat(rules.find("client-create-container-type-link").orElseThrow().taskTriggered()).isTrue();
    }

    @Test
    void aRuleCitingOnlyServerClausesIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = edit(tmp, "lws10-core/put-clients-use-conditional-requests",
                "lws10-core/conditional-requests-supported");
        assertThatThrownBy(() -> DefinitionLoader.loadClientRules(copy, CATALOG))
                .isInstanceOf(InvalidDefinitionsException.class)
                .hasMessageContaining("client-put-conditional: cites no requirement that binds a Client or a Receiver");
    }

    @Test
    void aRuleStrongerThanItsClauseIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = edit(tmp, "    level: MAY\n", "    level: MUST\n");
        assertThatThrownBy(() -> DefinitionLoader.loadClientRules(copy, CATALOG))
                .hasMessageContaining("client-delete-conditional: is MUST, stronger than any requirement it cites (MAY)");
    }

    @Test
    void variablesAndCapturesAreRefused(@TempDir Path tmp) throws IOException {
        Path copy = edit(tmp, "          hasValue: AccessGrant\n", "          hasValue: \"${storage}\"\n          capture: grantType\n");
        assertThatThrownBy(() -> DefinitionLoader.loadClientRules(copy, CATALOG))
                .hasMessageContaining("client-access-grant-type: the expect has ${storage}, but client rules have no variables")
                .hasMessageContaining("client-access-grant-type: the expect captures a value");
    }

    @Test
    void aRuleNamedLikeAServerTestIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = edit(tmp, "client-delete-conditional", "deleteDataResource");
        assertThatThrownBy(() -> DefinitionLoader.loadClientRules(copy, CATALOG))
                .hasMessageContaining("the name deleteDataResource is a server test's too");
    }

    /** A copy of the definitions with one edit, everywhere, in the client core manifest. */
    private static Path edit(Path tmp, String target, String replacement) throws IOException {
        Path copy = DefinitionLoaderTest.copy(tmp);
        Path file = copy.resolve("lws10/clients/core.yamlld");
        String s = Files.readString(file);
        assertThat(s).contains(target);
        Files.writeString(file, s.replace(target, replacement));
        return copy;
    }
}
