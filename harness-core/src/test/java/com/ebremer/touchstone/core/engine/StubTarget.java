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
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A scripted stand-in for a target's storage, on the loopback interface, for engine tests that
 * need HTTP. It creates containers the way the engine provisions them (a POST to a URL ending in
 * {@code /} answers 201 with a fresh {@code Location}), answers HEAD with 404 and DELETE with
 * 204, so a run opens and cleans up, and passes every other request to the test's handler.
 */
final class StubTarget implements AutoCloseable {

    /** Answers a request the stub does not handle itself; false leaves it to the stub (404). */
    @FunctionalInterface
    interface Handler {
        boolean handle(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger containers = new AtomicInteger();

    private StubTarget(Handler handler) throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (handler.handle(exchange)) {
                    return;
                }
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if (method.equals("POST") && path.endsWith("/")) {
                    exchange.getResponseHeaders().set("Location", "/c" + containers.incrementAndGet() + "/");
                    exchange.sendResponseHeaders(201, -1);
                } else if (method.equals("DELETE")) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    static StubTarget start(Handler handler) throws IOException {
        return new StubTarget(handler);
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** Sends a response with the given headers and a body (none when null). */
    static void answer(HttpExchange exchange, int status, Map<String, String> headers, String body) throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
    }
}
