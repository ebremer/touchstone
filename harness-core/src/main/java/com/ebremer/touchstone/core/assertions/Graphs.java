package com.ebremer.touchstone.core.assertions;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.ebremer.touchstone.core.exec.TemplateEngine;
import com.ebremer.touchstone.core.manifest.Manifest;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;

/** RDF parsing and triple-pattern matching for graph assertions. */
final class Graphs {

    /** Wildcard token: matches any blank node in that position. */
    static final String ANY_BLANK = "_:any";

    private Graphs() {
    }

    static Model parse(ResponseData data, String parseAs) {
        String mediaType = parseAs != null ? parseAs : data.contentType();
        if (mediaType == null) {
            throw new IllegalStateException("response has no Content-Type and the assertion sets no parseAs");
        }
        Model model = ModelFactory.createDefaultModel();
        RDFParser.create()
                .source(new ByteArrayInputStream(data.body()))
                .lang(langFor(mediaType))
                .base(data.uri().toString())
                .parse(model);
        return model;
    }

    static Model parseFile(Path file, URI base) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.create()
                .source(file.toUri().toString())
                .base(base.toString())
                .parse(model);
        return model;
    }

    static org.apache.jena.graph.Graph loadGraph(Path file) {
        return RDFDataMgr.loadGraph(file.toUri().toString());
    }

    static Lang langFor(String mediaType) {
        String mt = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (mt) {
            case "text/turtle" -> Lang.TURTLE;
            case "application/ld+json", "application/lws+json", "application/json" -> Lang.JSONLD;
            case "application/n-triples" -> Lang.NTRIPLES;
            case "application/rdf+xml" -> Lang.RDFXML;
            default -> throw new IllegalStateException("no RDF reader for media type " + mediaType);
        };
    }

    /** True when the model holds at least one statement matching the (template-expanded) pattern. */
    static boolean matches(Model model, Manifest.TriplePattern pattern, Map<String, String> vars) {
        String s = TemplateEngine.resolve(pattern.s(), vars);
        String p = TemplateEngine.resolve(pattern.p(), vars);
        Resource subject = ANY_BLANK.equals(s) ? null : model.createResource(s);
        Property predicate = model.createProperty(p);

        RDFNode object = null;
        boolean objectAnyBlank = false;
        Manifest.ObjectTerm o = pattern.o();
        if (o.isLiteral()) {
            object = literal(model, o, vars);
        } else if (ANY_BLANK.equals(TemplateEngine.resolve(o.iri(), vars))) {
            objectAnyBlank = true;
        } else {
            object = model.createResource(TemplateEngine.resolve(o.iri(), vars));
        }

        StmtIterator it = model.listStatements(subject, predicate, object);
        while (it.hasNext()) {
            Statement st = it.next();
            if (subject == null && !st.getSubject().isAnon()) {
                continue;
            }
            if (objectAnyBlank && !st.getObject().isAnon()) {
                continue;
            }
            return true;
        }
        return false;
    }

    static String render(Manifest.TriplePattern pattern, Map<String, String> vars) {
        Manifest.ObjectTerm o = pattern.o();
        String obj = o.isLiteral()
                ? "\"" + TemplateEngine.resolve(o.value(), vars) + "\""
                + (o.lang() != null ? "@" + o.lang() : "")
                + (o.datatype() != null ? "^^" + o.datatype() : "")
                : TemplateEngine.resolve(o.iri(), vars);
        return "<" + TemplateEngine.resolve(pattern.s(), vars) + "> <"
                + TemplateEngine.resolve(pattern.p(), vars) + "> " + obj;
    }

    private static Literal literal(Model model, Manifest.ObjectTerm o, Map<String, String> vars) {
        String value = TemplateEngine.resolve(o.value(), vars);
        if (o.lang() != null) {
            return model.createLiteral(value, o.lang());
        }
        if (o.datatype() != null) {
            return model.createTypedLiteral(value, o.datatype());
        }
        return model.createLiteral(value);
    }
}
