package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.core.Touchstone;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

/**
 * EARL writer (DESIGN.md paragraph 5.5): one earl:Assertion per executed test,
 * subject = the target storage, test = the minted test-case IRI, linked to the
 * catalog requirement IRIs it verifies — the W3C-standard shape the WG needs for
 * CR implementation reports.
 */
public final class EarlReport {

    private static final String EARL = "http://www.w3.org/ns/earl#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String DOAP = "http://usefulinc.com/ns/doap#";

    private EarlReport() {
    }

    public static void write(RunResult run, Path file) {
        Model m = model(run);
        try (OutputStream out = Files.newOutputStream(file)) {
            RDFDataMgr.write(out, m, Lang.TURTLE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write EARL report " + file, e);
        }
    }

    /** Exposed for tests and for the MCP resource endpoint later. */
    public static Model model(RunResult run) {
        Model m = ModelFactory.createDefaultModel();
        m.setNsPrefix("earl", EARL);
        m.setNsPrefix("dcterms", DCTERMS);
        m.setNsPrefix("doap", DOAP);
        m.setNsPrefix("touchstone", Touchstone.VOCAB_NS);

        Property assertedBy = m.createProperty(EARL, "assertedBy");
        Property subjectP = m.createProperty(EARL, "subject");
        Property testP = m.createProperty(EARL, "test");
        Property resultP = m.createProperty(EARL, "result");
        Property outcomeP = m.createProperty(EARL, "outcome");
        Property modeP = m.createProperty(EARL, "mode");
        Property verifies = m.createProperty(Touchstone.VOCAB_NS, "verifies");
        Property date = m.createProperty(DCTERMS, "date");
        Property title = m.createProperty(DCTERMS, "title");
        Property name = m.createProperty(DOAP, "name");
        Property release = m.createProperty(DOAP, "revision");

        Resource assertor = m.createResource(Touchstone.HARNESS_IRI)
                .addProperty(RDF.type, m.createResource(EARL + "Software"))
                .addProperty(name, Touchstone.NAME)
                .addProperty(release, Touchstone.version());
        Resource subject = m.createResource(run.targetBaseUrl())
                .addProperty(RDF.type, m.createResource(EARL + "TestSubject"))
                .addProperty(title, "target '" + run.targetId() + "'");

        for (TestResult test : run.results()) {
            Resource testCase = m.createResource(Touchstone.TEST_NS + test.manifestId())
                    .addProperty(RDF.type, m.createResource(EARL + "TestCase"));
            for (String requirement : test.requirements()) {
                testCase.addProperty(verifies, m.createResource(requirement));
            }
            Resource result = m.createResource()
                    .addProperty(RDF.type, m.createResource(EARL + "TestResult"))
                    .addProperty(outcomeP, m.createResource(EARL + outcome(test.outcome())))
                    .addProperty(date, m.createTypedLiteral(run.startedAt(), XSDDatatype.XSDdateTime));
            m.createResource()
                    .addProperty(RDF.type, m.createResource(EARL + "Assertion"))
                    .addProperty(assertedBy, assertor)
                    .addProperty(subjectP, subject)
                    .addProperty(testP, testCase)
                    .addProperty(modeP, m.createResource(EARL + "automatic"))
                    .addProperty(resultP, result);
        }
        return m;
    }

    static String outcome(Outcome outcome) {
        return switch (outcome) {
            case PASSED -> "passed";
            case FAILED -> "failed";
            // The harness could not tell — transport error, unresolved variable, ...
            case ERROR -> "cantTell";
            // Skips are capability-gated: the feature does not apply to this target.
            case SKIPPED -> "inapplicable";
        };
    }
}
