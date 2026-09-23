package com.ebremer.touchstone.core.engine;

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code json} expectations of EXECUTION.md section 7.8, also used for a {@code jwt}'s
 * header and claims (7.9). Each expectation selects a value with its RFC 6901 pointer and
 * checks its operators in the order they appear. Matching is by content, never by position,
 * except where a pointer names a position on purpose.
 */
final class JsonChecks {

    private JsonChecks() {
    }

    /**
     * Checks {@code expectations} against {@code root}, appending one result per operator to
     * {@code results}. Returns false at the first failure, which ends the evaluation.
     */
    static boolean check(String what, JsonNode root, JsonNode expectations, Templates.Resolver resolver,
                         URI requestUrl, BiConsumer<String, String> capture, List<AssertionResult> results) {
        for (JsonNode e : expectations) {
            if (!checkOne(what, root, e, resolver, requestUrl, capture, results)) {
                return false;
            }
        }
        return true;
    }

    private static boolean checkOne(String what, JsonNode root, JsonNode e, Templates.Resolver resolver,
                                    URI requestUrl, BiConsumer<String, String> capture,
                                    List<AssertionResult> results) {
        String pointer = e.path("pointer").asText();
        String label = what + " " + (pointer.isEmpty() ? "(document)" : pointer);
        JsonNode selected = root.at(JsonPointer.compile(pointer));
        boolean missing = selected.isMissingNode();
        if (missing) {
            if (e.path("optional").asBoolean(false)) {
                results.add(AssertionResult.ok(label + " is optional", "absent or matching", "absent"));
                return true;
            }
            if (e.has("exists") && !e.get("exists").asBoolean()) {
                results.add(AssertionResult.ok(label + " exists: false", "nothing selected", "nothing selected"));
                return true;
            }
            return fail(results, label + " selects a value", "a value at " + pointer, "nothing");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = e.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> op = it.next();
            String name = op.getKey();
            JsonNode arg = op.getValue();
            boolean ok;
            String expected;
            switch (name) {
                case "pointer", "optional" -> {
                    continue;
                }
                case "exists" -> {
                    expected = arg.asBoolean() ? "a value" : "nothing";
                    ok = arg.asBoolean();
                }
                case "equals" -> {
                    JsonNode want = Templates.expandJson(arg, resolver);
                    expected = want.toString();
                    ok = jsonEquals(selected, want);
                }
                case "equalsIri" -> {
                    String want = Templates.expand(arg.asText(), resolver);
                    expected = want;
                    ok = selected.isTextual() && LinkValues.resolve(requestUrl, selected.asText()).equals(want);
                }
                case "hasValue" -> {
                    JsonNode want = Templates.expandJson(arg, resolver);
                    expected = "has value " + want;
                    ok = jsonEquals(selected, want) || (selected.isArray() && contains(selected, want));
                }
                case "matches" -> {
                    expected = "matches /" + arg.asText() + "/";
                    String text = selected.isTextual() ? selected.asText() : selected.toString();
                    ok = Pattern.compile(arg.asText()).matcher(text).find();
                }
                case "count" -> {
                    expected = "count " + arg.asInt();
                    ok = selected.isContainerNode() && selected.size() == arg.asInt();
                }
                case "jsonType" -> {
                    expected = "a JSON " + arg.asText();
                    ok = type(selected).equals(arg.asText());
                }
                case "some", "every", "none" -> {
                    expected = name + " element satisfies " + arg;
                    ok = quantified(name, what, selected, arg, resolver, requestUrl, capture);
                }
                case "capture" -> {
                    capture.accept(arg.asText(), Templates.text(selected));
                    results.add(AssertionResult.ok(label + " captured as " + arg.asText(), "a value", abbreviate(selected)));
                    continue;
                }
                default -> {
                    expected = name;
                    ok = false;
                }
            }
            String description = label + " " + name;
            if (!ok) {
                return fail(results, description, expected, abbreviate(selected));
            }
            results.add(AssertionResult.ok(description, expected, abbreviate(selected)));
        }
        return true;
    }

    private static boolean quantified(String quantifier, String what, JsonNode selected, JsonNode nested,
                                      Templates.Resolver resolver, URI requestUrl,
                                      BiConsumer<String, String> capture) {
        if (!selected.isArray()) {
            return false;
        }
        int satisfied = 0;
        for (JsonNode element : selected) {
            // Nested checks bind nothing: which element's value a capture would take is not
            // something a definition can rely on.
            boolean all = check(what, element, nested, resolver, requestUrl, (k, v) -> { }, new java.util.ArrayList<>());
            if (all) {
                satisfied++;
            }
        }
        return switch (quantifier) {
            case "some" -> satisfied > 0;
            case "every" -> satisfied == selected.size();
            default -> satisfied == 0;
        };
    }

    private static boolean fail(List<AssertionResult> results, String description, String expected, String actual) {
        results.add(AssertionResult.failed(description, expected, actual));
        return false;
    }

    /** Deep JSON equality: numbers compare numerically, object members in any order. */
    static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> m : a.properties()) {
                if (!b.has(m.getKey()) || !jsonEquals(m.getValue(), b.get(m.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode element : array) {
            if (jsonEquals(element, value)) {
                return true;
            }
        }
        return false;
    }

    static String type(JsonNode node) {
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return node.isArray() ? "array" : node.isObject() ? "object" : "unknown";
    }

    private static String abbreviate(JsonNode node) {
        String s = node.toString();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
