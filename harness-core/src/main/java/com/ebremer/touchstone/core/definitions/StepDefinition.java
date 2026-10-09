package com.ebremer.touchstone.core.definitions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One exchange of a test: a request and the expectations on its response (EXECUTION.md
 * sections 6 and 7). Both stay in their JSON form, whose shape the schema fixes.
 *
 * @param identity     the step's own {@code as}, or null to inherit the test's
 * @param precondition a failed expectation here makes the test inapplicable, not failed
 * @param pollWithin   seconds a polled step keeps re-sending before its last attempt is judged,
 *                     or 0 for an ordinary step (EXECUTION.md section 4.4)
 * @param pollEvery    seconds between the attempts of a polled step, or 0
 * @param pages        the most pages of a paged result the step reads by following
 *                     {@code rel="next"}, or 0 to read only the response (EXECUTION.md section 4.6)
 */
public record StepDefinition(
        String label,
        String identity,
        boolean precondition,
        JsonNode request,
        JsonNode response,
        int pollWithin,
        int pollEvery,
        int pages) {

    /** An ordinary step: sent once and judged on that response. */
    public StepDefinition(String label, String identity, boolean precondition, JsonNode request, JsonNode response) {
        this(label, identity, precondition, request, response, 0, 0, 0);
    }

    /** A step that reads one response, polled when {@code pollWithin} is positive. */
    public StepDefinition(String label, String identity, boolean precondition, JsonNode request, JsonNode response,
                          int pollWithin, int pollEvery) {
        this(label, identity, precondition, request, response, pollWithin, pollEvery, 0);
    }

    public boolean polls() {
        return pollWithin > 0;
    }

    /** Whether the step reads a paged result whole (EXECUTION.md section 4.6). */
    public boolean readsPages() {
        return pages > 0;
    }
}
