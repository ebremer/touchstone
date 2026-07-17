package com.ebremer.touchstone.core.report;

import com.ebremer.touchstone.core.Touchstone;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import static com.ebremer.touchstone.core.report.ReportTestData.REQ_A;
import static com.ebremer.touchstone.core.report.ReportTestData.run;
import static com.ebremer.touchstone.core.report.ReportTestData.test;
import static org.assertj.core.api.Assertions.assertThat;

class EarlReportTest {

    private static final String EARL = "http://www.w3.org/ns/earl#";

    @Test
    void emitsOneAssertionPerTestWithMappedOutcomes() {
        RunResult run = run(
                test("core/pass", Outcome.PASSED, REQ_A),
                test("core/fail", Outcome.FAILED, REQ_A),
                test("core/error", Outcome.ERROR, REQ_A),
                test("core/skip", Outcome.SKIPPED, REQ_A));
        Model m = EarlReport.model(run);

        Resource assertion = m.createResource(EARL + "Assertion");
        assertThat(m.listResourcesWithProperty(RDF.type, assertion).toList()).hasSize(4);

        assertThat(outcomes(m)).containsExactlyInAnyOrder(
                EARL + "passed", EARL + "failed", EARL + "cantTell", EARL + "inapplicable");

        // test cases carry their requirement links and minted IRIs
        Property verifies = m.createProperty(Touchstone.VOCAB_NS, "verifies");
        Resource testCase = m.createResource(Touchstone.TEST_NS + "core/pass");
        assertThat(m.contains(testCase, verifies, m.createResource(REQ_A))).isTrue();

        // subject is the target storage
        Resource subject = m.createResource("http://localhost:4711/");
        assertThat(m.contains(subject, RDF.type, m.createResource(EARL + "TestSubject"))).isTrue();
    }

    private static java.util.List<String> outcomes(Model m) {
        Property outcome = m.createProperty(EARL, "outcome");
        return m.listObjectsOfProperty(outcome).toList().stream()
                .map(n -> n.asResource().getURI()).toList();
    }
}
