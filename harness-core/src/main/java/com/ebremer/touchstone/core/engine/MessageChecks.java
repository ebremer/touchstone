package com.ebremer.touchstone.core.engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The expectations of EXECUTION.md section 7 that apply to any HTTP message, not only to a
 * response: the status, the {@code Link} field and other header fields. The engine checks
 * responses with them; the client service checks the requests clients send (OBSERVATION.md
 * section 5), through {@link Matching}. Each check appends one result per expectation it checks
 * and returns false at the first failure.
 */
final class MessageChecks {

    private MessageChecks() {
    }

    /** EXECUTION.md section 7.1: the status equals a listed integer, or falls in a listed class ("4xx"). */
    static boolean statusMatches(JsonNode spec, int status) {
        for (JsonNode s : spec.isArray() ? spec : List.of(spec)) {
            if (s.isInt() ? s.asInt() == status
                    : s.asText().length() == 3 && s.asText().charAt(0) - '0' == status / 100) {
                return true;
            }
        }
        return false;
    }

    /** The statuses a {@code statusCode} value accepts, for a report. */
    static String statusText(JsonNode spec) {
        List<String> expected = new ArrayList<>();
        for (JsonNode s : spec.isArray() ? spec : List.of(spec)) {
            expected.add(s.asText());
        }
        return String.join(" or ", expected);
    }

    /** EXECUTION.md section 7.4, on the {@code Link} field lines of a message whose URL is {@code base}. */
    static boolean links(JsonNode expectations, List<String> fieldLines, URI base, Templates.Resolver resolver,
                         BiConsumer<String, String> bind, List<AssertionResult> results) {
        List<LinkValues.Link> links = LinkValues.parse(fieldLines, base);
        String actual = fieldLines.isEmpty() ? "no Link" : String.join(", ", fieldLines);
        for (JsonNode e : expectations) {
            String rel = e.path("rel").asText();
            String href = e.has("href") ? LinkValues.resolve(base, Templates.expand(e.get("href").asText(), resolver)) : null;
            String mediaType = e.has("mediaType") ? e.get("mediaType").asText() : null;
            LinkValues.Link match = null;
            for (LinkValues.Link link : links) {
                if (link.hasRel(rel) && (href == null || link.target().equals(href))
                        && (mediaType == null || mediaType.equalsIgnoreCase(link.param("type")))) {
                    match = link;
                    break;
                }
            }
            String wanted = "rel=\"" + rel + "\"" + (href == null ? "" : " to " + href)
                    + (mediaType == null ? "" : " type=\"" + mediaType + "\"");
            if (e.path("absent").asBoolean(false)) {
                if (!check(match == null, "Link " + wanted + " absent", "no such link", actual, results)) {
                    return false;
                }
                continue;
            }
            if (!check(match != null, "Link " + wanted, "a matching link", actual, results)) {
                return false;
            }
            if (e.has("capture")) {
                bind.accept(e.get("capture").asText(), match.target());
            }
        }
        return true;
    }

    /** EXECUTION.md section 7.5, on header fields that {@code header} returns by name, case-insensitively. */
    static boolean headers(JsonNode expectations, Function<String, List<String>> header, Templates.Resolver resolver,
                           BiConsumer<String, String> bind, List<AssertionResult> results) {
        for (JsonNode e : expectations) {
            String name = e.path("headerName").asText();
            List<String> lines = header.apply(name);
            String combined = String.join(", ", lines);
            String actual = lines.isEmpty() ? "absent" : combined;
            for (Map.Entry<String, JsonNode> op : e.properties()) {
                boolean ok;
                String expected;
                switch (op.getKey()) {
                    case "headerName" -> {
                        continue;
                    }
                    case "headerValue" -> {
                        String v = Templates.expand(op.getValue().asText(), resolver).trim();
                        expected = v;
                        ok = combined.trim().equals(v) || lines.stream().anyMatch(l -> l.trim().equals(v));
                    }
                    case "present" -> {
                        expected = op.getValue().asBoolean() ? "present" : "absent";
                        ok = op.getValue().asBoolean() != lines.isEmpty();
                    }
                    case "differsFrom" -> {
                        String v = Templates.expand(op.getValue().asText(), resolver).trim();
                        expected = "present and not " + v;
                        ok = !lines.isEmpty() && lines.stream().noneMatch(l -> l.trim().equals(v));
                    }
                    case "matches" -> {
                        Pattern p = Pattern.compile(op.getValue().asText());
                        expected = "matches /" + op.getValue().asText() + "/";
                        ok = p.matcher(combined).find() || lines.stream().anyMatch(l -> p.matcher(l).find());
                    }
                    case "capture" -> {
                        if (!check(!lines.isEmpty(), "header " + name + " captured", "present", actual, results)) {
                            return false;
                        }
                        bind.accept(op.getValue().asText(), combined);
                        continue;
                    }
                    default -> {
                        expected = op.getKey();
                        ok = false;
                    }
                }
                if (!check(ok, "header " + name + " " + op.getKey(), expected, actual, results)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean check(boolean ok, String description, String expected, String actual,
                                 List<AssertionResult> results) {
        results.add(ok ? AssertionResult.ok(description, expected, actual)
                : AssertionResult.failed(description, expected, actual));
        return ok;
    }
}
