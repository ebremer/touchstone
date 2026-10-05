package com.ebremer.touchstone.clients;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the traffic log never keeps (CLIENT-TESTING.md section 8.4): credentials. Each is replaced
 * by a fingerprint, the first twelve hex digits of its SHA-256, so two requests carrying the same
 * token can still be told apart from two carrying different ones without either being shown.
 */
final class Redaction {

    /** Request and response headers whose values are credentials. */
    static final Set<String> HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "dpop");
    /** Form fields and JSON members that carry credentials, in token requests and responses. */
    static final Set<String> FIELDS = Set.of("access_token", "refresh_token", "id_token", "subject_token",
            "actor_token", "client_secret", "code", "code_verifier", "password", "assertion");

    private static final ObjectMapper JSON = new ObjectMapper();

    private Redaction() {
    }

    static String fingerprint(String secret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String redacted(String secret) {
        return "[redacted " + fingerprint(secret) + "]";
    }

    /** A credential header's value, its scheme kept: {@code Bearer [redacted 1a2b3c4d5e6f]}. */
    static String header(String name, String value) {
        if (!HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            return value;
        }
        int space = value.indexOf(' ');
        if ((name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("proxy-authorization")) && space > 0) {
            return value.substring(0, space) + " " + redacted(value.substring(space + 1).trim());
        }
        return redacted(value);
    }

    /** A query string or form body with the values of credential fields replaced. */
    static String form(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return encoded;
        }
        StringBuilder out = new StringBuilder();
        for (String pair : encoded.split("&", -1)) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            if (eq >= 0 && FIELDS.contains(decoded)) {
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.append(name).append('=').append(URLEncoder.encode(redacted(value), StandardCharsets.UTF_8));
            } else {
                out.append(pair);
            }
        }
        return out.toString();
    }

    /** A JSON document with the values of credential members replaced, or the text unchanged when it is no JSON. */
    static String json(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) {
                return text;
            }
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            boolean changed = false;
            for (String field : FIELDS) {
                JsonNode v = copy.get(field);
                if (v != null && v.isTextual()) {
                    copy.put(field, redacted(v.asText()));
                    changed = true;
                }
            }
            return changed ? JSON.writeValueAsString(copy) : text;
        } catch (Exception e) {
            return text;
        }
    }
}
