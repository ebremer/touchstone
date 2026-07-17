package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.report.RunRecords;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class DiffCommandTest {

    @TempDir
    Path tmp;

    private static TestResult test(String id, Outcome outcome) {
        return new TestResult(id, List.of("https://example.org/touchstone/req/lws10-core/x"),
                outcome, List.of(), 5, null);
    }

    @Test
    void reportsRegressionsWithExitOne() {
        RunResult before = new RunResult("ref", "http://sut/", "aaa", "2026-07-16T20:00:00Z",
                List.of(test("core/one", Outcome.PASSED), test("core/two", Outcome.FAILED)));
        RunResult after = new RunResult("ref", "http://sut/", "bbb", "2026-07-16T21:00:00Z",
                List.of(test("core/one", Outcome.FAILED), test("core/two", Outcome.PASSED)));
        RunRecords.save(before, tmp.resolve("aaa").resolve("run.json"));
        RunRecords.save(after, tmp.resolve("bbb").resolve("run.json"));

        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setOut(new PrintWriter(out));

        int exit = cmd.execute("diff", tmp.resolve("aaa").toString(), tmp.resolve("bbb").toString());

        assertThat(exit).isEqualTo(1);
        assertThat(out.toString())
                .contains("regressions:")
                .contains("core/one: PASSED -> FAILED")
                .contains("fixes:")
                .contains("core/two: FAILED -> PASSED");
    }

    @Test
    void identicalRunsExitZero() {
        RunResult run = new RunResult("ref", "http://sut/", "aaa", "2026-07-16T20:00:00Z",
                List.of(test("core/one", Outcome.PASSED)));
        RunRecords.save(run, tmp.resolve("a").resolve("run.json"));
        RunRecords.save(run, tmp.resolve("b").resolve("run.json"));

        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setOut(new PrintWriter(out));

        int exit = cmd.execute("diff", tmp.resolve("a").toString(), tmp.resolve("b").toString());

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("no regressions").contains("1 unchanged");
    }
}
