package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code report.json} — the report, as data.
 *
 * <p>Distinct from {@code run.json}, which is the raw {@link RunResult}: every step, every
 * redacted HTTP exchange, every assertion. That file is the evidence and it is large. This one
 * is the finding — totals, per-level coverage, the requirement matrix with each requirement's
 * verdict, and the tests with their outcomes — which is what a dashboard, a gate, or a person
 * skimming a hundred runs actually reads.
 *
 * <p>It serialises {@link HtmlReport#model} verbatim, and that is the point: {@code report.html}
 * and {@code report.pdf} render the same map. The three cannot drift into disagreeing about
 * whether a run conformed, because there is one computation of what conforming means.
 */
public final class JsonReport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonReport() {
    }

    public static void write(RunResult run, List<Requirement> catalog, Path file) {
        Map<String, Object> model = HtmlReport.model(run, catalog);
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), model);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write JSON report " + file, e);
        }
    }
}
