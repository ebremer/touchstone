package com.ebremer.touchstone.core.results;

/** Human-readable rendering of results for the CLI, the reports and JUnit failure messages. */
public final class Results {

    private Results() {
    }

    public static String describe(TestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.testId()).append(" - ").append(result.outcome().earl());
        if (result.level() != null) {
            sb.append(" [").append(result.level()).append(']');
        }
        sb.append(" (").append(result.durationMillis()).append(" ms)");
        if (result.reason() != null) {
            sb.append("\n  ").append(result.outcome().earl()).append(": ").append(result.reason());
        }
        for (StepResult step : result.steps()) {
            boolean interesting = step.error() != null || step.failed();
            if (!interesting) {
                continue;
            }
            sb.append("\n  step");
            if (step.name() != null) {
                sb.append(" '").append(step.name()).append('\'');
            }
            sb.append(':');
            // Failed assertions first: when a step has both, the assertion is what decided the
            // test, and the error is what it cost the steps after it (D-0049).
            for (AssertionResult a : step.assertions()) {
                if (!a.passed()) {
                    sb.append("\n    FAILED ").append(a.description())
                            .append("\n      expected: ").append(a.expected())
                            .append("\n      actual:   ").append(a.actual());
                }
            }
            if (step.error() != null) {
                sb.append("\n    error: ").append(step.error());
            }
            if (step.trace() != null) {
                sb.append("\n    exchange: ").append(step.trace().method()).append(' ')
                        .append(step.trace().uri()).append(" -> ").append(step.trace().status());
                if (step.trace().responseBody() != null && !step.trace().responseBody().isEmpty()) {
                    sb.append("\n    response body: ").append(step.trace().responseBody());
                }
            }
        }
        return sb.toString();
    }
}
