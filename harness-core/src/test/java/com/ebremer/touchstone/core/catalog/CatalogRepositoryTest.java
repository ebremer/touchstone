package com.ebremer.touchstone.core.catalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRepositoryTest {

    private static final Path FIXTURE = Path.of("src", "test", "resources", "catalog-fixture");

    @Test
    void loadsRequirementsFromTurtleSortedByIri() {
        List<Requirement> requirements = CatalogRepository.load(FIXTURE);

        assertThat(requirements).hasSize(3);
        assertThat(requirements).extracting(Requirement::iri).isSorted();

        Requirement alpha = requirements.getFirst();
        assertThat(alpha.iri()).isEqualTo("https://example.org/touchstone/req/test-module/alpha");
        assertThat(alpha.level()).isEqualTo("MUST");
        assertThat(alpha.specModule()).isEqualTo("test-module");
        assertThat(alpha.section()).isEqualTo("https://example.org/spec#alpha");
        assertThat(alpha.summary()).isEqualTo("Alpha requirement.");
        assertThat(alpha.status()).isEqualTo("Approved");
        assertThat(alpha.appliesTo()).containsExactly("Client", "Server");
        assertThat(alpha.bindsServerSide()).isTrue();
    }

    @Test
    void aRequirementWithoutRolesCountsAsServerSide() {
        // A catalog older than D-0076 names no roles; a server run still answers for all of it.
        Requirement beta = CatalogRepository.load(FIXTURE).stream()
                .filter(r -> r.iri().endsWith("/beta"))
                .findFirst()
                .orElseThrow();
        assertThat(beta.appliesTo()).isEmpty();
        assertThat(beta.bindsServerSide()).isTrue();
        assertThat(new Requirement("x", "MUST", "m", null, null, null, null, List.of("Client", "Receiver"))
                .bindsServerSide()).isFalse();
    }

    @Test
    void everyRequirementInTheCatalogNamesItsRoles() {
        Set<String> roles = Set.of("Server", "AuthorizationServer", "Client", "IdentityProvider", "Receiver",
                "Specification");
        List<Requirement> catalog = CatalogRepository.load(Path.of("..", "catalog"));

        assertThat(catalog).isNotEmpty().allSatisfy(r -> assertThat(r.appliesTo()).as(r.iri()).isNotEmpty()
                .allSatisfy(role -> assertThat(roles).contains(role)));
        assertThat(catalog).filteredOn(r -> !r.bindsServerSide()).isNotEmpty();
    }

    @Test
    void missingOptionalPropertiesBecomeNulls() {
        List<Requirement> requirements = CatalogRepository.load(FIXTURE);

        Requirement beta = requirements.stream()
                .filter(r -> r.iri().endsWith("/beta"))
                .findFirst()
                .orElseThrow();
        assertThat(beta.section()).isNull();
        assertThat(beta.level()).isEqualTo("MUST");
    }
}
