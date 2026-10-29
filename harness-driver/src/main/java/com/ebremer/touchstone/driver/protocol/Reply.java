package com.ebremer.touchstone.driver.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An adapter's response to one request (driver/PROTOCOL.md section 3): a result, or an error,
 * which is an outcome the client reached, not a breakdown.
 */
public record Reply(boolean ok, JsonNode result, Error error) {

    /**
     * An error the client reported (section 4.2).
     *
     * @param kind    http, authentication, protocol, verification, transport, invalidArguments,
     *                unsupported or internal
     * @param status  the HTTP status of the response that caused it, or null
     * @param message what the client said
     */
    public record Error(String kind, Integer status, String message) {

        public String describe() {
            return kind + (status == null ? "" : " " + status) + (message == null || message.isBlank() ? "" : ": " + message);
        }
    }

    static Reply of(JsonNode line) {
        if (line.path("ok").asBoolean()) {
            return new Reply(true, line.path("result"), null);
        }
        JsonNode e = line.path("error");
        return new Reply(false, null, new Error(e.path("kind").asText(),
                e.hasNonNull("status") ? e.path("status").asInt() : null, e.path("message").asText(null)));
    }
}
