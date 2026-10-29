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
        assertThat(rules.rules()).hasSize(53);
        assertThat(rules.rules().getFirst().id()).isEqualTo("clients/core#client-token-in-authorization-header");
        assertThat(rules.rules().stream().map(RuleDefinition::area).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("core", "authentication", "notifications", "index");
        RuleDefinition r = rules.find("client-query-baseline-after-415").orElseThrow();
        assertThat(r.observe().path("after").path("statusCode").asInt()).isEqualTo(415);
        assertThat(r.iri()).isEqualTo(Definitions.BASE + "clients/index#client-query-baseline-after-415");
        assertThat(r.guidance()).contains("application/lws-query+json");
        assertThat(r.task()).isNull();
        RuleDefinition lost = rules.find("client-no-blind-retry-of-create").orElseThrow();
        assertThat(lost.task().arm()).isEqualTo("lostCreateResponse");
        assertThat(lost.taskTriggered()).isFalse();
        assertThat(rules.find("client-create-container-type-link").orElseThrow().taskTriggered()).isTrue();
        assertThat(rules.find("client-combined-update-prefer-set-linkset").orElseThrow().taskTriggered()).isTrue();
        assertThat(rules.find("client-create-no-server-managed-links").orElseThrow().task()).isNull();
        assertThat(rules.find("client-saml-token-type-saml2").orElseThrow().observe().path("credentialSource").asText())
                .isEqualTo("samlIdentityProvider");
        RuleDefinition discovery = rules.find("client-token-after-discovery-checks").orElseThrow();
        assertThat(discovery.task().arm()).isEqualTo("tokenExpired");
        assertThat(discovery.iri()).isEqualTo(Definitions.BASE + "clients/authentication#client-token-after-discovery-checks");
        assertThat(discovery.expect().path("discoveryValid").asBoolean()).isTrue();
        assertThat(rules.find("client-cid-credential-signed").orElseThrow().expect()
                .at("/credential/header/0/pointer").asText()).isEqualTo("/alg");
        // A task that arms a fault, without after: its trials are the exchanges observe selects.
        RuleDefinition forged = rules.find("client-inbox-refuses-altered-body").orElseThrow();
        assertThat(forged.task().arm()).isEqualTo("forgedAlteredBody");
        assertThat(forged.taskTriggered()).isFalse();
        assertThat(forged.observe().path("deliverySignature").asText()).isEqualTo("alteredBody");
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

    @Test
    void aRulesDriveIsLoadedAsWritten() {
        ClientRules rules = DefinitionLoader.loadClientRules(DEFINITIONS, CATALOG);
        List<DriveStep> drive = rules.find("client-no-repeat-after-405-415").orElseThrow().drive();
        // A careful application (D-0108): it reads before it writes, and writes with If-Match.
        assertThat(drive).extracting(s -> s.isOperation() ? s.op() : s.startTask() ? "startTask" : "wait")
                .containsExactly("create", "read", "read_linkset", "startTask", "update_linkset", "read_linkset");
        assertThat(drive.get(0).args().path("container").asText()).isEqualTo("${drive.container}");
        assertThat(drive.get(0).capture()).containsEntry("note", "/location");
        assertThat(drive.get(2).capture()).containsEntry("linksetUrl", "/url").containsEntry("linkset", "/linkset");
        assertThat(drive.get(4).args().path("ifMatch").asText()).isEqualTo("${linksetEtag}");
        assertThat(drive.get(4).allows(405)).isTrue();
        assertThat(drive.get(4).allows(404)).isFalse();
        assertThat(rules.find("client-no-blind-retry-of-create").orElseThrow().drive().get(1).allows(503)).isTrue();
        assertThat(rules.find("client-restart-after-refused-page").orElseThrow().drive())
                .anySatisfy(s -> assertThat(s.waitSeconds()).isEqualTo(4));
        // The setup's traffic tries it: every token request carries a self-issued credential.
        assertThat(rules.find("client-cid-subject-claim").orElseThrow().drive()).isEmpty();
        assertThat(rules.rules().stream().filter(r -> !r.drive().isEmpty()).count()).isEqualTo(20);
    }

    @Test
    void aDriveOutsideTheProtocolIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = edit(tmp, "    name: client-member-urls-issued\n", """
                    name: client-member-urls-issued
                    drive:
                      - startTask: true
                      - op: create
                        args: {body: {text: x}, prefer: return=minimal}
                        capture: {made: /location}
                      - op: delete
                        args: {url: "${made}", ifMatch: "${etag}"}
                        capture: {made: /status}
                """);
        assertThatThrownBy(() -> DefinitionLoader.loadClientRules(copy, CATALOG))
                .hasMessageContaining("client-member-urls-issued drive step 1: startTask in a rule without a task")
                .hasMessageContaining("client-member-urls-issued drive step 2: create has no argument prefer")
                .hasMessageContaining("client-member-urls-issued drive step 2: create needs the argument container")
                .hasMessageContaining("client-member-urls-issued drive step 3: unbound variable ${etag}")
                .hasMessageContaining("client-member-urls-issued drive step 3: capture made rebinds an existing variable");
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
