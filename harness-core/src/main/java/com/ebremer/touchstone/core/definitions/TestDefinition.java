package com.ebremer.touchstone.core.definitions;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One test of the YAML-LD definitions. The short form is already desugared: {@link #steps()}
 * always holds at least one step (EXECUTION.md section 4.2).
 *
 * @param id           {@code <manifest path without extension>#<name>}, e.g.
 *                     {@code core/containers#getContainer} (EXECUTION.md section 2)
 * @param manifestPath the manifest's path under {@code lws10/} without extension
 * @param iri          the test's IRI, as its JSON-LD expansion gives it
 * @param identity     the test's {@code as}, or null (alice)
 * @param prereqs      the {@code prereqs} object, or null
 * @param directory    the manifest's directory, against which {@code bodyURL} resolves
 */
public record TestDefinition(
        String id,
        String manifestPath,
        String name,
        String iri,
        String type,
        String label,
        String level,
        String status,
        List<String> source,
        List<String> traits,
        List<String> requires,
        String identity,
        List<String> requirements,
        JsonNode prereqs,
        List<StepDefinition> steps,
        Path directory) {

    /** The manifest's module: the first segment of its path ({@code core}, {@code auth}). */
    public String module() {
        int slash = manifestPath.indexOf('/');
        return slash < 0 ? manifestPath : manifestPath.substring(0, slash);
    }
}
