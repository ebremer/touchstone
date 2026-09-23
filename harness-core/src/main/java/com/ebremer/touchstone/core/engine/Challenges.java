package com.ebremer.touchstone.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 9110 section 11.6.1 {@code WWW-Authenticate} parsing, as EXECUTION.md section 7.6
 * requires. A field holds one or more challenges, and a challenge's auth-params are
 * comma-separated too, so a comma alone does not say where a challenge ends: an element that
 * starts with a token followed by whitespace (rather than {@code =}) opens a new challenge.
 * Parameter order is irrelevant, and a quoted value is unquoted.
 */
final class Challenges {

    /** One challenge: its scheme and its auth-params by lower-case name (first occurrence wins). */
    record Challenge(String scheme, Map<String, String> params, String token68) {

        String param(String name) {
            return params.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final Pattern PARAM = Pattern.compile("^(" + TOKEN + ")\\s*=\\s*(" + TOKEN + "|\"(?:[^\"\\\\]|\\\\.)*\")$");
    private static final Pattern SCHEME_AND_REST = Pattern.compile("^(" + TOKEN + ")(?:\\s+(.*))?$", Pattern.DOTALL);

    private Challenges() {
    }

    static List<Challenge> parse(List<String> fieldLines) {
        List<Challenge> out = new ArrayList<>();
        for (String field : fieldLines) {
            String scheme = null;
            Map<String, String> params = null;
            String token68 = null;
            for (String raw : Headers.splitTopLevel(field)) {
                String element = raw.trim();
                if (element.isEmpty()) {
                    continue;
                }
                Matcher param = PARAM.matcher(element);
                if (param.matches() && scheme != null) {
                    params.putIfAbsent(param.group(1).toLowerCase(Locale.ROOT), Headers.unquote(param.group(2)));
                    continue;
                }
                Matcher start = SCHEME_AND_REST.matcher(element);
                if (!start.matches()) {
                    continue;
                }
                if (scheme != null) {
                    out.add(new Challenge(scheme, Map.copyOf(params), token68));
                }
                scheme = start.group(1);
                params = new LinkedHashMap<>();
                token68 = null;
                String rest = start.group(2);
                if (rest != null && !rest.isBlank()) {
                    Matcher first = PARAM.matcher(rest.trim());
                    if (first.matches()) {
                        params.putIfAbsent(first.group(1).toLowerCase(Locale.ROOT), Headers.unquote(first.group(2)));
                    } else {
                        token68 = rest.trim();
                    }
                }
            }
            if (scheme != null) {
                out.add(new Challenge(scheme, Map.copyOf(params), token68));
            }
        }
        return out;
    }
}
