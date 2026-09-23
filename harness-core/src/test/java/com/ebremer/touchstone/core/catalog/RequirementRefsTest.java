package com.ebremer.touchstone.core.catalog;

import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link between a test and the clause it verifies. A requirement IRI that resolves to
 * nothing is invisible twice over (EARL cites a requirement that does not exist, and coverage
 * never counts the test), so it is checked, and checked against the shipped catalog, not only
 * in the abstract.
 */
class RequirementRefsTest {

    private static final Path CATALOG = Path.of("..", "catalog");
    private static final Path DEFINITIONS = Path.of("..", "definitions");

    @Test
    void everyShippedDefinitionCitesRequirementsTheShippedCatalogHolds() {
        List<Requirement> catalog = CatalogRepository.load(CATALOG);
        List<TestDefinition> tests = DefinitionLoader.load(DEFINITIONS, null).tests();

        assertThat(catalog).as("the catalog under %s", CATALOG).isNotEmpty();
        assertThat(tests).as("the definitions under %s", DEFINITIONS).isNotEmpty();
        assertThat(RequirementRefs.unresolved(tests, catalog))
                .as("definitions citing a requirement IRI absent from the catalog")
                .isEmpty();
    }

    @Test
    void anIriTheCatalogDoesNotHoldIsReportedWithItsTest() {
        List<Requirement> catalog = List.of(requirement("https://example.org/req/known"));
        List<TestDefinition> tests = List.of(
                test("core/x#good", "https://example.org/req/known"),
                test("core/x#typo", "https://example.org/req/known", "https://example.org/req/mispelled"));

        List<RequirementRefs.Dangling> dangling = RequirementRefs.unresolved(tests, catalog);

        assertThat(dangling).containsExactly(
                new RequirementRefs.Dangling("core/x#typo", "https://example.org/req/mispelled"));
        assertThat(RequirementRefs.describe(dangling))
                .contains("1 cited requirement(s) are not in the catalog")
                .contains("core/x#typo -> https://example.org/req/mispelled");
    }

    @Test
    void anEmptyCatalogMeansUnconfiguredRatherThanUnresolvable() {
        // `coverage --catalog` may point at nothing; that is a different condition from a test
        // citing a requirement the catalog does not hold, and must not be reported as one.
        assertThat(RequirementRefs.unresolved(
                List.of(test("core/x#one", "https://example.org/req/anything")), List.of()))
                .isEmpty();
    }

    private static Requirement requirement(String iri) {
        return new Requirement(iri, "MUST", "lws10-core", null, null, null, "Approved");
    }

    private static TestDefinition test(String id, String... requirements) {
        String name = id.substring(id.indexOf('#') + 1);
        return new TestDefinition(id, "core/x", name, "urn:test:" + id, "ValidationTest", name, "MUST", "Proposed",
                List.of(), List.of(), List.of(), null, List.of(requirements), null, List.of(), Path.of("."));
    }
}
