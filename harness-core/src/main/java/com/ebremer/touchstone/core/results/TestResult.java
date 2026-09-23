package com.ebremer.touchstone.core.results;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Result of one test, keyed to the requirements it verifies.
 *
 * @param testId the test's identity, {@code <manifest path>#<name>} (EXECUTION.md section 2)
 * @param level  MUST, SHOULD or MAY: the test's one level, which decides whether its failure
 *               bears on conformance (EXECUTION.md section 9). Null in records written before
 *               tests had levels; those count as MUST.
 * @param reason why the test was inapplicable or could not tell; null when it passed or failed
 */
public record TestResult(
        @JsonAlias("manifestId") String testId,
        String label,
        String level,
        List<String> requirements,
        Outcome outcome,
        List<StepResult> steps,
        long durationMillis,
        @JsonAlias("skipReason") String reason) {

    /** True when a failure of this test decides conformance. */
    public boolean decidesConformance() {
        return level == null || "MUST".equals(level);
    }
}
