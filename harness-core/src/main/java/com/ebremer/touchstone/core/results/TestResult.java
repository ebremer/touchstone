package com.ebremer.touchstone.core.results;

import java.util.List;

/** Result of one manifest execution, keyed to the requirements it verifies. */
public record TestResult(
        String manifestId,
        List<String> requirements,
        Outcome outcome,
        List<StepResult> steps,
        long durationMillis,
        String skipReason) {

    public static TestResult skipped(String manifestId, List<String> requirements, String reason) {
        return new TestResult(manifestId, requirements, Outcome.SKIPPED, List.of(), 0, reason);
    }
}
