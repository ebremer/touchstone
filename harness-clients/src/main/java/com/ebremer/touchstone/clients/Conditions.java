package com.ebremer.touchstone.clients;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.ebremer.touchstone.core.engine.Matching;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Whether a condition of a client rule holds of an exchange (OBSERVATION.md section 5). Terms
 * are checked in the contract's order, and the first that fails is the evidence. The request
 * terms are matched by harness-core's {@link Matching}, so a term means the same on a client's
 * request as on a server's response. {@code after} and {@code sameTarget} belong to trial
 * selection ({@link Judge}) and are ignored here.
 */
final class Conditions {

    /** A term that did not hold: which, what it expected, and what the exchange had. */
    record Failure(String term, String expected, String actual) {
    }

    private Conditions() {
    }

    /** Null when every term of {@code condition} holds of {@code x}, else the first failure. */
    static Failure check(JsonNode condition, Observed x) {
        Exchange.Annotations a = x.annotations();
        Failure f;
        // 1. server, role, method, builtBy, builtFromRole
        if ((f = oneOf(condition, "server", a.server())) != null
                || (f = oneOf(condition, "role", a.role())) != null
                || (f = oneOf(condition, "method", x.method())) != null
                || (f = oneOf(condition, "builtBy", a.builtBy())) != null
                || (f = oneOf(condition, "builtFromRole", a.builtFromRole())) != null) {
            return f;
        }
        // 2. statusCode
        if (condition.has("statusCode") && !Matching.status(condition.get("statusCode"), x.status())) {
            return new Failure("statusCode", Matching.statusText(condition.get("statusCode")), String.valueOf(x.status()));
        }
        // 3. the boolean annotations
        if ((f = bool(condition, "issued", a.issued())) != null
                || (f = bool(condition, "methodAdvertised", a.methodAdvertised())) != null
                || (f = bool(condition, "patchFormatAdvertised", a.patchFormatAdvertised())) != null
                || (f = bool(condition, "queryFormatAdvertised", a.queryFormatAdvertised())) != null
                || (f = bool(condition, "repeat", a.repeat())) != null
                || (f = bool(condition, "containerEmpty", a.containerEmpty())) != null) {
            return f;
        }
        // 4. presentation: every place the request carried a credential is listed
        if (condition.has("presentation")) {
            List<String> allowed = values(condition.get("presentation"));
            if (!allowed.containsAll(a.presentation())) {
                return new Failure("presentation", "only " + String.join(" or ", allowed),
                        String.join(" and ", a.presentation()));
            }
        }
        // 5. contentType
        if (condition.has("contentType")) {
            String want = condition.get("contentType").asText();
            String actual = Matching.essence(x.firstHeader("Content-Type"));
            if (!want.equals(actual)) {
                return new Failure("contentType", want, actual == null ? "no Content-Type" : actual);
            }
        }
        // 6. linkHeaders, otherHeaders
        if (condition.has("linkHeaders")) {
            AssertionResult r = Matching.links(condition.get("linkHeaders"), x.header("Link"), x.uri());
            if (r != null) {
                return new Failure("linkHeaders", r.expected(), r.actual());
            }
        }
        if (condition.has("otherHeaders")) {
            AssertionResult r = Matching.headers(condition.get("otherHeaders"), x::header);
            if (r != null) {
                return new Failure("otherHeaders: " + r.description(), r.expected(), r.actual());
            }
        }
        // 7. bodyMatches, json
        if (condition.has("bodyMatches")) {
            String pattern = condition.get("bodyMatches").asText();
            if (!Matching.find(pattern, x.bodyText())) {
                return new Failure("bodyMatches", "matches /" + pattern + "/", abbreviate(x.bodyText()));
            }
        }
        if (condition.has("json")) {
            JsonNode root = x.json();
            if (root == null) {
                return new Failure("json", "a JSON body", x.body().length == 0 ? "no body" : abbreviate(x.bodyText()));
            }
            AssertionResult r = Matching.json(condition.get("json"), root, x.uri());
            if (r != null) {
                return new Failure("json: " + r.description(), r.expected(), r.actual());
            }
        }
        // 8. anyOf
        if (condition.has("anyOf")) {
            List<String> misses = new ArrayList<>();
            for (JsonNode alternative : condition.get("anyOf")) {
                Failure miss = check(alternative, x);
                if (miss == null) {
                    return null;
                }
                misses.add(miss.term() + ": " + miss.actual());
            }
            return new Failure("anyOf", "one of " + misses.size() + " alternatives", String.join("; ", misses));
        }
        return null;
    }

    private static Failure oneOf(JsonNode condition, String term, String actual) {
        if (!condition.has(term)) {
            return null;
        }
        List<String> allowed = values(condition.get(term));
        return actual != null && allowed.contains(actual) ? null
                : new Failure(term, String.join(" or ", allowed), Objects.requireNonNullElse(actual, "none"));
    }

    private static Failure bool(JsonNode condition, String term, boolean actual) {
        if (!condition.has(term) || condition.get(term).asBoolean() == actual) {
            return null;
        }
        return new Failure(term, String.valueOf(!actual), String.valueOf(actual));
    }

    private static List<String> values(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(v -> out.add(v.asText()));
        } else {
            out.add(node.asText());
        }
        return out;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
