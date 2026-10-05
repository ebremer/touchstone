package com.ebremer.touchstone.core.definitions;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One client rule, an {@code ObservationTest} (OBSERVATION.md): it selects the exchanges of a
 * client session that {@link #observe()} holds of, and each passes when {@link #expect()} holds
 * of it.
 *
 * @param id           {@code <manifest path without extension>#<name>}, e.g. {@code clients/core#client-put-conditional}
 * @param manifestPath the manifest's path under {@code lws10/} without extension
 * @param iri          the rule's IRI, as its JSON-LD expansion gives it
 * @param comment      the rule's comment, or null
 * @param area         core, authentication, notifications or index
 * @param observe      the condition that selects trials, {@code after} included
 * @param expect       the condition each trial must satisfy
 * @param guidance     how a client fixes a failure
 * @param task         what the developer is asked to do so the rule can be tried, or null (since 0.9.0)
 */
public record RuleDefinition(
        String id,
        String manifestPath,
        String name,
        String iri,
        String label,
        String comment,
        String level,
        String status,
        List<String> source,
        List<String> traits,
        String area,
        List<String> requirements,
        JsonNode observe,
        JsonNode expect,
        String guidance,
        Task task) {

    /**
     * A rule's task (OBSERVATION.md section 6.1).
     *
     * @param prompt what the developer reads
     * @param arm    the fault starting the task arms, or null
     */
    public record Task(String prompt, String arm) {
    }

    /** Whether each start of the task opens a trial: a task without {@code after} (section 6.1). */
    public boolean taskTriggered() {
        return task != null && !observe.has("after");
    }
}
