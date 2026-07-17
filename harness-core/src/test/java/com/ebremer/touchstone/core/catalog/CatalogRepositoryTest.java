package com.ebremer.touchstone.core.catalog;

import java.nio.file.Path;
import java.util.List;

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
