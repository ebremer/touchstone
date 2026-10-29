package com.ebremer.touchstone.driver.protocol;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The line an adapter writes before reading anything (driver/PROTOCOL.md section 3).
 *
 * @param client     the client under test: its name, and its version and homepage when given
 * @param language   informative only; may be null
 * @param auth       the kinds of {@code configure} authentication the client supports
 * @param operations the operations the adapter implements; names this version does not define
 *                   are kept, and the driver never sends them
 */
public record Hello(ClientUnderTest client, String language, List<String> auth, List<String> operations) {

    public static final String PROTOCOL = "touchstone-driver/1";

    /** The client under test, as the hello names it; it becomes the session's EARL subject. */
    public record ClientUnderTest(String name, String version, String homepage) {
    }

    public Hello {
        auth = List.copyOf(auth);
        operations = List.copyOf(operations);
    }

    /** Whether the adapter implements an operation; {@code configure} and {@code shutdown} always are. */
    public boolean implementsOperation(String op) {
        return op.equals("configure") || op.equals("shutdown") || operations.contains(op);
    }

    /** Reads a hello line already checked against the schema. */
    static Hello of(JsonNode line) {
        JsonNode h = line.path("hello");
        JsonNode c = h.path("client");
        return new Hello(new ClientUnderTest(c.path("name").asText(), c.path("version").asText(null),
                c.path("homepage").asText(null)), h.path("language").asText(null), texts(h.path("auth")),
                texts(h.path("operations")));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
