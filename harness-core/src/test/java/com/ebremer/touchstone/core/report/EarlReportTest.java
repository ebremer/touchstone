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
                test("core/x#pass", Outcome.PASSED, REQ_A),
                test("core/x#fail", Outcome.FAILED, REQ_A),
                test("core/x#error", Outcome.CANT_TELL, REQ_A),
                test("core/x#skip", Outcome.INAPPLICABLE, REQ_A));
        Model m = EarlReport.model(run);

        Resource assertion = m.createResource(EARL + "Assertion");
        assertThat(m.listResourcesWithProperty(RDF.type, assertion).toList()).hasSize(4);

        assertThat(outcomes(m)).containsExactlyInAnyOrder(
                EARL + "passed", EARL + "failed", EARL + "cantTell", EARL + "inapplicable");

        // test cases carry their requirement links and minted IRIs
        Property verifies = m.createProperty(Touchstone.VOCAB_NS, "verifies");
        Resource testCase = m.createResource(Touchstone.TEST_NS + "lws10/core/x#pass");
        assertThat(m.contains(testCase, verifies, m.createResource(REQ_A))).isTrue();

        // subject is the target storage
        Resource subject = m.createResource("http://localhost:4711/");
        assertThat(m.contains(subject, RDF.type, m.createResource(EARL + "TestSubject"))).isTrue();
    }

    @Test
    void aSemiAutomaticReportIsAboutTheNamedProjectAndSaysWhyAResultDidNotPass() {
        RunResult run = run(
                test("clients/core#pass", Outcome.PASSED, REQ_A),
                test("clients/core#skip", Outcome.INAPPLICABLE, REQ_A));
        Model m = EarlReport.model(run, new EarlReport.Subject("https://client.example/", "client 'Example' 1.0",
                "Example", "1.0", "https://client.example/"), "semiAuto");
        Property mode = m.createProperty(EARL, "mode");
        assertThat(m.listObjectsOfProperty(mode).toList()).containsExactly(m.createResource(EARL + "semiAuto"));
        Resource client = m.createResource("https://client.example/");
        String doap = "http://usefulinc.com/ns/doap#";
        assertThat(m.contains(client, RDF.type, m.createResource(doap + "Project"))).isTrue();
        assertThat(m.contains(client, m.createProperty(doap, "name"), "Example")).isTrue();
        assertThat(m.contains(client, m.createProperty(doap, "homepage"), client)).isTrue();
        Resource release = client.getPropertyResourceValue(m.createProperty(doap, "release"));
        assertThat(release.getProperty(m.createProperty(doap, "revision")).getString()).isEqualTo("1.0");
        assertThat(m.listObjectsOfProperty(m.createProperty(EARL, "info")).toList()).extracting(n -> n.asLiteral().getString())
                .containsExactly("the target does not declare Authentication");

        // Without an IRI the subject is a blank node.
        Model anonymous = EarlReport.model(run, new EarlReport.Subject(null, "a client", null, null, null), "semiAuto");
        assertThat(anonymous.listResourcesWithProperty(RDF.type, anonymous.createResource(EARL + "TestSubject")).next()
                .isAnon()).isTrue();
    }

    @Test
    void aFailureSaysWhatFailed() {
        Model m = EarlReport.model(run(test("core/x#fail", Outcome.FAILED, REQ_A)));
        assertThat(m.listObjectsOfProperty(m.createProperty(EARL, "info")).next().asLiteral().getString())
                .startsWith("core/x#fail - failed [MUST]");
    }

    private static java.util.List<String> outcomes(Model m) {
        Property outcome = m.createProperty(EARL, "outcome");
        return m.listObjectsOfProperty(outcome).toList().stream()
                .map(n -> n.asResource().getURI()).toList();
    }
}
