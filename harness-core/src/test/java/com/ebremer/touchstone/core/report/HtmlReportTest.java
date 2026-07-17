package com.ebremer.touchstone.core.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.ebremer.touchstone.core.report.ReportTestData.REQ_A;
import static com.ebremer.touchstone.core.report.ReportTestData.REQ_B;
import static com.ebremer.touchstone.core.report.ReportTestData.REQ_UNCOVERED;
import static com.ebremer.touchstone.core.report.ReportTestData.run;
import static com.ebremer.touchstone.core.report.ReportTestData.test;
import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportTest {

    @TempDir
    Path tmp;

    private static final List<Requirement> CATALOG = List.of(
            new Requirement(REQ_A, "MUST", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#read-resource", "Alpha requirement.", "Approved"),
            new Requirement(REQ_B, "SHOULD", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#metadata", "Beta requirement.", "Draft"),
            new Requirement(REQ_UNCOVERED, "MAY", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#containers", "Gamma requirement.", "Draft"));

    @Test
    void matrixLinksTestsToRequirementsToSpecSections() throws Exception {
        RunResult run = run(
                test("core/pass", Outcome.PASSED, REQ_A, REQ_B),
                test("core/fail", Outcome.FAILED, REQ_A));
        Path file = tmp.resolve("report.html");
        HtmlReport.write(run, CATALOG, file);
        String html = Files.readString(file);

        // requirement rows link to spec sections
        assertThat(html).contains("href=\"https://www.w3.org/TR/lws10-core/#read-resource\"");
        // requirement rows anchor, tests link to them
        assertThat(html).contains("id=\"r-alpha-must\"").contains("href=\"#r-alpha-must\"");
        // test sections anchor, matrix links to them
        assertThat(html).contains("id=\"t-core-pass\"").contains("href=\"#t-core-pass\"");
        // uncovered requirement is visible as such
        assertThat(html).contains("UNCOVERED");
        // a MUST failure flips the verdict
        assertThat(html).contains("NON-CONFORMANT");
    }

    @Test
    void conformantWhenOnlyAdvisoryLevelsFail() throws Exception {
        RunResult run = run(
                test("core/pass", Outcome.PASSED, REQ_A),
                test("core/should-fail", Outcome.FAILED, REQ_B));
        Path file = tmp.resolve("advisory.html");
        HtmlReport.write(run, CATALOG, file);
        String html = Files.readString(file);

        assertThat(html).contains("No MUST-level failures");
    }
}
