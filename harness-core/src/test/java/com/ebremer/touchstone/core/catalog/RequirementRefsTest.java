package com.ebremer.touchstone.core.catalog;

import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link between a test and the clause it verifies. A requirement IRI that resolves to
 * nothing is invisible three ways at once — EARL cites a requirement that does not exist,
 * coverage never counts the test, and the conformance verdict reads a level it cannot find —
 * so it is checked, and checked against the shipped catalog, not only in the abstract.
 */
class RequirementRefsTest {

    private static final Path CATALOG = Path.of("..", "catalog");
    private static final Path MANIFESTS = Path.of("..", "manifests");

    @Test
    void everyShippedManifestDeclaresRequirementsTheShippedCatalogHolds() {
        List<Requirement> catalog = CatalogRepository.load(CATALOG);
        List<Manifest> manifests = ManifestLoader.loadDirectory(MANIFESTS);

        assertThat(catalog).as("the catalog under %s", CATALOG).isNotEmpty();
        assertThat(manifests).as("the manifests under %s", MANIFESTS).isNotEmpty();
        assertThat(RequirementRefs.unresolved(manifests, catalog))
                .as("manifests citing a requirement IRI absent from the catalog")
                .isEmpty();
    }

    @Test
    void anIriTheCatalogDoesNotHoldIsReportedWithItsManifest() {
        List<Requirement> catalog = List.of(requirement("https://example.org/req/known"));
        List<Manifest> manifests = List.of(
                manifest("core/good", "https://example.org/req/known"),
                manifest("core/typo", "https://example.org/req/known", "https://example.org/req/mispelled"));

        List<RequirementRefs.Dangling> dangling = RequirementRefs.unresolved(manifests, catalog);

        assertThat(dangling).containsExactly(
                new RequirementRefs.Dangling("core/typo", "https://example.org/req/mispelled"));
        assertThat(RequirementRefs.describe(dangling))
                .contains("1 declared requirement(s) are not in the catalog")
                .contains("core/typo -> https://example.org/req/mispelled");
    }

    @Test
    void anEmptyCatalogMeansUnconfiguredRatherThanUnresolvable() {
        // `run --catalog` may point at nothing; that is a different condition from a manifest
        // naming a requirement the catalog does not hold, and must not be reported as one.
        assertThat(RequirementRefs.unresolved(
                List.of(manifest("core/one", "https://example.org/req/anything")), List.of()))
                .isEmpty();
    }

    private static Requirement requirement(String iri) {
        return new Requirement(iri, "MUST", "lws10-core", null, null, null, "Approved");
    }

    private static Manifest manifest(String id, String... requirements) {
        return new Manifest(id, id, null, List.of(requirements), List.of(), List.of(), null,
                List.of(), Path.of("manifests", id + ".yaml"));
    }
}
