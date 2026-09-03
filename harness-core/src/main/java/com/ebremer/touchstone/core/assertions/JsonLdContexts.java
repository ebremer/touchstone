package com.ebremer.touchstone.core.assertions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.document.Document;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.http.media.MediaType;
import com.apicatalog.jsonld.loader.DocumentLoader;
import com.apicatalog.jsonld.loader.DocumentLoaderOptions;
import org.apache.jena.riot.RIOT;
import org.apache.jena.riot.system.jsonld.TitaniumJsonLdOptions;
import org.apache.jena.sparql.util.Context;

/**
 * JSON-LD context resolution for graph assertions: contexts ship with the harness and
 * are served from its own classpath; nothing is fetched over the network.
 *
 * <p>Three reasons, in order of weight. The context IRI is chosen by the <em>SUT's</em>
 * response body, and SUT responses are untrusted input (DESIGN.md paragraph 7.3) — a
 * dereferencing parser lets any target steer harness requests and swap the term mappings
 * a verdict is computed from. A conformance verdict must not depend on external
 * infrastructure being up. And the LWS context IRI is not resolvable at all today
 * (w3c/lws-protocol#216), so dereferencing turned every graph assertion over a real
 * server's {@code application/lws+json} into a spurious failure (DECISIONS.md D-0026).
 * The core draft agrees: "Production systems are advised not to fetch remote JSON-LD
 * context documents at runtime ... prevents context manipulation attacks."
 */
final class JsonLdContexts {

    /**
     * Context IRI to the classpath resource holding it, each copied verbatim from its source.
     *
     * <p>{@code lws/v1} is the copy of the last draft that published the context inline
     * (WD-lws10-core-20260622 12.1.1). The 21 August 2026 draft dropped that block: section 13
     * now gives only the URL and an unfilled digest (w3c/lws-protocol#216), and the URL is still
     * a 404, so there is nothing newer to copy. Terms the current data model added — {@code
     * format}, which replaced {@code mediaType} — are therefore absent, and a container's
     * {@code format} values expand to nothing. That is deliberate: reconstructing terms the
     * specification has not published would make the harness compute verdicts from a mapping
     * nobody normatively defined. {@code format} is asserted through JSON pointers instead, which
     * read the response as it was sent.
     *
     * <p>{@code cid/v1} is the live W3C document (it resolves, unlike the LWS one) and is
     * required from 21 August: a storage description is {@code application/lws+cid} whose
     * {@code @context} is an array starting with the CID context, so without it every storage
     * description parse would fail the offline loader below.
     */
    private static final Map<String, String> BUNDLED = Map.of(
            "https://www.w3.org/ns/lws/v1", "/touchstone/context/lws-v1.jsonld",
            "https://www.w3.org/ns/cid/v1", "/touchstone/context/cid-v1.jsonld");

    private static final Context PARSER_CONTEXT = RIOT.getContext().copy()
            .set(TitaniumJsonLdOptions.JSONLD_OPTIONS,
                    new JsonLdOptions((DocumentLoader) JsonLdContexts::load));

    private JsonLdContexts() {
    }

    /** Parser context carrying the offline document loader; pass to every {@code RDFParser}. */
    static Context parserContext() {
        return PARSER_CONTEXT;
    }

    static Set<String> bundledIris() {
        return BUNDLED.keySet();
    }

    private static Document load(URI url, DocumentLoaderOptions options) throws JsonLdError {
        String resource = BUNDLED.get(url.toString());
        if (resource == null) {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                    "refusing to dereference JSON-LD context <" + url + ">: the harness loads only"
                            + " contexts bundled with it " + BUNDLED.keySet()
                            + " (DESIGN.md paragraph 7.3 — the context IRI comes from the SUT)");
        }
        try (InputStream in = JsonLdContexts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                        "bundled context " + resource + " is missing from the classpath");
            }
            return JsonDocument.of(MediaType.JSON_LD, in);
        } catch (IOException e) {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                    "cannot read bundled context " + resource, e);
        }
    }
}
