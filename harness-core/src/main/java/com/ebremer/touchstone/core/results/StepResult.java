package com.ebremer.touchstone.core.results;

import java.util.List;

/** Result of one manifest step: its (redacted) exchange, assertions, and any harness error. */
public record StepResult(
        String name,
        HttpExchangeTrace trace,
        List<AssertionResult> assertions,
        String error) {

    public boolean failed() {
        return assertions.stream().anyMatch(a -> !a.passed());
    }
}
