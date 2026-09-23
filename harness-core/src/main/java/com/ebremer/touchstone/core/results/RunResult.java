package com.ebremer.touchstone.core.results;

import java.util.List;

/**
 * All test results of one run against one target. Serializable to runs/&lt;id&gt;/run.json
 * (see report.RunRecords): the record the reports, run diffs and the MCP layer read.
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

    /**
     * The verdict of EXECUTION.md section 9: the target conforms when no MUST test failed and
     * no MUST test ended cantTell. SHOULD and MAY failures are advisory, and an inapplicable
     * MUST test is coverage the run did not have, not evidence either way.
     */
    public boolean conformant() {
        return results.stream().noneMatch(r -> r.decidesConformance()
                && (r.outcome() == Outcome.FAILED || r.outcome() == Outcome.CANT_TELL));
    }

    /** The MUST tests that decided a non-conformant verdict. */
    public List<TestResult> mustFailures() {
        return results.stream().filter(r -> r.decidesConformance()
                && (r.outcome() == Outcome.FAILED || r.outcome() == Outcome.CANT_TELL)).toList();
    }
}
