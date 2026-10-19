package com.ebremer.touchstone.core.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.document.Document;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.http.media.MediaType;
import com.apicatalog.jsonld.loader.DocumentLoader;
import com.apicatalog.jsonld.loader.DocumentLoaderOptions;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RIOT;
import org.apache.jena.riot.system.jsonld.TitaniumJsonLdOptions;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.IsoMatcher;

/**
 * JSON-LD graph comparison for {@code connegEquivalent} (EXECUTION.md section 7.10): when two
 * representations are not byte-identical, the report says whether their graphs still are,
 * since "re-serialized" and "different content" are different defects (D-0044).
 *
 * <p>It also reads a representation in any RDF media type the LWS profile names, for
 * {@code rdfIsomorphicWith} (section 7.11, since 0.14.0).
 *
 * <p>Contexts ship with the harness and nothing is fetched (D-0026): the context IRI comes
 * from the server's response, which is untrusted input (DESIGN.md section 7.3).
 * {@code lws/v1} is the context the Working Group's CI builds from the LWS vocabulary
 * (w3c/lws-protocol 9a08ff6, 19 October 2026), since W3C still serves nothing at its IRI
 * (D-0100); {@code cid/v1} is the live W3C document.
 */
final class JsonLdGraphs {

    private static final Map<String, String> BUNDLED = Map.of(
            "https://www.w3.org/ns/lws/v1", "/touchstone/context/lws-v1.jsonld",
            "https://www.w3.org/ns/cid/v1", "/touchstone/context/cid-v1.jsonld");

    private static final Context PARSER_CONTEXT = RIOT.getContext().copy()
            .set(TitaniumJsonLdOptions.JSONLD_OPTIONS, new JsonLdOptions((DocumentLoader) JsonLdGraphs::load));

    private JsonLdGraphs() {
    }

    /**
     * "isomorphic" when every body parses as JSON-LD and all graphs are isomorphic, "not
     * isomorphic" when they parse but differ, or why they could not be compared.
     */
    static String compare(List<byte[]> bodies, URI base) {
        Model first = null;
        for (byte[] body : bodies) {
            Model m;
            try {
                m = parse(body, base);
            } catch (RuntimeException e) {
                return "not comparable as JSON-LD: " + e.getMessage();
            }
            if (first == null) {
                first = m;
            } else if (!first.isIsomorphicWith(m)) {
                return "not isomorphic: the representations carry different content";
            }
        }
        return "isomorphic: the same graph, serialized differently";
    }

    /**
     * The RDF dataset a representation encodes, read by its media type's essence: JSON-LD for
     * {@code application/lws+json}, {@code application/ld+json} and {@code application/json}
     * (with the bundled contexts), and Turtle, TriG, N-Triples or N-Quads. Throws
     * IllegalArgumentException for any other media type, and a RuntimeException for a body that
     * does not parse.
     */
    static DatasetGraph dataset(byte[] body, String contentType, URI base) {
        String essence = Headers.essence(contentType);
        Lang lang = essence == null ? null : switch (essence) {
            case "application/lws+json", "application/ld+json", "application/json" -> Lang.JSONLD;
            case "text/turtle" -> Lang.TURTLE;
            case "application/trig" -> Lang.TRIG;
            case "application/n-triples" -> Lang.NTRIPLES;
            case "application/n-quads" -> Lang.NQUADS;
            default -> null;
        };
        if (lang == null) {
            throw new IllegalArgumentException(essence + " is not an RDF media type the harness reads");
        }
        DatasetGraph dataset = DatasetGraphFactory.create();
        RDFParser.create()
                .source(new ByteArrayInputStream(body))
                .lang(lang)
                .base(base.toString())
                .context(PARSER_CONTEXT)
                .parse(dataset);
        return dataset;
    }

    /** "isomorphic", or how two datasets differ: their sizes, and a quad one has that the other lacks. */
    static String compare(DatasetGraph a, DatasetGraph b) {
        if (IsoMatcher.isomorphic(a, b)) {
            return "isomorphic";
        }
        List<Quad> onlyA = only(a, b);
        List<Quad> onlyB = only(b, a);
        StringBuilder sb = new StringBuilder("not isomorphic: ").append(a.stream().count()).append(" quads against ")
                .append(b.stream().count());
        if (!onlyA.isEmpty()) {
            sb.append("; only in this response: ").append(onlyA.getFirst());
        }
        if (!onlyB.isEmpty()) {
            sb.append("; only in the other: ").append(onlyB.getFirst());
        }
        return sb.toString();
    }

    /** The quads of {@code a} without blank nodes that {@code b} lacks; blank nodes cannot be matched one by one. */
    private static List<Quad> only(DatasetGraph a, DatasetGraph b) {
        return a.stream().filter(q -> !q.getSubject().isBlank() && !q.getObject().isBlank() && !b.contains(q))
                .limit(1).toList();
    }

    static Model parse(byte[] body, URI base) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.create()
                .source(new ByteArrayInputStream(body))
                .lang(Lang.JSONLD)
                .base(base.toString())
                .context(PARSER_CONTEXT)
                .parse(model);
        return model;
    }

    private static Document load(URI url, DocumentLoaderOptions options) throws JsonLdError {
        String resource = BUNDLED.get(url.toString());
        if (resource == null) {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                    "refusing to dereference JSON-LD context <" + url + ">: the harness loads only the contexts"
                            + " bundled with it " + BUNDLED.keySet());
        }
        try (InputStream in = JsonLdGraphs.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                        "bundled context " + resource + " is missing from the classpath");
            }
            return JsonDocument.of(MediaType.JSON_LD, in);
        } catch (IOException e) {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, "cannot read " + resource, e);
        }
    }
}
