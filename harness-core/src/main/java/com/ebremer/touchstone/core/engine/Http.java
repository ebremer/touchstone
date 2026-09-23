package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.Touchstone;
import com.ebremer.touchstone.core.results.HttpExchangeTrace;

/**
 * The engine's HTTP: exactly the headers a definition asks for plus the few EXECUTION.md
 * section 6.2 names, HTTP/1.1 (so no upgrade header is added behind a definition's back), a
 * timeout on every request, redirects never followed, nothing retried (section 4.2).
 */
final class Http {

    /** A request as sent. {@code bodyText} is what the trace shows (redacted on the way in). */
    record Req(String method, URI uri, List<Map.Entry<String, String>> headers, byte[] body, String bodyText) {

        Req withHeader(String name, String value) {
            List<Map.Entry<String, String>> h = new ArrayList<>();
            for (Map.Entry<String, String> e : headers) {
                if (!e.getKey().equalsIgnoreCase(name)) {
                    h.add(e);
                }
            }
            h.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
            return new Req(method, uri, List.copyOf(h), body, bodyText);
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    /** A response as received. */
    record Resp(int status, HttpHeaders headers, byte[] body) {

        List<String> header(String name) {
            return headers.allValues(name);
        }

        String first(String name) {
            return headers.firstValue(name).orElse(null);
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final String USER_AGENT = "touchstone/" + Touchstone.version();

    private final HttpClient client;
    private final Duration timeout;

    Http(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    Resp send(Req req) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(req.uri()).timeout(timeout);
        b.method(req.method(), req.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(req.body()));
        boolean agent = false;
        for (Map.Entry<String, String> h : req.headers()) {
            b.header(h.getKey(), h.getValue());
            agent |= h.getKey().equalsIgnoreCase("User-Agent");
        }
        if (!agent) {
            b.header("User-Agent", USER_AGENT);
        }
        HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(r.statusCode(), r.headers(), r.body() == null ? new byte[0] : r.body());
    }

    /** The redacted record of an exchange; {@code resp} is null when nothing came back. */
    static HttpExchangeTrace trace(Req req, Resp resp) {
        Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : req.headers()) {
            requestHeaders.computeIfAbsent(h.getKey(), k -> new ArrayList<>()).add(h.getValue());
        }
        return HttpExchangeTrace.of(req.method(), req.uri(), requestHeaders, req.bodyText(),
                resp == null ? null : resp.status(),
                resp == null ? null : resp.headers().map(),
                resp == null ? null : resp.text());
    }

    static Map.Entry<String, String> header(String name, String value) {
        return new AbstractMap.SimpleImmutableEntry<>(name, value);
    }
}
