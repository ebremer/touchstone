package com.ebremer.touchstone.core.catalog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ebremer.touchstone.core.definitions.TestDefinition;

/**
 * Checks that every requirement IRI a test cites resolves to a catalog entry.
 *
 * <p>A test's {@code requirements} are the whole link between it and the clause it exists to
 * verify, and an IRI that resolves to nothing fails silently in two places at once: the EARL
 * report, the artifact meant for W3C implementation reports, emits a
 * {@code touchstone:verifies} triple pointing at a requirement that does not exist, and the
 * coverage matrix never counts the test. A run refuses to start on such a test (the lint of
 * EXECUTION.md section 2.5, D-0039); {@code coverage}, a report rather than a gate, warns.
 */
public final class RequirementRefs {

    /** One test naming one requirement IRI that is not in the catalog. */
    public record Dangling(String testId, String iri) {
    }

    private RequirementRefs() {
    }

    /**
     * Every (test, IRI) pair where the IRI is absent from {@code catalog}, in test order. An
     * empty catalog yields no findings: it means no catalog was configured, which is a separate
     * condition from a test citing something that is not there.
     */
    public static List<Dangling> unresolved(Collection<TestDefinition> tests, Collection<Requirement> catalog) {
        if (catalog.isEmpty()) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        catalog.forEach(r -> known.add(r.iri()));
        return tests.stream()
                .flatMap(t -> t.requirements().stream()
                        .filter(iri -> !known.contains(iri))
                        .map(iri -> new Dangling(t.id(), iri)))
                .toList();
    }

    /** The findings as one operator-readable message naming each test and IRI. */
    public static String describe(List<Dangling> dangling) {
        StringBuilder sb = new StringBuilder(dangling.size() + " cited requirement(s) are not in the catalog");
        for (Dangling d : dangling) {
            sb.append("\n  ").append(d.testId()).append(" -> ").append(d.iri());
        }
        return sb.toString();
    }
}
