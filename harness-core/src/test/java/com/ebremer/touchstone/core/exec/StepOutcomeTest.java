package com.ebremer.touchstone.core.exec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a step that goes wrong makes of its test (D-0049).
 *
 * <p>A step can fail an assertion and then fail to bind a value, because the second failure
 * follows from the first: a create refused with 401 has no {@code Location} to capture. The
 * outcome was decided by the bind, so the test was an {@code ERROR}, meaning "the harness
 * could not tell". EARL recorded that as {@code cantTell}, for a server that had answered and
 * answered wrongly. The failed assertion is the finding, so it decides. A bind error alone,
 * after every assertion held, is still an {@code ERROR}: the server met the step's
 * expectations, and the harness cannot go on.
 */
class StepOutcomeTest {

    /** POST allocates a container; any other request gets 200 with no Location. */
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Location", exchange.getRequestURI().getPath() + "sub/");
                exchange.sendResponseHeaders(201, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RunContext context() {
        URI runRoot = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/run/");
        Target target = new Target("stub", runRoot, "env", Map.of(), Set.of());
        return new RunContext(target, "test-run", runRoot, identity -> Map.of(), HttpClient.newHttpClient(), Map.of());
    }

    /** One GET that expects {@code status} and binds the Location header the stub never sends. */
    private static Manifest getThenBindLocation(int status) {
        Manifest.Step step = new Manifest.Step(
                "read and capture", null,
                new Manifest.RequestSpec("GET", "${test.container}", Map.of(), null, null),
                null,
                new Manifest.Expectations(Set.of(status), null, null, null, null, null),
                Map.of("created", "header:Location"), null);
        return new Manifest("core/outcome-probe", "outcome probe", null,
                List.of(), List.of(), List.of(), null, List.of(step), Path.of("probe.yaml"));
    }

    @Test
    void aFailedAssertionDecidesTheOutcomeEvenWhenTheBindAfterItFails() {
        TestResult result = Executor.execute(getThenBindLocation(201), context());

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        // Both stay on the record: the finding, and the value it left the test without.
        StepResult step = result.steps().getFirst();
        assertThat(step.failed()).isTrue();
        assertThat(step.error()).contains("bind 'created'");
        assertThat(Results.describe(result))
                .as("the deciding assertion is reported before the error it caused")
                .containsSubsequence("FAILED status", "error: bind 'created'");
    }

    @Test
    void aBindErrorAfterEveryAssertionHeldIsStillAnError() {
        TestResult result = Executor.execute(getThenBindLocation(200), context());

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.steps().getFirst().failed()).isFalse();
    }
}
