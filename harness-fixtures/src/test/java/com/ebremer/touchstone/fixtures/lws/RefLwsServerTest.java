package com.ebremer.touchstone.fixtures.lws;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Direct sanity checks of the reference server's trickier status paths. */
class RefLwsServerTest {

    private static RefLwsServer server;
    private static HttpClient http;

    @BeforeAll
    static void start() {
        server = RefLwsServer.start(0);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void fullLifecycleWithConcurrencyAndContainmentRules() throws Exception {
        URI base = server.baseUri();

        // create a container
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(base)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Slug", "notes")
                .header("Link", "<https://www.w3.org/ns/lws#Container>; rel=\"type\"")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        URI container = base.resolve(created.headers().firstValue("Location").orElseThrow());
        assertThat(container.toString()).endsWith("/");

        // create a data resource inside it
        HttpResponse<String> note = http.send(HttpRequest.newBuilder(container)
                .POST(HttpRequest.BodyPublishers.ofString("v1"))
                .header("Content-Type", "text/plain")
                .header("Slug", "note")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(note.statusCode()).isEqualTo(201);
        URI resource = base.resolve(note.headers().firstValue("Location").orElseThrow());

        // read it, keep the etag
        HttpResponse<String> read = http.send(HttpRequest.newBuilder(resource).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo("v1");
        String etag = read.headers().firstValue("ETag").orElseThrow();

        // unconditional PUT -> 428; wrong If-Match -> 412; right one -> 204
        assertThat(put(resource, "v2", null).statusCode()).isEqualTo(428);
        assertThat(put(resource, "v2", "\"wrong\"").statusCode()).isEqualTo(412);
        assertThat(put(resource, "v2", etag).statusCode()).isEqualTo(204);

        // conditional GET with the container's etag -> 304
        HttpResponse<String> listing = http.send(HttpRequest.newBuilder(container).build(),
                HttpResponse.BodyHandlers.ofString());
        String containerEtag = listing.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> conditional = http.send(HttpRequest.newBuilder(container)
                .header("If-None-Match", containerEtag).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(conditional.statusCode()).isEqualTo(304);

        // non-recursive delete of a non-empty container -> 409; Depth infinity -> 204
        assertThat(http.send(HttpRequest.newBuilder(container).DELETE().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(409);
        assertThat(http.send(HttpRequest.newBuilder(container).DELETE().header("Depth", "infinity").build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
        assertThat(http.send(HttpRequest.newBuilder(resource).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }

    private static HttpResponse<Void> put(URI uri, String body, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain");
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding());
    }
}
