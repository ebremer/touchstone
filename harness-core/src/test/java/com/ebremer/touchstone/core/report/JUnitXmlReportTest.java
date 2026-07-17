package com.ebremer.touchstone.core.report;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static com.ebremer.touchstone.core.report.ReportTestData.REQ_A;
import static com.ebremer.touchstone.core.report.ReportTestData.run;
import static com.ebremer.touchstone.core.report.ReportTestData.test;
import static org.assertj.core.api.Assertions.assertThat;

class JUnitXmlReportTest {

    @Test
    void rendersWellFormedSuiteWithCounts() throws Exception {
        RunResult run = run(
                test("core/pass", Outcome.PASSED, REQ_A),
                test("core/fail", Outcome.FAILED, REQ_A),
                test("core/error", Outcome.ERROR, REQ_A),
                test("core/skip", Outcome.SKIPPED, REQ_A));
        String xml = JUnitXmlReport.render(run);

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element suite = doc.getDocumentElement();

        assertThat(suite.getTagName()).isEqualTo("testsuite");
        assertThat(suite.getAttribute("tests")).isEqualTo("4");
        assertThat(suite.getAttribute("failures")).isEqualTo("1");
        assertThat(suite.getAttribute("errors")).isEqualTo("1");
        assertThat(suite.getAttribute("skipped")).isEqualTo("1");
        assertThat(doc.getElementsByTagName("testcase").getLength()).isEqualTo(4);
        assertThat(doc.getElementsByTagName("failure").getLength()).isEqualTo(1);
        assertThat(doc.getElementsByTagName("error").getLength()).isEqualTo(1);
        assertThat(doc.getElementsByTagName("skipped").getLength()).isEqualTo(1);
    }

    @Test
    void escapesMarkupInDetails() throws Exception {
        RunResult run = run(new com.ebremer.touchstone.core.results.TestResult(
                "core/markup", java.util.List.of(REQ_A), Outcome.SKIPPED, java.util.List.of(), 1,
                "reason with <angle> & \"quotes\""));
        String xml = JUnitXmlReport.render(run);

        // must parse despite markup-hostile characters in attribute values
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element skipped = (Element) doc.getElementsByTagName("skipped").item(0);
        assertThat(skipped.getAttribute("message")).isEqualTo("reason with <angle> & \"quotes\"");
    }
}
