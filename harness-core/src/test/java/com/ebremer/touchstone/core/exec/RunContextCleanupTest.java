package com.ebremer.touchstone.core.exec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run-root cleanup (D-0027). A target may require conditional writes and answer an
 * unconditional DELETE with 428, which used to leave a {@code touchstone-run-*}
 * container behind after every run — silently, because cleanup swallows failures.
 */
class RunContextCleanupTest {

    private static final String ETAG = "\"c7.1\"";

    /** Records what the DELETE carried; answers 428 unless the delete is conditional. */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        final AtomicReference<String> ifMatch = new AtomicReference<>();
        final AtomicReference<String> depth = new AtomicReference<>();
        final AtomicReference<Integer> deleteStatus = new AtomicReference<>();

        Stub(String etag) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/run/", exchange -> {
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    String match = exchange.getRequestHeaders().getFirst("If-Match");
                    ifMatch.set(match);
                    depth.set(exchange.getRequestHeaders().getFirst("Depth"));
                    int status = etag == null || etag.equals(match) ? 204 : 428;
                    deleteStatus.set(status);
                    respond(exchange, status, null);
                } else {
                    respond(exchange, 200, etag);
                }
            });
            server.start();
        }

        private static void respond(HttpExchange exchange, int status, String etag) throws IOException {
            if (etag != null) {
                exchange.getResponseHeaders().add("ETag", etag);
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        URI runRoot() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/run/");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static RunContext context(URI runRoot) {
        Target target = new Target("stub", runRoot, "env", Map.of(), Set.of());
        return new RunContext(target, "test-run", runRoot, identity -> Map.of(),
                HttpClient.newHttpClient(), Map.of());
    }

    @Test
    void cleanupDeleteIsConditionalOnTheRunRootEtag() throws Exception {
        try (Stub stub = new Stub(ETAG)) {
            context(stub.runRoot()).close();

            assertThat(stub.ifMatch.get()).as("If-Match from a fresh HEAD of the run root").isEqualTo(ETAG);
            assertThat(stub.depth.get()).as("recursive delete is still requested").isEqualTo("infinity");
            assertThat(stub.deleteStatus.get()).as("the run root is actually removed").isEqualTo(204);
        }
    }

    @Test
    void cleanupStaysUnconditionalWhenTheTargetIssuesNoEtag() throws Exception {
        try (Stub stub = new Stub(null)) {
            context(stub.runRoot()).close();

            assertThat(stub.ifMatch.get()).as("no ETag to be conditional on").isNull();
            assertThat(stub.deleteStatus.get()).isEqualTo(204);
        }
    }

    @Test
    void cleanupFailureNeverThrows() {
        // nothing is listening on this port: close() must stay advisory
        RunContext ctx = context(URI.create("http://127.0.0.1:1/run/"));

        assertThat(ctx).satisfies(c -> c.close());
    }
}
