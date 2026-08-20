package com.ebremer.touchstone.core.exec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.TestResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which identity a step runs as (D-0028): step {@code as} beats manifest {@code as} beats
 * the target's {@code defaultIdentity} property beats anonymous. The target-level default
 * is what lets the core suite — which declares no identities, because the spec's operations
 * are not about auth — run against a storage that is not world-readable.
 */
class IdentityResolutionTest {

    /** Records the Authorization header seen per request path; POST allocates a container. */
    private HttpServer server;
    private final Map<String, String> authByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authByPath.put(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
                    auth == null ? "<none>" : auth);
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Location",
                        exchange.getRequestURI().getPath() + "sub/");
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

    private URI runRoot() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/run/");
    }

    /** Credentials are just the identity name, so assertions can read them back. */
    private RunContext context(Map<String, String> targetProperties) {
        URI runRoot = runRoot();
        Target target = new Target("stub", runRoot, "env", targetProperties, Set.of());
        return new RunContext(target, "test-run", runRoot,
                identity -> "anonymous".equals(identity) ? Map.of() : Map.of("Authorization", "Bearer " + identity),
                HttpClient.newHttpClient(), Map.of());
    }

    private static Manifest manifest(String manifestIdentity, String stepIdentity) {
        Manifest.Step step = new Manifest.Step(
                "read", stepIdentity,
                new Manifest.RequestSpec("GET", "${test.container}", Map.of(), null, null),
                null,
                new Manifest.Expectations(Set.of(200), null, null, null, null, null),
                Map.of(), null);
        return new Manifest("core/identity-probe", "identity probe", null,
                List.of(), List.of(), List.of(), manifestIdentity, List.of(step), Path.of("probe.yaml"));
    }

    /** The Authorization header the single GET step carried. */
    private String authOnGet() {
        return authByPath.entrySet().stream()
                .filter(e -> e.getKey().startsWith("GET "))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no GET reached the stub: " + authByPath));
    }

    @Test
    void anUndeclaredIdentityFallsBackToTheTargetDefault() {
        TestResult result = Executor.execute(manifest(null, null),
                context(Map.of("defaultIdentity", "touchstone")));

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(authOnGet()).isEqualTo("Bearer touchstone");
    }

    @Test
    void withNoTargetDefaultAnUndeclaredIdentityIsStillAnonymous() {
        Executor.execute(manifest(null, null), context(Map.of()));

        assertThat(authOnGet()).isEqualTo("<none>");
    }

    @Test
    void anExplicitAnonymousManifestStaysAnonymousOnAnAuthenticatedTarget() {
        // the auth suite's 401-challenge test must keep its meaning
        Executor.execute(manifest("anonymous", null), context(Map.of("defaultIdentity", "touchstone")));

        assertThat(authOnGet()).isEqualTo("<none>");
    }

    @Test
    void aManifestIdentityBeatsTheTargetDefault() {
        Executor.execute(manifest("alice", null), context(Map.of("defaultIdentity", "touchstone")));

        assertThat(authOnGet()).isEqualTo("Bearer alice");
    }

    @Test
    void aStepIdentityBeatsBoth() {
        Executor.execute(manifest("alice", "bob"), context(Map.of("defaultIdentity", "touchstone")));

        assertThat(authOnGet()).isEqualTo("Bearer bob");
    }
}
