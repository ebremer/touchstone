package com.ebremer.touchstone.core.definitions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One exchange of a test: a request and the expectations on its response (EXECUTION.md
 * sections 6 and 7). Both stay in their JSON form, whose shape the schema fixes.
 *
 * @param identity     the step's own {@code as}, or null to inherit the test's
 * @param precondition a failed expectation here makes the test inapplicable, not failed
 */
public record StepDefinition(
        String label,
        String identity,
        boolean precondition,
        JsonNode request,
        JsonNode response) {
}
