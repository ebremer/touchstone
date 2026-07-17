package com.ebremer.touchstone.core.report;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import org.junit.jupiter.api.Test;

import static com.ebremer.touchstone.core.report.ReportTestData.REQ_A;
import static com.ebremer.touchstone.core.report.ReportTestData.run;
import static com.ebremer.touchstone.core.report.ReportTestData.test;
import static org.assertj.core.api.Assertions.assertThat;

class RunDiffTest {

    @Test
    void categorizesTransitions() {
        RunResult before = run(
                test("core/stays-green", Outcome.PASSED, REQ_A),
                test("core/regresses", Outcome.PASSED, REQ_A),
                test("core/gets-fixed", Outcome.FAILED, REQ_A),
                test("core/goes-weird", Outcome.SKIPPED, REQ_A),
                test("core/disappears", Outcome.PASSED, REQ_A));
        RunResult after = run(
                test("core/stays-green", Outcome.PASSED, REQ_A),
                test("core/regresses", Outcome.ERROR, REQ_A),
                test("core/gets-fixed", Outcome.PASSED, REQ_A),
                test("core/goes-weird", Outcome.PASSED, REQ_A),
                test("core/brand-new", Outcome.PASSED, REQ_A));

        RunDiff diff = RunDiff.compare(before, after);

        assertThat(diff.regressions()).containsExactly(
                new RunDiff.Transition("core/regresses", Outcome.PASSED, Outcome.ERROR));
        assertThat(diff.fixes()).containsExactly(
                new RunDiff.Transition("core/gets-fixed", Outcome.FAILED, Outcome.PASSED));
        assertThat(diff.otherChanges()).containsExactly(
                new RunDiff.Transition("core/goes-weird", Outcome.SKIPPED, Outcome.PASSED));
        assertThat(diff.added()).containsExactly("core/brand-new");
        assertThat(diff.removed()).containsExactly("core/disappears");
        assertThat(diff.unchanged()).isEqualTo(1);
        assertThat(diff.hasRegressions()).isTrue();
    }
}
