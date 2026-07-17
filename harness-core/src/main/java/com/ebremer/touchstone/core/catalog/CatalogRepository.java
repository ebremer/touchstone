package com.ebremer.touchstone.core.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.ebremer.touchstone.core.Touchstone;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

/** Loads requirements-catalog Turtle files into {@link Requirement} records. */
public final class CatalogRepository {

    private CatalogRepository() {
    }

    /**
     * Reads every {@code .ttl} file under {@code dir} (recursively, in stable path
     * order) and returns all requirements sorted by IRI.
     */
    public static List<Requirement> load(Path dir) {
        Model model = ModelFactory.createDefaultModel();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".ttl"))
                    .sorted()
                    .forEach(p -> RDFDataMgr.read(model, p.toUri().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read catalog directory " + dir, e);
        }
        Property level = model.createProperty(Touchstone.VOCAB_NS, "level");
        Property specModule = model.createProperty(Touchstone.VOCAB_NS, "specModule");
        Property section = model.createProperty(Touchstone.VOCAB_NS, "section");
        Property summary = model.createProperty(Touchstone.VOCAB_NS, "summary");
        Property clauseText = model.createProperty(Touchstone.VOCAB_NS, "clauseText");
        Property status = model.createProperty(Touchstone.VOCAB_NS, "status");
        Resource requirementClass = model.createResource(Touchstone.VOCAB_NS + "Requirement");

        List<Requirement> out = new ArrayList<>();
        model.listResourcesWithProperty(RDF.type, requirementClass).forEachRemaining(r ->
                out.add(new Requirement(
                        r.getURI(),
                        literal(r, level),
                        literal(r, specModule),
                        resourceUri(r, section),
                        literal(r, summary),
                        literal(r, clauseText),
                        localName(r, status))));
        out.sort(Comparator.comparing(Requirement::iri));
        return List.copyOf(out);
    }

    private static String literal(Resource r, Property p) {
        Statement s = r.getProperty(p);
        return s == null ? null : s.getString();
    }

    private static String resourceUri(Resource r, Property p) {
        Statement s = r.getProperty(p);
        return s == null || !s.getObject().isURIResource() ? null : s.getResource().getURI();
    }

    private static String localName(Resource r, Property p) {
        Statement s = r.getProperty(p);
        return s == null || !s.getObject().isURIResource() ? null : s.getResource().getLocalName();
    }
}
