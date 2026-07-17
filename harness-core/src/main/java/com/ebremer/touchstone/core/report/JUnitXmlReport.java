package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;

/** JUnit XML for CI (DESIGN.md paragraph 5.5). */
public final class JUnitXmlReport {

    private JUnitXmlReport() {
    }

    public static void write(RunResult run, Path file) {
        try {
            Files.writeString(file, render(run));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write JUnit XML report " + file, e);
        }
    }

    static String render(RunResult run) {
        long failures = run.count(Outcome.FAILED);
        long errors = run.count(Outcome.ERROR);
        long skipped = run.count(Outcome.SKIPPED);
        double total = run.results().stream().mapToLong(TestResult::durationMillis).sum() / 1000.0;

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"touchstone\" tests=\"").append(run.results().size())
                .append("\" failures=\"").append(failures)
                .append("\" errors=\"").append(errors)
                .append("\" skipped=\"").append(skipped)
                .append("\" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", total))
                .append("\" timestamp=\"").append(escape(run.startedAt())).append("\">\n");
        for (TestResult test : run.results()) {
            String module = test.manifestId().substring(0, Math.max(test.manifestId().indexOf('/'), 0));
            xml.append("  <testcase classname=\"").append(escape(module.isEmpty() ? "touchstone" : module))
                    .append("\" name=\"").append(escape(test.manifestId()))
                    .append("\" time=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", test.durationMillis() / 1000.0))
                    .append("\"");
            switch (test.outcome()) {
                case PASSED -> xml.append("/>\n");
                case FAILED -> xml.append(">\n    <failure message=\"assertion failed\">")
                        .append(escape(Results.describe(test))).append("</failure>\n  </testcase>\n");
                case ERROR -> xml.append(">\n    <error message=\"harness error\">")
                        .append(escape(Results.describe(test))).append("</error>\n  </testcase>\n");
                case SKIPPED -> xml.append(">\n    <skipped message=\"")
                        .append(escape(test.skipReason() == null ? "skipped" : test.skipReason()))
                        .append("\"/>\n  </testcase>\n");
            }
        }
        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
