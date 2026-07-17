package com.ebremer.touchstone.core.report;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.results.AssertionResult;
import com.ebremer.touchstone.core.results.HttpExchangeTrace;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RunRecordsTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripsAFullRunRecordIncludingTraces() {
        HttpExchangeTrace trace = HttpExchangeTrace.of(
                "POST", URI.create("http://sut/c/"),
                Map.of("Authorization", List.of("Bearer secret"), "Slug", List.of("note")),
                "hello", 201,
                Map.of("Location", List.of("http://sut/c/note")), "");
        TestResult test = new TestResult(
                "core/example", List.of("https://example.org/touchstone/req/lws10-core/x"),
                Outcome.FAILED,
                List.of(new StepResult("create", trace,
                        List.of(AssertionResult.failed("status", "[200]", "201")), null)),
                77, null);
        RunResult run = new RunResult("ref", "http://sut/", "abc123", "2026-07-16T21:00:00Z", List.of(test));

        Path dir = tmp.resolve("runs").resolve("abc123");
        RunRecords.save(run, dir.resolve("run.json"));

        // load via the directory form too
        RunResult reloaded = RunRecords.load(dir);
        assertThat(reloaded).isEqualTo(run);
        // redaction happened before persistence
        assertThat(reloaded.results().getFirst().steps().getFirst().trace().requestHeaders())
                .containsEntry("Authorization", List.of("[REDACTED]"));
    }
}
