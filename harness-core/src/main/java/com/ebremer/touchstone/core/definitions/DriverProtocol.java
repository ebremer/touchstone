package com.ebremer.touchstone.core.definitions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The frozen driver protocol's schema, {@code driver/protocol.schema.json} (driver/PROTOCOL.md,
 * D-0104), as bundled on the classpath: the operations a rule's drive may name and their
 * arguments, for the lint here and for the driver's checks of every line.
 */
public final class DriverProtocol {

    /** The classpath copy of {@code driver/protocol.schema.json}; a test keeps the two identical. */
    public static final String SCHEMA_RESOURCE = "/touchstone/driver/protocol.schema.json";

    private static final String TEXT = load();
    private static final JsonNode DEFS;

    static {
        try {
            DEFS = new ObjectMapper().readTree(TEXT).path("$defs");
        } catch (IOException e) {
            throw new UncheckedIOException("the bundled driver protocol schema is not JSON", e);
        }
    }

    private DriverProtocol() {
    }

    /** The schema's text, for a validator. */
    public static String schemaText() {
        return TEXT;
    }

    /** Every operation of the protocol, {@code shutdown} included. */
    public static Set<String> operations() {
        Set<String> out = new LinkedHashSet<>();
        DEFS.path("operation").path("enum").forEach(n -> out.add(n.asText()));
        return out;
    }

    /** The arguments an operation takes, or an empty set for one the protocol does not define. */
    public static Set<String> arguments(String op) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> e : DEFS.path("args-" + op).path("properties").properties()) {
            out.add(e.getKey());
        }
        return out;
    }

    /** The arguments an operation requires. */
    public static List<String> required(String op) {
        List<String> out = new ArrayList<>();
        DEFS.path("args-" + op).path("required").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String load() {
        try (InputStream in = DriverProtocol.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing schema resource " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read schema resource " + SCHEMA_RESOURCE, e);
        }
    }
}
