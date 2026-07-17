package com.ebremer.touchstone.core.report;

import java.util.List;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;

/** Shared synthetic run data for report tests. */
final class ReportTestData {

    static final String REQ_A = "https://example.org/touchstone/req/lws10-core/alpha-must";
    static final String REQ_B = "https://example.org/touchstone/req/lws10-core/beta-should";
    static final String REQ_UNCOVERED = "https://example.org/touchstone/req/lws10-core/gamma-uncovered";

    private ReportTestData() {
    }

    static TestResult test(String id, Outcome outcome, String... requirements) {
        return new TestResult(id, List.of(requirements), outcome, List.of(), 42,
                outcome == Outcome.SKIPPED ? "target lacks capabilities [x]" : null);
    }

    static RunResult run(TestResult... tests) {
        return new RunResult("ref", "http://localhost:4711/", "run1", "2026-07-16T21:00:00Z", List.of(tests));
    }
}
