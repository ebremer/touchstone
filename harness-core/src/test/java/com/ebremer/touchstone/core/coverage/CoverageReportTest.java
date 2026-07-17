package com.ebremer.touchstone.core.coverage;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageReportTest {

    private static final List<Requirement> FIXTURE =
            CatalogRepository.load(Path.of("src", "test", "resources", "catalog-fixture"));

    @Test
    void emptyTestSetLeavesEverythingUncovered() {
        CoverageReport report = CoverageReport.compute(FIXTURE, Set.of());

        assertThat(report.totalRequirements()).isEqualTo(3);
        assertThat(report.totalCovered()).isZero();
        assertThat(report.uncoveredIris()).hasSize(3);
        assertThat(report.rows()).containsExactly(
                new CoverageReport.Row("test-module", "MUST", 0, 2),
                new CoverageReport.Row("test-module", "SHOULD", 0, 1));
    }

    @Test
    void coveredIrisCountPerLevelCell() {
        CoverageReport report = CoverageReport.compute(
                FIXTURE, Set.of("https://example.org/touchstone/req/test-module/alpha"));

        assertThat(report.totalCovered()).isEqualTo(1);
        assertThat(report.rows()).containsExactly(
                new CoverageReport.Row("test-module", "MUST", 1, 2),
                new CoverageReport.Row("test-module", "SHOULD", 0, 1));
        assertThat(report.uncoveredIris())
                .containsExactlyInAnyOrder(
                        "https://example.org/touchstone/req/test-module/beta",
                        "https://example.org/touchstone/req/test-module/gamma");
    }
}
