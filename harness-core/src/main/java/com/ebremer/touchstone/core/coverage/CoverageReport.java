package com.ebremer.touchstone.core.coverage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.core.catalog.Requirement;

/**
 * Requirements-by-tests coverage matrix (DESIGN.md paragraph 5.1): which catalog
 * requirements are verified by at least one test. MUST failures decide conformance,
 * so the matrix is reported per (specModule, level) cell.
 */
public final class CoverageReport {

    /** Covered/total for one (specModule, level) cell. */
    public record Row(String specModule, String level, long covered, long total) {
    }

    private static final List<String> LEVEL_ORDER = List.of("MUST", "SHOULD", "MAY");

    private final List<Row> rows;
    private final List<String> uncoveredIris;

    private CoverageReport(List<Row> rows, List<String> uncoveredIris) {
        this.rows = rows;
        this.uncoveredIris = uncoveredIris;
    }

    /** Computes the matrix for {@code requirements} given the IRIs at least one test verifies. */
    public static CoverageReport compute(Collection<Requirement> requirements, Set<String> coveredIris) {
        Map<String, long[]> cells = new LinkedHashMap<>();
        List<String> uncovered = new ArrayList<>();
        for (Requirement r : requirements) {
            long[] cell = cells.computeIfAbsent(r.specModule() + "|" + r.level(), k -> new long[2]);
            cell[1]++;
            if (coveredIris.contains(r.iri())) {
                cell[0]++;
            } else {
                uncovered.add(r.iri());
            }
        }
        List<Row> rows = new ArrayList<>();
        cells.forEach((key, counts) -> {
            String[] parts = key.split("\\|", 2);
            rows.add(new Row(parts[0], parts[1], counts[0], counts[1]));
        });
        rows.sort(Comparator.comparing(Row::specModule).thenComparing(row -> {
            int i = LEVEL_ORDER.indexOf(row.level());
            return i < 0 ? LEVEL_ORDER.size() : i;
        }));
        return new CoverageReport(List.copyOf(rows), List.copyOf(uncovered));
    }

    public List<Row> rows() {
        return rows;
    }

    public List<String> uncoveredIris() {
        return uncoveredIris;
    }

    public long totalRequirements() {
        return rows.stream().mapToLong(Row::total).sum();
    }

    public long totalCovered() {
        return rows.stream().mapToLong(Row::covered).sum();
    }
}
