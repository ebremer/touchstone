package com.ebremer.touchstone.core.catalog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ebremer.touchstone.core.manifest.Manifest;

/**
 * Checks that every requirement IRI a manifest declares resolves to a catalog entry.
 *
 * <p>A manifest's {@code requirements} list is the whole link between a test and the clause
 * it exists to verify, and an IRI that resolves to nothing fails silently in three places at
 * once: the EARL report — the artifact meant for W3C implementation reports — emits a
 * {@code touchstone:verifies} triple pointing at a requirement that does not exist; the
 * coverage matrix never counts the test; and a run's conformance verdict is computed from
 * requirement <em>levels</em>, so a MUST whose IRI is misspelled stops being a MUST and its
 * failure stops deciding conformance. None of that surfaces as an error, which is why a
 * typo (`authz-token-validation-verification` for `…-checklist`) survived in five manifests.
 *
 * <p>So the check is made before a run starts, not after: a front end that cannot resolve a
 * declared requirement refuses to run rather than producing a report whose provenance is
 * wrong.
 */
public final class RequirementRefs {

    /** One manifest naming one requirement IRI that is not in the catalog. */
    public record Dangling(String manifestId, String iri) {
    }

    private RequirementRefs() {
    }

    /**
     * Every (manifest, IRI) pair where the IRI is absent from {@code catalog}, in manifest
     * order. Empty when every declared requirement resolves.
     *
     * <p>An empty catalog yields no findings: it means no catalog was configured, which is a
     * separate condition from a manifest citing something that is not there.
     */
    public static List<Dangling> unresolved(Collection<Manifest> manifests, Collection<Requirement> catalog) {
        if (catalog.isEmpty()) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        catalog.forEach(r -> known.add(r.iri()));
        return manifests.stream()
                .flatMap(m -> m.requirements().stream()
                        .filter(iri -> !known.contains(iri))
                        .map(iri -> new Dangling(m.id(), iri)))
                .toList();
    }

    /** The findings as one operator-readable message naming each manifest and IRI. */
    public static String describe(List<Dangling> dangling) {
        StringBuilder sb = new StringBuilder(dangling.size() + " declared requirement(s) are not in the catalog");
        for (Dangling d : dangling) {
            sb.append("\n  ").append(d.manifestId()).append(" -> ").append(d.iri());
        }
        return sb.toString();
    }
}
