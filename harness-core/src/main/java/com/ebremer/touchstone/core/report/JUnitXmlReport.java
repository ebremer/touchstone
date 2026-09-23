package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;

/**
 * JUnit XML for CI (DESIGN.md section 5.5). A test case's class is its manifest
 * ({@code core/containers}) and its name the test's name. cantTell is a JUnit error,
 * inapplicable a skip, and a failure of a SHOULD or MAY test a failure too, so CI shows it;
 * the conformance verdict, which only MUST tests decide, is in the report.
 */
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
        long errors = run.count(Outcome.CANT_TELL);
        long skipped = run.count(Outcome.INAPPLICABLE) + run.count(Outcome.UNTESTED);
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
            String id = test.testId();
            int hash = id.indexOf('#');
            String classname = hash < 0 ? "touchstone" : id.substring(0, hash);
            String name = hash < 0 ? id : id.substring(hash + 1);
            xml.append("  <testcase classname=\"").append(escape(classname))
                    .append("\" name=\"").append(escape(name))
                    .append("\" time=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", test.durationMillis() / 1000.0))
                    .append("\"");
            switch (test.outcome()) {
                case PASSED -> xml.append("/>\n");
                case FAILED -> xml.append(">\n    <failure message=\"").append(escape(level(test)))
                        .append(" expectation failed\">")
                        .append(escape(Results.describe(test))).append("</failure>\n  </testcase>\n");
                case CANT_TELL -> xml.append(">\n    <error message=\"cantTell\">")
                        .append(escape(Results.describe(test))).append("</error>\n  </testcase>\n");
                case INAPPLICABLE, UNTESTED -> xml.append(">\n    <skipped message=\"")
                        .append(escape(test.reason() == null ? test.outcome().earl() : test.reason()))
                        .append("\"/>\n  </testcase>\n");
            }
        }
        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private static String level(TestResult test) {
        return test.level() == null ? "MUST" : test.level();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
