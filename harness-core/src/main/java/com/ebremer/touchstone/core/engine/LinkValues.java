package com.ebremer.touchstone.core.engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 8288 section 3 {@code Link} header parsing, as EXECUTION.md section 7.4 requires:
 * comma-separated link-values in any number of field lines, quoted parameters, and
 * space-separated relation lists. Targets resolve against the request URL, and a link-value
 * whose {@code anchor} resolves elsewhere is about another resource, so it is dropped.
 */
final class LinkValues {

    /** One link-value: its resolved target, its relation types and its other parameters (names lower case). */
    record Link(String target, List<String> rels, Map<String, String> params) {

        /** Registered relation names compare case-insensitively, extension relation types (URIs) exactly. */
        boolean hasRel(String rel) {
            boolean uri = rel.indexOf(':') >= 0;
            for (String r : rels) {
                if (uri ? r.equals(rel) : r.equalsIgnoreCase(rel)) {
                    return true;
                }
            }
            return false;
        }

        String param(String name) {
            return params.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private LinkValues() {
    }

    /** Every link of every field line, in order, with the anchor rule applied. */
    static List<Link> parse(List<String> fieldLines, URI requestUrl) {
        List<Link> out = new ArrayList<>();
        for (String field : fieldLines) {
            for (String value : Headers.splitTopLevel(field)) {
                Link link = parseOne(value, requestUrl);
                if (link != null) {
                    out.add(link);
                }
            }
        }
        return out;
    }

    private static Link parseOne(String value, URI requestUrl) {
        String v = value.trim();
        int open = v.indexOf('<');
        int close = v.indexOf('>', open + 1);
        if (open != 0 || close < 0) {
            return null;
        }
        String target = v.substring(1, close).trim();
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : Headers.splitParams(v.substring(close + 1))) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            String name = (eq < 0 ? p : p.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String val = eq < 0 ? "" : Headers.unquote(p.substring(eq + 1).trim());
            // RFC 8288 section 3.3: occurrences after the first rel are ignored.
            params.putIfAbsent(name, val);
        }
        String resolved = resolve(requestUrl, target);
        String anchor = params.get("anchor");
        if (anchor != null && !resolve(requestUrl, anchor).equals(requestUrl.toString())) {
            return null;
        }
        List<String> rels = new ArrayList<>();
        String rel = params.get("rel");
        if (rel != null) {
            for (String r : rel.trim().split("\\s+")) {
                if (!r.isEmpty()) {
                    rels.add(r);
                }
            }
        }
        return new Link(resolved, List.copyOf(rels), Map.copyOf(params));
    }

    static String resolve(URI base, String reference) {
        try {
            return base == null ? reference : base.resolve(reference).toString();
        } catch (IllegalArgumentException e) {
            return reference;
        }
    }

    /** Serialises request links as EXECUTION.md section 6.2 says: {@code <href>; rel="rel"[; type="t"]}, joined by ", ". */
    static String serialize(List<String[]> links) {
        StringBuilder sb = new StringBuilder();
        for (String[] l : links) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append('<').append(l[0]).append(">; rel=\"").append(l[1]).append('"');
            if (l[2] != null) {
                sb.append("; type=\"").append(l[2]).append('"');
            }
        }
        return sb.toString();
    }
}
