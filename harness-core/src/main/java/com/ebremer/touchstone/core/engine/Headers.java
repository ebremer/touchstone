package com.ebremer.touchstone.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lexing shared by the {@code Link} and {@code WWW-Authenticate} parsers, and media-type essence. */
final class Headers {

    private Headers() {
    }

    /** Splits a field value on commas outside angle brackets and quoted strings. */
    static List<String> splitTopLevel(String field) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean angle = false;
        boolean quote = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (quote) {
                if (c == '\\' && i + 1 < field.length()) {
                    cur.append(c).append(field.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    quote = false;
                }
            } else if (c == '"') {
                quote = true;
            } else if (c == '<') {
                angle = true;
            } else if (c == '>') {
                angle = false;
            } else if (c == ',' && !angle) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Splits link parameters on semicolons outside quoted strings. */
    static List<String> splitParams(String params) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (quote) {
                if (c == '\\' && i + 1 < params.length()) {
                    cur.append(c).append(params.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    quote = false;
                }
            } else if (c == '"') {
                quote = true;
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** A token as it is, or a quoted-string without its quotes and escapes. */
    static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < v.length() - 1; i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length() - 1) {
                    c = v.charAt(++i);
                }
                sb.append(c);
            }
            return sb.toString();
        }
        return v;
    }

    /** A media type's essence: type/subtype in lower case, parameters dropped (EXECUTION.md section 8). */
    static String essence(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        int semi = mediaType.indexOf(';');
        return (semi < 0 ? mediaType : mediaType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }
}
