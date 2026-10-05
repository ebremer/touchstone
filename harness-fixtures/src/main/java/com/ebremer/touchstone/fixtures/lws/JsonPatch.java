package com.ebremer.touchstone.fixtures.lws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON Patch (RFC 6902), the patch format every LWS server must support since the 5 October 2026
 * draft (DECISIONS.md D-0086), with JSON Pointer (RFC 6901) paths.
 *
 * <p>A patch applies to a copy of the target and the copy is returned only when every operation
 * succeeded, so a patch that fails part-way changes nothing (RFC 6902 section 5, RFC 5789 section
 * 2). A failure carries the status RFC 5789 section 2.2 suggests: 400 for a malformed patch
 * document, 409 when the patch cannot apply to the resource's current state (a missing location,
 * an index out of bounds, a {@code test} that does not match).
 */
final class JsonPatch {

    /** Why a patch did not apply, and the status to answer with. */
    static final class Failure extends Exception {
        private final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private JsonPatch() {
    }

    /** {@code patch} applied to a copy of {@code target}; {@code target} itself is never changed. */
    static JsonNode apply(JsonNode target, JsonNode patch) throws Failure {
        if (patch == null || !patch.isArray()) {
            throw new Failure(400, "a JSON Patch document is an array of operations");
        }
        JsonNode doc = target.deepCopy();
        for (JsonNode op : patch) {
            if (!op.isObject() || !op.path("op").isTextual() || !op.path("path").isTextual()) {
                throw new Failure(400, "every operation is an object with op and path");
            }
            List<String> path = pointer(op.get("path").asText());
            switch (op.get("op").asText()) {
                case "add" -> doc = add(doc, path, value(op));
                case "remove" -> doc = remove(doc, path);
                case "replace" -> {
                    JsonNode value = value(op);
                    doc = path.isEmpty() ? value : add(remove(doc, path), path, value);
                }
                case "move" -> {
                    List<String> from = from(op);
                    if (path.size() > from.size() && path.subList(0, from.size()).equals(from)) {
                        throw new Failure(409, "a value cannot move into one of its own children");
                    }
                    JsonNode value = get(doc, from);
                    doc = add(remove(doc, from), path, value);
                }
                case "copy" -> doc = add(doc, path, get(doc, from(op)).deepCopy());
                case "test" -> {
                    JsonNode value = value(op);
                    if (!same(get(doc, path), value)) {
                        throw new Failure(409, "test failed at " + op.get("path").asText());
                    }
                }
                default -> throw new Failure(400, "unknown operation " + op.get("op").asText());
            }
        }
        return doc;
    }

    private static JsonNode value(JsonNode op) throws Failure {
        if (!op.has("value")) {
            throw new Failure(400, op.get("op").asText() + " needs a value");
        }
        return op.get("value").deepCopy();
    }

    private static List<String> from(JsonNode op) throws Failure {
        if (!op.path("from").isTextual()) {
            throw new Failure(400, op.get("op").asText() + " needs from");
        }
        return pointer(op.get("from").asText());
    }

    /** RFC 6901: "" is the whole document; otherwise "/"-separated tokens, ~1 for "/" and ~0 for "~". */
    private static List<String> pointer(String text) throws Failure {
        List<String> tokens = new ArrayList<>();
        if (text.isEmpty()) {
            return tokens;
        }
        if (!text.startsWith("/")) {
            throw new Failure(400, "a JSON Pointer starts with /");
        }
        for (String raw : text.substring(1).split("/", -1)) {
            if (raw.matches(".*~([^01].*|$)")) {
                throw new Failure(400, "~ is escaped as ~0 or ~1 in a JSON Pointer");
            }
            tokens.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return tokens;
    }

    private static JsonNode get(JsonNode doc, List<String> path) throws Failure {
        JsonNode node = doc;
        for (String token : path) {
            node = child(node, token);
            if (node == null) {
                throw new Failure(409, "no value at /" + String.join("/", path));
            }
        }
        return node;
    }

    private static JsonNode child(JsonNode node, String token) {
        if (node.isObject()) {
            return node.get(token);
        }
        if (node.isArray()) {
            int i = index(token);
            return i >= 0 && i < node.size() ? node.get(i) : null;
        }
        return null;
    }

    /** An array index: digits without a leading zero, or -1 for anything else ("-" included). */
    private static int index(String token) {
        if (!token.matches("0|[1-9][0-9]{0,8}")) {
            return -1;
        }
        return Integer.parseInt(token);
    }

    private static JsonNode add(JsonNode doc, List<String> path, JsonNode value) throws Failure {
        if (path.isEmpty()) {
            return value;
        }
        JsonNode parent = get(doc, path.subList(0, path.size() - 1));
        String last = path.getLast();
        if (parent instanceof ObjectNode object) {
            object.set(last, value);
        } else if (parent instanceof ArrayNode array) {
            if (last.equals("-")) {
                array.add(value);
            } else {
                int i = index(last);
                if (i < 0 || i > array.size()) {
                    throw new Failure(409, "index " + last + " is outside the array");
                }
                array.insert(i, value);
            }
        } else {
            throw new Failure(409, "cannot add a member to a value that is neither an object nor an array");
        }
        return doc;
    }

    private static JsonNode remove(JsonNode doc, List<String> path) throws Failure {
        if (path.isEmpty()) {
            throw new Failure(409, "cannot remove the whole document");
        }
        JsonNode parent = get(doc, path.subList(0, path.size() - 1));
        String last = path.getLast();
        if (parent instanceof ObjectNode object && object.has(last)) {
            object.remove(last);
        } else if (parent instanceof ArrayNode array && index(last) >= 0 && index(last) < array.size()) {
            array.remove(index(last));
        } else {
            throw new Failure(409, "no value at /" + String.join("/", path));
        }
        return doc;
    }

    /**
     * RFC 6902 section 4.6's equality: same type; numbers equal by value; arrays element by
     * element in order; objects with the same members, in any order.
     */
    private static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.getNodeType() != b.getNodeType()) {
            return false;
        }
        if (a.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                if (!b.has(e.getKey()) || !same(e.getValue(), b.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
