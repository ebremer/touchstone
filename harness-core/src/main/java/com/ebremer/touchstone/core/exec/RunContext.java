package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One provisioned conformance run against one target: a unique run-root container
 * on the SUT plus per-test sub-containers, so tests never share state and never
 * depend on each other (DESIGN.md paragraph 5.3). Thread-safe; tests run in parallel.
 */
public final class RunContext implements AutoCloseable {

    private final Target target;
    private final String runId;
    private final URI runRoot;
    private final CredentialResolver credentials;
    private final HttpClient http;
    private final Map<String, String> provisionHeaders;
    private final AtomicInteger testCounter = new AtomicInteger();

    public RunContext(Target target, String runId, URI runRoot, CredentialResolver credentials, HttpClient http) {
        this(target, runId, runRoot, credentials, http, Map.of());
    }

    /** @param provisionHeaders credentials used to allocate run-owned containers (per-test roots). */
    public RunContext(Target target, String runId, URI runRoot, CredentialResolver credentials, HttpClient http,
                      Map<String, String> provisionHeaders) {
        this.target = target;
        this.runId = runId;
        this.runRoot = runRoot;
        this.credentials = credentials;
        this.http = http;
        this.provisionHeaders = Map.copyOf(provisionHeaders);
    }

    public Target target() {
        return target;
    }

    public String runId() {
        return runId;
    }

    public URI runRoot() {
        return runRoot;
    }

    public CredentialResolver credentials() {
        return credentials;
    }

    public HttpClient http() {
        return http;
    }

    /** Allocates a fresh container for one test under the run root, as the provisioning identity. */
    public URI allocateTestContainer(String slug) {
        return Containers.create(http, runRoot, "t" + testCounter.incrementAndGet() + "-" + slug, provisionHeaders);
    }

    /** Best-effort recursive cleanup of the run root; failures are ignored by design. */
    @Override
    public void close() {
        try {
            HttpRequest.Builder delete = HttpRequest.newBuilder(runRoot)
                    .DELETE()
                    .header("Depth", "infinity")
                    .timeout(Duration.ofSeconds(10));
            provisionHeaders.forEach(delete::header);
            http.send(delete.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // cleanup is advisory; the run root is uniquely named per run
        }
    }
}
