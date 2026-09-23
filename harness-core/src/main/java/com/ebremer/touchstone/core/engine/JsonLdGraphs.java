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
import org.apache.jena.sparql.util.Context;

/**
 * JSON-LD graph comparison for {@code connegEquivalent} (EXECUTION.md section 7.10): when two
 * representations are not byte-identical, the report says whether their graphs still are,
 * since "re-serialized" and "different content" are different defects (D-0044).
 *
 * <p>Contexts ship with the harness and nothing is fetched (D-0026): the context IRI comes
 * from the server's response, which is untrusted input (DESIGN.md section 7.3).
 * {@code lws/v1} is the copy of the last draft that published the context inline
 * (WD-lws10-core-20260622); {@code cid/v1} is the live W3C document.
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
