package com.ebremer.touchstone.clients;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An exchange as client rules see it (OBSERVATION.md section 3): the request redacted as in the
 * traffic log but with its whole body, the session's status, and the annotations. It exists
 * only while the exchange is judged.
 *
 * @param url the request URL, credentials in its query redacted
 * @param body the whole request body; at the token endpoint, with credentials redacted
 */
record Observed(long seq, String method, String url, Map<String, List<String>> requestHeaders, byte[] body,
                int status, Exchange.Annotations annotations) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A request header's field lines, by name, case-insensitively. */
    List<String> header(String name) {
        List<String> out = new ArrayList<>();
        requestHeaders.forEach((k, v) -> {
            if (k.equalsIgnoreCase(name)) {
                out.addAll(v);
            }
        });
        return out;
    }

    String firstHeader(String name) {
        List<String> lines = header(name);
        return lines.isEmpty() ? null : lines.getFirst();
    }

    String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The body parsed as JSON, or null when it is not JSON. */
    JsonNode json() {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * An application/x-www-form-urlencoded body as a JSON object, each parameter name with its
     * first value (OBSERVATION.md section 5, {@code form}); null for any other body.
     */
    JsonNode form() {
        String type = firstHeader("Content-Type");
        if (type == null || !"application/x-www-form-urlencoded".equals(
                type.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
        TokenRequests.form(bodyText()).forEach(out::put);
        return out;
    }

    URI uri() {
        try {
            return new URI(url);
        } catch (Exception e) {
            return URI.create("about:invalid");
        }
    }

    /** The request URL without its fragment, as {@code sameTarget} compares it. */
    String target() {
        return Recorder.withoutFragment(url);
    }
}
