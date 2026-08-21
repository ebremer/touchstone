package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads RFC 8288 {@code Link} headers, so a manifest can follow a relation instead of
 * guessing a URI.
 *
 * <p>This is what lets a test about a resource's linkset stay portable. LWS says a linkset is
 * discovered through {@code rel="linkset"} (RFC 9264); it does not say a linkset lives at
 * {@code {resource}.meta}. That is one server's convention, and a manifest that hard-codes it
 * tests that server rather than the specification.
 *
 * <p>Both framings of a multi-link response are accepted, because both are legal: several
 * {@code Link} header fields, or one field holding comma-separated link-values.
 */
public final class LinkHeaders {

    private LinkHeaders() {
    }

    /**
     * The target of the first link whose {@code rel} contains {@code rel}, resolved against
     * {@code base}, or null when the response advertises no such relation.
     *
     * <p>{@code rel} is compared case-insensitively and against each token of a space-separated
     * relation list, as RFC 8288 §3.3 allows ({@code rel="linkset alternate"}).
     */
    public static String target(HttpHeaders headers, String rel, URI base) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        String wanted = rel.trim().toLowerCase(Locale.ROOT);
        for (String field : headers.allValues("Link")) {
            for (String value : split(field)) {
                String target = targetOf(value);
                if (target == null) {
                    continue;
                }
                for (String token : relsOf(value)) {
                    if (token.equals(wanted)) {
                        return base == null ? target : base.resolve(target).toString();
                    }
                }
            }
        }
        return null;
    }

    /** Splits one header field into link-values on commas outside {@code <>} and quotes. */
    private static List<String> split(String field) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inAngle = false;
        boolean inQuote = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (inQuote) {
                // A quoted-string may escape its delimiter; the escaped char is never structural.
                if (c == '\\' && i + 1 < field.length()) {
                    cur.append(c).append(field.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == '<') {
                inAngle = true;
            } else if (c == '>') {
                inAngle = false;
            } else if (c == ',' && !inAngle) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String targetOf(String linkValue) {
        int open = linkValue.indexOf('<');
        int close = linkValue.indexOf('>', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String target = linkValue.substring(open + 1, close).trim();
        return target.isEmpty() ? null : target;
    }

    /** The tokens of the link's {@code rel} parameter, lower-cased; empty when it has none. */
    private static List<String> relsOf(String linkValue) {
        int close = linkValue.indexOf('>');
        String params = close < 0 ? linkValue : linkValue.substring(close + 1);
        for (String part : params.split(";")) {
            String p = part.trim();
            if (p.length() < 4 || !p.regionMatches(true, 0, "rel", 0, 3)) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq < 0 || !p.substring(0, eq).trim().equalsIgnoreCase("rel")) {
                continue;
            }
            String v = p.substring(eq + 1).trim();
            if (v.length() >= 2 && v.charAt(0) == '"' && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return List.of(v.toLowerCase(Locale.ROOT).split("\\s+"));
        }
        return List.of();
    }
}
