package com.ebremer.touchstone.core.results;

/** Human-readable rendering of results for the CLI and JUnit failure messages. */
public final class Results {

    private Results() {
    }

    public static String describe(TestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.manifestId()).append(" - ").append(result.outcome())
                .append(" (").append(result.durationMillis()).append(" ms)");
        if (result.skipReason() != null) {
            sb.append("\n  skipped: ").append(result.skipReason());
        }
        int i = 0;
        for (StepResult step : result.steps()) {
            i++;
            boolean interesting = step.error() != null || step.failed();
            if (!interesting) {
                continue;
            }
            sb.append("\n  step ").append(i);
            if (step.name() != null) {
                sb.append(" '").append(step.name()).append('\'');
            }
            sb.append(':');
            if (step.error() != null) {
                sb.append("\n    error: ").append(step.error());
            }
            for (AssertionResult a : step.assertions()) {
                if (!a.passed()) {
                    sb.append("\n    FAILED ").append(a.description())
                            .append("\n      expected: ").append(a.expected())
                            .append("\n      actual:   ").append(a.actual());
                }
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
