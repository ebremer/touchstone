package com.ebremer.touchstone.core.definitions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * One step of a client rule's drive (OBSERVATION.md section 12, since 0.16.0): an operation of the
 * driver protocol, the start of the rule's task, or a pause.
 *
 * @param op          the operation (driver/PROTOCOL.md section 5), or null for the other two kinds
 * @param args        the operation's arguments, whose strings are templates; empty when not an operation
 * @param capture     names bound to JSON Pointers into the operation's result, in order
 * @param allowStatus statuses, such as {@code 405} or {@code 4xx}, the operation may end with in an
 *                    error without stopping the drive
 * @param startTask   whether the step starts the rule's task
 * @param waitSeconds the pause, from 1 to 10 seconds, or 0
 */
public record DriveStep(String op, JsonNode args, Map<String, String> capture, List<String> allowStatus,
        boolean startTask, int waitSeconds) {

    public DriveStep {
        capture = Map.copyOf(capture);
        allowStatus = List.copyOf(allowStatus);
    }

    public boolean isOperation() {
        return op != null;
    }

    /** Whether an error with this status lets the drive go on. */
    public boolean allows(int status) {
        for (String s : allowStatus) {
            if (s.endsWith("xx") ? status / 100 == s.charAt(0) - '0' : Integer.parseInt(s) == status) {
                return true;
            }
        }
        return false;
    }

    /** Reads a step the schema has checked. Capture order is kept, though no step depends on it. */
    static DriveStep of(JsonNode step) {
        if (step.has("startTask")) {
            return new DriveStep(null, JsonNodeFactory.instance.objectNode(), Map.of(), List.of(), true, 0);
        }
        if (step.has("waitSeconds")) {
            return new DriveStep(null, JsonNodeFactory.instance.objectNode(), Map.of(), List.of(), false,
                    step.path("waitSeconds").asInt());
        }
        Map<String, String> capture = new LinkedHashMap<>();
        step.path("capture").properties().forEach(e -> capture.put(e.getKey(), e.getValue().asText()));
        List<String> allow = new ArrayList<>();
        step.path("allowStatus").forEach(s -> allow.add(s.asText()));
        JsonNode args = step.has("args") ? step.get("args") : JsonNodeFactory.instance.objectNode();
        return new DriveStep(step.path("op").asText(), args, capture, allow, false, 0);
    }
}
