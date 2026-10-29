package com.ebremer.touchstone.driver.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.definitions.DriverProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/**
 * Checks protocol lines against the frozen {@code driver/protocol.schema.json} (D-0104): an
 * adapter's hello and responses, which are untrusted, and the driver's own requests, which come
 * from rules. A response carries no operation, so its result is checked against the result
 * schema of the request it answers.
 */
public final class ProtocolSchema {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProtocolSchema INSTANCE = new ProtocolSchema();

    private final Schema hello;
    private final Schema request;
    private final Schema response;
    private final Map<String, Schema> results = new HashMap<>();

    private ProtocolSchema() {
        JsonNode root;
        try {
            root = JSON.readTree(DriverProtocol.schemaText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        JsonNode defs = root.path("$defs");
        hello = part(registry, defs, "hello");
        request = part(registry, defs, "request");
        response = part(registry, defs, "response");
        for (String op : DriverProtocol.operations()) {
            results.put(op, part(registry, defs, "result-" + op));
        }
    }

    public static ProtocolSchema instance() {
        return INSTANCE;
    }

    /** Why a hello line is not one, or null. */
    public String checkHello(JsonNode line) {
        return problem(hello, line);
    }

    /** Why a request the driver built is not one, or null. */
    public String checkRequest(JsonNode line) {
        return problem(request, line);
    }

    /** Why a line is not a response, or null; its result is checked by {@link #checkResult}. */
    public String checkResponse(JsonNode line) {
        return problem(response, line);
    }

    /** Why a response's result is not one {@code op} gives, or null; an error has no result to check. */
    public String checkResult(JsonNode line, String op) {
        Schema result = results.get(op);
        return result == null || !line.path("ok").asBoolean() ? null : problem(result, line.path("result"));
    }

    private static String problem(Schema schema, JsonNode node) {
        List<com.networknt.schema.Error> errors = schema.validate(node.toString(), InputFormat.JSON);
        if (errors.isEmpty()) {
            return null;
        }
        com.networknt.schema.Error first = errors.getFirst();
        return "breaks the schema at " + first.getInstanceLocation() + ": " + first.getMessage();
    }

    /** One definition as a schema of its own: the definitions, and a reference to the one wanted. */
    private static Schema part(SchemaRegistry registry, JsonNode defs, String name) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        doc.set("$defs", defs);
        doc.put("$ref", "#/$defs/" + name);
        return registry.getSchema(new ByteArrayInputStream(doc.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
