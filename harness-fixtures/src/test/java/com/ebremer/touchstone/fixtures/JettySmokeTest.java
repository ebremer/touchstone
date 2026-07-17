package com.ebremer.touchstone.fixtures;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the fixture-server pattern from DESIGN.md paragraph 4: a programmatic embedded
 * Jetty {@code Server} on a random port, started and stopped per run.
 */
class JettySmokeTest {

    @Test
    void ephemeralServerOnRandomPortStartsAnswersAndStops() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(204);
                callback.succeeded();
                return true;
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://localhost:" + connector.getLocalPort() + "/ping");
            HttpResponse<Void> response =
                    client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(204);
        } finally {
            server.stop();
        }
    }
}
