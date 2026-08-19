package com.ebremer.touchstone.core.assertions;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.RiotException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bundled JSON-LD contexts (D-0026). A real LWS server sends the context by IRI
 * ("@context": "https://www.w3.org/ns/lws/v1"), which W3C has not published yet
 * (w3c/lws-protocol#216); the reference fixture inlines its context, so every graph
 * assertion over a real server used to fail with "Unexpected response code [404]"
 * and got reported as the server's non-conformance.
 */
class JsonLdContextsTest {

    private static final String LWS = "https://www.w3.org/ns/lws#";

    /** A container listing exactly as vulcan.bmi.stonybrook.edu returned it. */
    private static final String LISTING = """
            {"@context":"https://www.w3.org/ns/lws/v1",
             "id":"https://sut.example/c/",
             "type":"Container",
             "totalItems":1,
             "items":[{"id":"https://sut.example/c/note","type":"DataResource",
                       "mediaType":"text/plain","size":21}]}
            """;

    private static ResponseData body(String json) {
        return new ResponseData(200, HttpHeaders.of(Map.of(), (a, b) -> true),
                json.getBytes(StandardCharsets.UTF_8), URI.create("https://sut.example/c/"),
                "application/lws+json");
    }

    @Test
    void remoteContextIriResolvesFromTheBundledCopyWithNoNetwork() {
        Model model = Graphs.parse(body(LISTING), null);

        // the two mappings the core manifests assert on
        assertThat(model.contains(
                ResourceFactory.createResource("https://sut.example/c/"),
                ResourceFactory.createProperty(LWS + "items"),
                ResourceFactory.createResource("https://sut.example/c/note"))).isTrue();
        assertThat(model.contains(
                ResourceFactory.createResource("https://sut.example/c/note"),
                org.apache.jena.vocabulary.RDF.type,
                ResourceFactory.createResource(LWS + "DataResource"))).isTrue();
    }

    @Test
    void unknownContextIriIsRefusedRatherThanFetched() {
        String hostile = """
                {"@context":"https://attacker.example/ctx","id":"https://sut.example/c/","type":"Container"}
                """;

        assertThatThrownBy(() -> Graphs.parse(body(hostile), null))
                .isInstanceOf(RiotException.class)
                .hasMessageContaining("refusing to dereference")
                .hasMessageContaining("https://attacker.example/ctx");
    }

    @Test
    void theLwsContextIsTheBundledOne() {
        assertThat(JsonLdContexts.bundledIris()).containsExactly("https://www.w3.org/ns/lws/v1");
    }

    @Test
    void inlineContextsStillParse() {
        String inline = """
                {"@context":{"@vocab":"https://www.w3.org/ns/lws#","id":"@id","type":"@type"},
                 "id":"https://sut.example/c/","type":"Container"}
                """;

        Model model = Graphs.parse(body(inline), null);

        assertThat(model.listStatements(null, org.apache.jena.vocabulary.RDF.type,
                ResourceFactory.createResource(LWS + "Container")).toList())
                .as("inline contexts are unaffected by the offline loader")
                .hasSize(1);
    }

    @Test
    void turtleIsUnaffected() {
        ResponseData turtle = new ResponseData(200, HttpHeaders.of(Map.of(), (a, b) -> true),
                "<https://sut.example/c/> a <https://www.w3.org/ns/lws#Container> ."
                        .getBytes(StandardCharsets.UTF_8),
                URI.create("https://sut.example/c/"), "text/turtle");

        assertThat(Graphs.parse(turtle, null).size()).isEqualTo(1L);
    }

    @Test
    void everyBundledContextIsReadableAndWellFormed() {
        List<String> iris = List.copyOf(JsonLdContexts.bundledIris());
        assertThat(iris).isNotEmpty();
        // parsing a document that references each one proves the resource resolves off the classpath
        for (String iri : iris) {
            String doc = "{\"@context\":\"" + iri + "\",\"id\":\"https://sut.example/c/\",\"type\":\"Container\"}";
            assertThat(Graphs.parse(body(doc), null).size()).isPositive();
        }
    }
}
