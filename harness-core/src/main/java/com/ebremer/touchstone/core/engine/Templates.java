package com.ebremer.touchstone.core.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * {@code ${expression}} substitution (EXECUTION.md section 3). Plain substitution: nothing is
 * percent-encoded, escaped or trimmed. Inside a JSON value, a string that is exactly one
 * expression takes the expression's JSON value, so {@code "${now+300}"} becomes a number and
 * {@code "${self.publicJwk}"} an object.
 */
final class Templates {

    /** Resolves one expression, such as {@code test.container} or {@code now+300}, to its JSON value. */
    @FunctionalInterface
    interface Resolver {
        JsonNode resolve(String expression);
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern WHOLE = Pattern.compile("^\\$\\{([^}]*)}$");

    private Templates() {
    }

    static String expand(String template, Resolver resolver) {
        if (template == null) {
            return null;
        }
        Matcher m = EXPRESSION.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(text(resolver.resolve(m.group(1)))));
        }
        m.appendTail(out);
        return out.toString();
    }

    static JsonNode expandJson(JsonNode value, Resolver resolver) {
        if (value == null) {
            return null;
        }
        if (value.isTextual()) {
            Matcher whole = WHOLE.matcher(value.asText());
            if (whole.matches()) {
                return resolver.resolve(whole.group(1));
            }
            return TextNode.valueOf(expand(value.asText(), resolver));
        }
        if (value.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> e : value.properties()) {
                out.set(e.getKey(), expandJson(e.getValue(), resolver));
            }
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            value.forEach(v -> out.add(expandJson(v, resolver)));
            return out;
        }
        return value;
    }

    /** A JSON value as text: a string as it is, anything else as its compact JSON. */
    static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }
}
