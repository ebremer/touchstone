package com.ebremer.touchstone.core.results;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side redaction (DESIGN.md paragraph 7.2): credentials are stripped before a
 * trace object even exists, so no consumer — CLI, report, MCP tool — can leak them.
 *
 * <p>Headers were the whole of it for a long time, which was only ever true because the
 * suite had not yet exercised a flow that carries a credential anywhere else. The core
 * draft's token exchange (5.2.3) puts a {@code subject_token} in a request body and an
 * {@code access_token} in the response, and OAuth has always allowed a token in a query
 * string. A credential that reaches {@code run.json}, {@code report.html} and every MCP
 * {@code get_trace} is not less exposed for having arrived in a body, so bodies and URLs
 * are scrubbed too.
 *
 * <p>The scrub is deliberately name-based and generous: it matches the well-known
 * credential parameter names wherever they appear as a JSON member, a form field or a
 * query parameter. It cannot catch a credential under a name nobody standardised, so it
 * is a floor, not a guarantee — which is the other reason bodies stay truncated.
 */
public final class Redaction {

    public static final String REDACTED = "[REDACTED]";
    private static final int MAX_BODY_CHARS = 2048;

    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "dpop",
            "dpop-nonce", "x-api-key", "api-key", "authentication-info", "proxy-authenticate");

    /**
     * Parameter names that carry a credential in OAuth 2.0, OIDC and RFC 8693 token
     * exchange — the flows the authorization and authentication modules test.
     */
    private static final String CREDENTIAL_NAMES =
            "access_token|refresh_token|id_token|subject_token|actor_token|requested_token"
            + "|client_secret|client_assertion|assertion|code|device_code|password|token";

    /** {@code "access_token": "..."} in a JSON body, capturing the name and the quoting. */
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "(\"(?:" + CREDENTIAL_NAMES + ")\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);

    /** {@code access_token=...} in a form body or a query string. */
    private static final Pattern PARAM = Pattern.compile(
            "((?:^|[?&])(?:" + CREDENTIAL_NAMES + ")=)([^&\\s]*)",
            Pattern.CASE_INSENSITIVE);

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

    /**
     * A request URI with any credential-bearing query parameter blanked. Applied to the
     * trace's URI, which is otherwise reproduced verbatim in every report.
     */
    public static URI redactUri(URI uri) {
        if (uri == null || uri.getRawQuery() == null) {
            return uri;
        }
        String redacted = replace(PARAM, uri.toString());
        return redacted.equals(uri.toString()) ? uri : URI.create(redacted);
    }

    /** A body with credential-bearing JSON members and form fields blanked, then truncated. */
    public static String redactBody(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        return truncate(replace(PARAM, replace(JSON_MEMBER, body)));
    }

    public static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_BODY_CHARS) + "... [truncated " + (body.length() - MAX_BODY_CHARS) + " chars]";
    }

    private static String replace(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String tail = m.groupCount() >= 3 ? m.group(3) : "";
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + REDACTED + tail));
        }
        m.appendTail(out);
        return out.toString();
    }
}
