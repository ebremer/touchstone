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

    private static final String REQ_CLIENT = "https://example.org/touchstone/req/lws10-core/client-only";
    private static final String REQ_CLIENT_CITED = "https://example.org/touchstone/req/lws10-core/client-cited";

    private static final List<Requirement> CATALOG = List.of(
            new Requirement(REQ_A, "MUST", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#read-resource", "Alpha requirement.", "Alpha clause.", "Approved",
                    List.of("Server")),
            new Requirement(REQ_B, "SHOULD", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#metadata", "Beta requirement.", "Beta clause.", "Draft",
                    List.of("Server")),
            new Requirement(REQ_UNCOVERED, "MAY", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#containers", "Gamma requirement.", "Gamma clause.", "Draft",
                    List.of("Server")),
            new Requirement(REQ_CLIENT, "MUST", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#pagination", "Client requirement.", "Clients MUST.", "Draft",
                    List.of("Client")),
            new Requirement(REQ_CLIENT_CITED, "MUST", "lws10-core",
                    "https://www.w3.org/TR/lws10-core/#authorization", "Cited client requirement.", "Clients MUST.",
                    "Draft", List.of("Client")));


    @Test
    void matrixLinksTestsToRequirementsToSpecSections() throws Exception {
        RunResult run = run(
                test("core/x#pass", Outcome.PASSED, REQ_A, REQ_B),
                test("core/x#fail", Outcome.FAILED, REQ_A));
        Path file = tmp.resolve("report.html");
        HtmlReport.write(run, CATALOG, file);
        String html = Files.readString(file);

        // requirement rows link to spec sections
        assertThat(html).contains("href=\"https://www.w3.org/TR/lws10-core/#read-resource\"");
        // requirement rows anchor, tests link to them
        assertThat(html).contains("id=\"r-alpha-must\"").contains("href=\"#r-alpha-must\"");
        // test sections anchor, matrix links to them
        assertThat(html).contains("id=\"t-core-x-pass\"").contains("href=\"#t-core-x-pass\"");
        // uncovered requirement is visible as such
        assertThat(html).contains("UNCOVERED");
        // a MUST failure flips the verdict
        assertThat(html).contains("NON-CONFORMANT");
    }

    @Test
    void aClientOnlyRequirementIsLeftOutUnlessATestCitesIt() throws Exception {
        // A server run answers for server-side clauses (D-0076); a client clause a test cites as a
        // premise stays in the matrix, so that the test's link to it resolves, but not in coverage.
        RunResult run = run(test("core/x#pass", Outcome.PASSED, REQ_A, REQ_CLIENT_CITED));
        Path file = tmp.resolve("roles.html");
        HtmlReport.write(run, CATALOG, file);
        String html = Files.readString(file);

        assertThat(html).contains("id=\"r-client-cited\"").doesNotContain("id=\"r-client-only\"")
                .contains("2 catalog requirements bind only clients")
                // the coverage table counts the three server-side requirements only
                .contains("<td>MUST</td><td>1</td>");
    }

    @Test
    void conformantWhenOnlyAdvisoryLevelsFail() throws Exception {
        // The verdict follows each test's own level (EXECUTION.md section 9): a failed SHOULD
        // test is advisory, and an inapplicable MUST test is missing coverage, not a failure.
        RunResult run = run(
                test("core/x#pass", Outcome.PASSED, REQ_A),
                test("core/x#should-fail", "SHOULD", Outcome.FAILED, REQ_B),
                test("core/x#must-inapplicable", Outcome.INAPPLICABLE, REQ_A));
        Path file = tmp.resolve("advisory.html");
        HtmlReport.write(run, CATALOG, file);
        String html = Files.readString(file);

        assertThat(run.conformant()).isTrue();
        assertThat(html).contains("CONFORMANT &mdash; no MUST test failed").contains("of which this run has 1")
                .contains("1 MUST test(s) were inapplicable");
    }

    @Test
    void aMustTestThatCannotTellIsNotConformant() {
        RunResult run = run(test("core/x#pass", Outcome.PASSED, REQ_A),
                test("core/x#unsure", Outcome.CANT_TELL, REQ_A));
        assertThat(run.conformant()).isFalse();
        assertThat(run.mustFailures()).extracting(r -> r.testId()).containsExactly("core/x#unsure");
    }
}
