package com.ebremer.touchstone.core.results;

import java.util.List;

/**
 * All test results of one run against one target. Serializable to runs/&lt;id&gt;/run.json
 * (see report.RunRecords) — the record the reports, run diffs, and the MCP layer read.
 */
public record RunResult(
        String targetId,
        String targetBaseUrl,
        String runId,
        String startedAt,
        List<TestResult> results) {

    public long count(Outcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }

    public boolean conformant() {
        return count(Outcome.FAILED) == 0 && count(Outcome.ERROR) == 0;
    }
}
