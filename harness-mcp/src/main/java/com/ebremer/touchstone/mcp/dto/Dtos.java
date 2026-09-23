package com.ebremer.touchstone.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * Tight structured summaries returned by the MCP tools (DESIGN.md paragraph 6: never dump
 * full reports into a response). SUT-derived content in traces is untrusted input to the
 * agent (paragraph 7.3) and is labelled as such.
 */
public final class Dtos {

    private Dtos() {
    }

    public record RequirementSummary(String iri, String level, String module, String section, String summary) {
    }

    public record RequirementDetail(
            String iri, String level, String module, String section, String status,
            String summary, String clauseText) {
    }

    /** A test's metadata: {@code requires} names the capabilities a target must declare for it to apply. */
    public record TestSummary(
            String id, String label, String level, String type, String manifest, List<String> requirements,
            List<String> requires, List<String> traits) {
    }

    public record CoverageCell(String module, String level, long covered, long total) {
    }

    public record CoverageReportDto(long covered, long total, List<CoverageCell> byLevel) {
    }

    public record StartRunResult(String runId, String status, String target, String selector, int total) {
    }

    public record LevelCounts(String level, long passed, long failed, long cantTell, long inapplicable) {
    }

    /**
     * A run's status. {@code conformant} is the verdict of definitions/EXECUTION.md section 9:
     * no MUST test failed or ended cantTell.
     */
    public record RunStatusDto(
            String runId, String target, String selector, String status, String startedAt,
            int completed, int total,
            long passed, long failed, long cantTell, long inapplicable,
            boolean conformant, List<LevelCounts> byLevel) {
    }

    public record FailureSummary(String testId, String level, String outcome, List<String> requirements,
                                 int failingStep, String reason) {
    }

    public record FailuresPage(
            String runId, int page, int pageSize, int totalFailures, int totalPages,
            List<FailureSummary> failures) {
    }

    public record AssertionDetail(String description, boolean passed, String expected, String actual) {
    }

    public record ExchangeDto(
            String method, String uri, Integer status,
            Map<String, List<String>> requestHeaders, String requestBody,
            Map<String, List<String>> responseHeaders, String responseBody) {
    }

    public record StepTraceDto(
            String name, ExchangeDto exchange, List<AssertionDetail> assertions, String error) {
    }

    public record TraceDto(
            String runId, String testId, List<String> requirements, String outcome,
            String untrustedNote, List<StepTraceDto> steps) {
    }

    public record TransitionDto(String testId, String before, String after) {
    }

    public record DiffDto(
            String before, String after,
            List<TransitionDto> regressions, List<TransitionDto> fixes, List<TransitionDto> otherChanges,
            List<String> added, List<String> removed, long unchanged, boolean hasRegressions) {
    }

    /**
     * One rendering of a completed run's report.
     *
     * <p>{@code content} is null for a binary format: an agent cannot read a PDF as text and
     * base64 of it would cost thousands of tokens to say nothing. {@code path} and {@code bytes}
     * are always populated, so the caller can hand the file to whoever can open it.
     */
    public record ReportDto(
            String runId, String format, String mediaType, String path, long bytes, String content) {
    }
}
