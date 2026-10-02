package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ebremer.touchstone.core.exec.ProvisioningException;
import com.ebremer.touchstone.core.exec.Target;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The harness fixture host (EXECUTION.md section 5.3): the controlled identifier documents of
 * the CID and OpenID identities, and the discovery document and JWKS of the harness OpenID
 * Provider and of the rogue one. A target that declares ReachableFixtures dereferences these,
 * so the harness owns the parties it must be able to make misbehave (DESIGN.md section 1).
 *
 * <p>It also serves the inboxes of running tests (section 5.4): POST records a delivery, PUT
 * scripts how the inbox answers, GET
 * returns the record. It serves nothing else (section 10), answers only GET and HEAD outside
 * the inboxes, and runs only while a run does. Bound to the loopback interface when {@code fixtures.baseUrl} names a loopback host,
 * otherwise to every interface on its port, unless {@code fixtures.bind} ({@code host:port})
 * says where.
 */
final class FixtureHost implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    private FixtureHost(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** Starts the host for a target that can reach one; null when the target declares none. */
    static FixtureHost startFor(RunSession run) {
        Target target = run.target();
        String base = target.properties().get("fixtures.baseUrl");
        if (!target.capabilities().contains("ReachableFixtures") || base == null || base.isBlank()) {
            return null;
        }
        URI uri = URI.create(base.endsWith("/") ? base : base + "/");
        String basePath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        Scope scope = new Scope(run, null);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            HttpServer server = HttpServer.create(bindAddress(target, uri), 0);
            server.createContext(basePath, exchange -> handle(exchange, basePath, run, scope));
            server.setExecutor(executor);
            server.start();
            return new FixtureHost(server, executor);
        } catch (IOException e) {
            executor.shutdown();
            throw new ProvisioningException("cannot start the fixture host for " + uri + ": " + e.getMessage(), e);
        }
    }

    private static InetSocketAddress bindAddress(Target target, URI base) throws IOException {
        String bind = target.properties().get("fixtures.bind");
        if (bind != null && !bind.isBlank()) {
            int colon = bind.lastIndexOf(':');
            return new InetSocketAddress(bind.substring(0, colon), Integer.parseInt(bind.substring(colon + 1)));
        }
        int port = base.getPort() != -1 ? base.getPort() : "https".equals(base.getScheme()) ? 443 : 80;
        String host = base.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("[::1]") || host.equals("::1"));
        return loopback ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port) : new InetSocketAddress(port);
    }

    private static void handle(HttpExchange exchange, String basePath, RunSession run, Scope scope) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String requested = exchange.getRequestURI().getRawPath();
            if (requested.startsWith(basePath + "inbox/")) {
                inbox(exchange, requested.substring((basePath + "inbox/").length()), run, basePath);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String rel = path.startsWith(basePath) ? path.substring(basePath.length()) : null;
            JsonNode doc = null;
            if (rel != null) {
                try {
                    Map<String, JsonNode> docs = run.credentials().fixtureDocuments(scope);
                    doc = docs.get(rel);
                } catch (RuntimeException e) {
                    doc = null;
                }
            }
            if (doc == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = doc.toString().getBytes(StandardCharsets.UTF_8);
            boolean provider = rel.endsWith("/jwks") || rel.endsWith("/.well-known/openid-configuration");
            exchange.getResponseHeaders().set("Content-Type", provider ? "application/json" : "application/ld+json");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * {@code inbox/<id>/}: POST records a delivery, PUT scripts the statuses it is answered with,
     * GET and HEAD return the record (section 5.4).
     * Only the inbox URL itself is served, and only while its test runs.
     */
    private static void inbox(HttpExchange exchange, String rest, RunSession run, String basePath) throws IOException {
        Inboxes inboxes = run.inboxes();
        String method = exchange.getRequestMethod();
        String id = rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
        if (id.isEmpty() || id.contains("/")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        switch (method) {
            case "POST" -> {
                byte[] body = exchange.getRequestBody().readNBytes(Inboxes.MAX_BODY_BYTES + 1);
                if (body.length > Inboxes.MAX_BODY_BYTES) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                if (!inboxes.isOpen(id)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                // The signature is checked against the inbox URL the server was given: the public
                // fixtures.baseUrl, not the request as it reached this host through a proxy.
                URI inbox = URI.create(run.fixturesBaseUrl() + "inbox/" + id + "/");
                var inspection = HttpSignatures.inspect(inbox, method, exchange.getRequestHeaders(), body,
                        storage -> storageDescription(run, storage));
                exchange.sendResponseHeaders(inboxes.record(id, method, exchange.getRequestHeaders(), body, inspection), -1);
            }
            case "PUT" -> {
                // The test scripts how its inbox answers the deliveries that follow (section 5.4).
                byte[] body = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
                JsonNode document = null;
                if (body.length <= 64 * 1024) {
                    try {
                        document = Templates.JSON.readTree(body);
                    } catch (IOException e) {
                        document = null;
                    }
                }
                exchange.sendResponseHeaders(inboxes.script(id, document), -1);
            }
            case "GET", "HEAD" -> {
                JsonNode view = inboxes.view(id);
                if (view == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] doc = view.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                if (method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(doc.length));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, doc.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(doc);
                }
            }
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, PUT");
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    /**
     * The storage description a delivery's keyid names, fetched anonymously, but only from the
     * target's own host: the keyid is whatever the sender wrote, and the fixture host must not be
     * made to fetch arbitrary URLs (section 10).
     */
    private static JsonNode storageDescription(RunSession run, URI storage) {
        URI base = run.target().baseUrl();
        if (!"https".equalsIgnoreCase(storage.getScheme()) && !"http".equalsIgnoreCase(storage.getScheme())
                || storage.getHost() == null || !storage.getHost().equalsIgnoreCase(base.getHost())) {
            return null;
        }
        try {
            Http.Resp resp = run.send(new Http.Req("GET", storage,
                    java.util.List.of(Http.header("Accept", "application/lws+cid, application/ld+json;q=0.9, application/json;q=0.8")),
                    null, null));
            return resp.status() == 200 ? Templates.JSON.readTree(resp.body()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
    }
}
