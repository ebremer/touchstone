package com.ebremer.touchstone.clients;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a developer may say about their session (CLIENT-TESTING.md section 3), when starting it
 * or later: the client under test, which becomes the subject of the EARL report, and the areas
 * in scope. The document is untrusted input, so it is checked whole before anything applies:
 * {@code {"clientUnderTest": {"name": ..., "version": ..., "homepage": ...}, "areas": [...]}},
 * both members optional.
 *
 * @param client the client under test, or null to leave it as it is
 * @param areas the areas in scope, or null to leave them as they are
 */
record SessionSettings(Session.ClientUnderTest client, List<String> areas) {

    static final SessionSettings NONE = new SessionSettings(null, null);
    private static final int MAX_NAME = 100;
    private static final int MAX_VERSION = 50;
    private static final int MAX_HOMEPAGE = 500;

    /** The settings {@code doc} gives; an IllegalArgumentException says what is wrong with it. */
    static SessionSettings parse(JsonNode doc) {
        if (doc == null || doc.isMissingNode() || doc.isNull()) {
            return NONE;
        }
        if (!doc.isObject()) {
            throw new IllegalArgumentException("send a JSON object");
        }
        for (Iterator<String> it = doc.fieldNames(); it.hasNext(); ) {
            String member = it.next();
            if (!member.equals("clientUnderTest") && !member.equals("areas")) {
                throw new IllegalArgumentException("unknown member " + clip(member) + "; use clientUnderTest and areas");
            }
        }
        Session.ClientUnderTest client = null;
        JsonNode c = doc.get("clientUnderTest");
        if (c != null && !c.isNull()) {
            if (!c.isObject()) {
                throw new IllegalArgumentException("clientUnderTest is an object with name, version and homepage");
            }
            for (Iterator<String> it = c.fieldNames(); it.hasNext(); ) {
                String member = it.next();
                if (!member.equals("name") && !member.equals("version") && !member.equals("homepage")) {
                    throw new IllegalArgumentException("unknown member clientUnderTest." + clip(member));
                }
            }
            client = new Session.ClientUnderTest(text(c, "name", MAX_NAME), text(c, "version", MAX_VERSION),
                    homepage(text(c, "homepage", MAX_HOMEPAGE)));
        } else if (c != null) {
            client = Session.ClientUnderTest.UNNAMED;
        }
        List<String> areas = null;
        JsonNode a = doc.get("areas");
        if (a != null) {
            if (!a.isArray() || a.isEmpty()) {
                throw new IllegalArgumentException("areas lists one or more of " + String.join(", ", Session.AREAS));
            }
            Set<String> chosen = new HashSet<>();
            for (JsonNode area : a) {
                if (!area.isTextual() || !Session.AREAS.contains(area.asText())) {
                    throw new IllegalArgumentException("areas are " + String.join(", ", Session.AREAS));
                }
                chosen.add(area.asText());
            }
            areas = Session.AREAS.stream().filter(chosen::contains).toList();
        }
        return new SessionSettings(client, areas);
    }

    /** Applies the settings to {@code s}: what they leave null stays as it is. */
    void applyTo(Session s) {
        if (client != null) {
            s.clientUnderTest = client;
        }
        if (areas != null) {
            List<String> out = new ArrayList<>(Session.AREAS);
            out.removeAll(areas);
            s.judge.outOfScope(Set.copyOf(out));
        }
    }

    /** A string member, trimmed, at most {@code max} characters, without control characters; null when absent or empty. */
    private static String text(JsonNode object, String name, int max) {
        JsonNode v = object.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException("clientUnderTest." + name + " is a string");
        }
        String t = v.asText().strip();
        if (t.length() > max) {
            throw new IllegalArgumentException("clientUnderTest." + name + " is at most " + max + " characters");
        }
        if (t.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("clientUnderTest." + name + " has a control character");
        }
        return t.isEmpty() ? null : t;
    }

    /** An absolute http(s) URL with a host, as an EARL report can name it; null stays null. */
    private static String homepage(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI u = new URI(value);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && u.getHost() != null && u.getRawUserInfo() == null
                    && value.chars().noneMatch(ch -> ch == ' ' || ch == '<' || ch == '>' || ch == '"' || ch == '\\'
                    || ch == '{' || ch == '}' || ch == '|' || ch == '^' || ch == '`')) {
                return value;
            }
        } catch (URISyntaxException e) {
            // refused below
        }
        throw new IllegalArgumentException("clientUnderTest.homepage is an absolute http or https URL");
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
