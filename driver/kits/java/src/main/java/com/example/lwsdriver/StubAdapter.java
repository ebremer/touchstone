package com.example.lwsdriver;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The starting point: an adapter that implements only {@code configure}, so every other operation
 * answers {@code unsupported}. It passes {@code touchstone driver check} as it is. Replace the client
 * name, keep your client in a field that {@link #configure} builds, and implement the operations your
 * client has, one by one.
 */
public final class StubAdapter implements LwsDriverAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public ClientInfo client() {
        return new ClientInfo("My LWS client (Java)", "0.0.0", null);
    }

    @Override
    public Set<String> auth() {
        return Set.of("none");
    }

    @Override
    public JsonNode configure(JsonNode args) throws AdapterError {
        String type = args.path("auth").path("type").asText();
        if (!auth().contains(type)) {
            throw AdapterError.unsupported("authentication " + type);
        }
        // Build your client here, with the authentication args.auth describes.
        return JSON.createObjectNode();
    }

    public static void main(String[] args) {
        AdapterRunner.serve(new StubAdapter(), System.in, System.out);
        System.exit(0);
    }
}
