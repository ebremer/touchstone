package com.ebremer.touchstone.core.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The inboxes the fixture host serves (EXECUTION.md section 5.4): one per running test that asks
 * for {@code ${test.inbox}}, recording what servers POST to it. Thread-safe: tests run in parallel,
 * and deliveries arrive on the fixture host's threads.
 */
final class Inboxes {

    /** A body larger than this is refused (413) and not recorded. */
    static final int MAX_BODY_BYTES = 1 << 20;
    /** Once an inbox holds this many deliveries, further ones are refused (429). */
    static final int MAX_DELIVERIES = 100;

    private static final Set<String> REDACTED = Set.of("authorization", "cookie", "proxy-authorization");

    /** The most statuses a test may script an inbox to answer with (section 5.4). */
    static final int MAX_SCRIPTED = 10;

    private final Map<String, List<ObjectNode>> open = new ConcurrentHashMap<>();
    /** Per inbox, the statuses still to answer deliveries with, the last repeating; absent: 202. */
    private final Map<String, List<Integer>> scripts = new ConcurrentHashMap<>();

    /** Opens a fresh inbox and returns its id, the path segment after {@code inbox/}. */
    String open() {
        String id = UUID.randomUUID().toString();
        open.put(id, new ArrayList<>());
        return id;
    }

    boolean isOpen(String id) {
        return open.containsKey(id);
    }

    /** Discards an inbox and its record; later deliveries to it are 404. */
    void close(String id) {
        open.remove(id);
        scripts.remove(id);
    }

    /**
     * Scripts how an inbox answers the deliveries that follow (section 5.4): {@code {"respond":
     * [503, 202]}} answers the next delivery 503 and every later one 202. Returns the status for
     * the PUT that asked: 204 done, 400 not such a document, 404 no such inbox.
     */
    int script(String id, JsonNode document) {
        if (!open.containsKey(id)) {
            return 404;
        }
        JsonNode respond = document == null ? null : document.get("respond");
        if (respond == null || !respond.isArray() || respond.isEmpty() || respond.size() > MAX_SCRIPTED) {
            return 400;
        }
        List<Integer> statuses = new ArrayList<>();
        for (JsonNode s : respond) {
            if (!s.isInt() || s.asInt() < 200 || s.asInt() > 599) {
                return 400;
            }
            statuses.add(s.asInt());
        }
        scripts.put(id, statuses);
        return 204;
    }

    /**
     * Records one delivery. Returns the status to answer: 202 recorded (or what the inbox was
     * scripted to answer), 404 no such inbox, 429 full. The caller has already refused an
     * oversized body.
     */
    int record(String id, String method, Map<String, List<String>> headers, byte[] body) {
        return record(id, method, headers, body, null);
    }

    /**
     * Records one delivery with what the fixture host learned about its signature and
     * Content-Digest ({@link HttpSignatures#inspect}), when it inspected them.
     */
    int record(String id, String method, Map<String, List<String>> headers, byte[] body, ObjectNode inspection) {
        List<ObjectNode> deliveries = open.get(id);
        if (deliveries == null) {
            return 404;
        }
        ObjectNode d = Templates.JSON.createObjectNode();
        d.put("method", method);
        ObjectNode h = d.putObject("headers");
        String contentType = null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            ArrayNode values = h.withArray(name);
            for (String v : e.getValue()) {
                values.add(REDACTED.contains(name) ? "[redacted]" : v);
            }
            if (name.equals("content-type") && !e.getValue().isEmpty()) {
                contentType = e.getValue().getFirst();
            }
        }
        if (contentType == null) {
            d.putNull("contentType");
        } else {
            d.put("contentType", contentType);
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode parsed;
        try {
            parsed = text.isBlank() ? TextNode.valueOf(text) : Templates.JSON.readTree(text);
        } catch (Exception e) {
            parsed = TextNode.valueOf(text);
        }
        d.set("body", parsed);
        // activity as an array, whether or not the server batched (section 5.4)
        ArrayNode activities = d.putArray("activities");
        JsonNode activity = parsed.path("activity");
        if (activity.isArray()) {
            activity.forEach(activities::add);
        } else if (activity.isObject()) {
            activities.add(activity);
        }
        if (inspection != null) {
            d.setAll(inspection);
        }
        d.put("receivedAt", Instant.now().toString());
        synchronized (deliveries) {
            if (deliveries.size() >= MAX_DELIVERIES) {
                return 429;
            }
            int status = 202;
            List<Integer> script = scripts.get(id);
            if (script != null) {
                synchronized (script) {
                    status = script.size() > 1 ? script.removeFirst() : script.getFirst();
                }
            }
            d.put("status", status);
            deliveries.add(d);
            return status;
        }
    }

    /** The record of an open inbox as the GET answers it, or null when there is no such inbox. */
    ObjectNode view(String id) {
        List<ObjectNode> deliveries = open.get(id);
        if (deliveries == null) {
            return null;
        }
        ObjectNode doc = Templates.JSON.createObjectNode();
        ArrayNode list = doc.putArray("deliveries");
        synchronized (deliveries) {
            deliveries.forEach(list::add);
        }
        return doc;
    }
}
