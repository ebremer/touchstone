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

    private final Map<String, List<ObjectNode>> open = new ConcurrentHashMap<>();

    /** Opens a fresh inbox and returns its id, the path segment after {@code inbox/}. */
    String open() {
        String id = UUID.randomUUID().toString();
        open.put(id, new ArrayList<>());
        return id;
    }

    /** Discards an inbox and its record; later deliveries to it are 404. */
    void close(String id) {
        open.remove(id);
    }

    /**
     * Records one delivery. Returns the status to answer: 202 recorded, 404 no such inbox, 429 full.
     * The caller has already refused an oversized body.
     */
    int record(String id, String method, Map<String, List<String>> headers, byte[] body) {
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
        d.put("receivedAt", Instant.now().toString());
        synchronized (deliveries) {
            if (deliveries.size() >= MAX_DELIVERIES) {
                return 429;
            }
            deliveries.add(d);
        }
        return 202;
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
