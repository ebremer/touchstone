package com.ebremer.touchstone.core.results;

import java.util.List;

/** All test results of one run against one target. */
public record RunResult(String targetId, String runId, List<TestResult> results) {

    public long count(Outcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }

    public boolean conformant() {
        return count(Outcome.FAILED) == 0 && count(Outcome.ERROR) == 0;
    }
}
