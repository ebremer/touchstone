package com.ebremer.touchstone.core.results;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A redacted record of one HTTP exchange. Redaction happens at construction —
 * traces never hold live credentials (DESIGN.md paragraph 7.2), and bodies are
 * truncated (paragraph 7.3: SUT responses are untrusted input downstream).
 */
public record HttpExchangeTrace(
        String method,
        URI uri,
        Map<String, List<String>> requestHeaders,
        String requestBody,
        Integer status,
        Map<String, List<String>> responseHeaders,
        String responseBody) {

    public static HttpExchangeTrace of(String method, URI uri, Map<String, List<String>> requestHeaders,
                                       String requestBody, Integer status,
                                       Map<String, List<String>> responseHeaders, String responseBody) {
        return new HttpExchangeTrace(
                method,
                uri,
                Redaction.redactHeaders(requestHeaders),
                Redaction.truncate(requestBody),
                status,
                responseHeaders == null ? null : Redaction.redactHeaders(responseHeaders),
                Redaction.truncate(responseBody));
    }
}
