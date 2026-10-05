package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.core.Touchstone;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
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
 * CR implementation reports. A result that did not pass says why in earl:info.
 *
 * <p>A client session's report (CLIENT-TESTING.md section 5.2) has the same shape, about the
 * client under test instead of a storage, in mode earl:semiAuto: a person drives the client,
 * and the harness judges it.
 */
public final class EarlReport {

    /**
     * What a report's assertions are about.
     *
     * @param iri its IRI, or null for a blank node
     * @param title its dcterms:title
     * @param name its doap:name, for a software project; or null
     * @param revision the doap:revision of the release tested, or null
     * @param homepage its doap:homepage, an absolute IRI; or null
     */
    public record Subject(String iri, String title, String name, String revision, String homepage) {
    }

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

    /** The report about {@code subject} as Turtle, which a service can send without writing a file. */
    public static String turtle(RunResult run, Subject subject, String mode) {
        java.io.StringWriter out = new java.io.StringWriter();
        RDFDataMgr.write(out, model(run, subject, mode), Lang.TURTLE);
        return out.toString();
    }

    /** Exposed for tests and for the MCP resource endpoint later. */
    public static Model model(RunResult run) {
        return model(run, new Subject(run.targetBaseUrl(), "target '" + run.targetId() + "'", null, null, null),
                "automatic");
    }

    /**
     * The report about {@code subject}, its assertions made in {@code mode}: {@code automatic}
     * when the harness drives the subject, {@code semiAuto} when a person does.
     */
    public static Model model(RunResult run, Subject subject, String mode) {
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
        Property info = m.createProperty(EARL, "info");
        Property name = m.createProperty(DOAP, "name");
        Property release = m.createProperty(DOAP, "revision");

        Resource assertor = m.createResource(Touchstone.HARNESS_IRI)
                .addProperty(RDF.type, m.createResource(EARL + "Software"))
                .addProperty(name, Touchstone.NAME)
                .addProperty(release, Touchstone.version());
        Resource subjectNode = (subject.iri() == null ? m.createResource() : m.createResource(subject.iri()))
                .addProperty(RDF.type, m.createResource(EARL + "TestSubject"))
                .addProperty(title, subject.title());
        if (subject.name() != null) {
            subjectNode.addProperty(RDF.type, m.createResource(DOAP + "Project"))
                    .addProperty(name, subject.name());
        }
        if (subject.revision() != null) {
            subjectNode.addProperty(m.createProperty(DOAP, "release"), m.createResource()
                    .addProperty(RDF.type, m.createResource(DOAP + "Version"))
                    .addProperty(release, subject.revision()));
        }
        if (subject.homepage() != null) {
            subjectNode.addProperty(m.createProperty(DOAP, "homepage"), m.createResource(subject.homepage()));
        }

        for (TestResult test : run.results()) {
            // The test's IRI as its JSON-LD expansion gives it: the same for the YAML-LD source
            // and the JSON-LD export (EXECUTION.md section 9).
            Resource testCase = m.createResource(Definitions.BASE + test.testId())
                    .addProperty(RDF.type, m.createResource(EARL + "TestCase"));
            if (test.label() != null) {
                testCase.addProperty(title, test.label());
            }
            for (String requirement : test.requirements()) {
                testCase.addProperty(verifies, m.createResource(requirement));
            }
            Resource result = m.createResource()
                    .addProperty(RDF.type, m.createResource(EARL + "TestResult"))
                    .addProperty(outcomeP, m.createResource(EARL + test.outcome().earl()))
                    .addProperty(date, m.createTypedLiteral(run.startedAt(), XSDDatatype.XSDdateTime));
            if (test.outcome() == Outcome.FAILED || test.outcome() == Outcome.CANT_TELL) {
                result.addProperty(info, Results.describe(test));
            } else if (test.reason() != null) {
                result.addProperty(info, test.reason());
            }
            m.createResource()
                    .addProperty(RDF.type, m.createResource(EARL + "Assertion"))
                    .addProperty(assertedBy, assertor)
                    .addProperty(subjectP, subjectNode)
                    .addProperty(testP, testCase)
                    .addProperty(modeP, m.createResource(EARL + mode))
                    .addProperty(resultP, result);
        }
        return m;
    }
}
