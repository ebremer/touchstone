package com.ebremer.touchstone.core.results;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-side redaction (DESIGN.md paragraph 7.2): credentials are stripped before a
 * trace object even exists, so no consumer — CLI, report, MCP tool — can leak them.
 */
public final class Redaction {

    public static final String REDACTED = "[REDACTED]";
    private static final int MAX_BODY_CHARS = 2048;
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "dpop", "x-api-key");

    private Redaction() {
    }

    public static Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((name, values) ->
                out.put(name, SENSITIVE.contains(name.toLowerCase(Locale.ROOT)) ? List.of(REDACTED) : values));
        return Map.copyOf(out);
    }

    public static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_BODY_CHARS) + "... [truncated " + (body.length() - MAX_BODY_CHARS) + " chars]";
    }
}
