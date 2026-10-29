package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The registered client adapters and client-session services (CLIENT-TESTING.md section 14.3), as
 * {@code adapters.yaml} declares them: the driver starts only these commands and uses only these
 * services, and everything else names them by id. It is to driving what {@code targets.yaml} is to
 * server runs (DESIGN.md section 7.1).
 *
 * <pre>
 * services:
 *   local: http://localhost:18090/touchstone/clients
 * clients:
 *   my-client:
 *     command: [node, adapter.mjs]
 *     directory: ../my-client      # relative to this file; optional
 *     environment: {DEBUG: "0"}    # optional
 * </pre>
 */
public record AdapterRegistry(Map<String, URI> services, Map<String, Client> clients) {

    /** A registered adapter: what to run, where, and with what environment. */
    public record Client(List<String> command, Path directory, Map<String, String> environment) {
    }

    public static AdapterRegistry load(Path file) {
        JsonNode doc;
        try {
            doc = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the adapter registry " + file, e);
        }
        if (doc == null || !doc.isObject()) {
            throw new IllegalArgumentException(file + " is not a mapping of services and clients");
        }
        Path base = file.toAbsolutePath().getParent();
        Map<String, URI> services = new LinkedHashMap<>();
        doc.path("services").properties().forEach(e -> {
            URI u = URI.create(e.getValue().asText());
            if (!u.isAbsolute() || !(u.getScheme().equals("http") || u.getScheme().equals("https"))) {
                throw new IllegalArgumentException(file + ": service " + e.getKey() + " is not an http(s) URL");
            }
            services.put(e.getKey(), URI.create(e.getValue().asText().replaceAll("/+$", "")));
        });
        Map<String, Client> clients = new LinkedHashMap<>();
        doc.path("clients").properties().forEach(e -> {
            JsonNode c = e.getValue();
            List<String> command = new ArrayList<>();
            c.path("command").forEach(part -> command.add(part.asText()));
            if (command.isEmpty() || !c.path("command").isArray()) {
                throw new IllegalArgumentException(file + ": client " + e.getKey() + " has no command, a list of strings");
            }
            Path directory = c.hasNonNull("directory") ? base.resolve(c.path("directory").asText()).normalize() : base;
            Map<String, String> environment = new LinkedHashMap<>();
            c.path("environment").properties().forEach(v -> environment.put(v.getKey(), v.getValue().asText()));
            clients.put(e.getKey(), new Client(List.copyOf(command), directory, Map.copyOf(environment)));
        });
        return new AdapterRegistry(Map.copyOf(services), Map.copyOf(clients));
    }

    /** Starts the adapter registered as {@code id}, its stderr to a log under {@code logs}. */
    public AdapterLauncher launcher(String id, Path logs) {
        Client c = clients.get(id);
        if (c == null) {
            throw new IllegalArgumentException("no client " + id + " is registered; registered: " + String.join(", ", clients.keySet()));
        }
        return AdapterLauncher.process(c.command(), c.directory(), c.environment(), logs.resolve(id + ".log"));
    }

    /** The service registered as {@code id}, or the only one when {@code id} is null. */
    public URI service(String id) {
        if (id == null) {
            if (services.size() != 1) {
                throw new IllegalArgumentException("name a service: registered are " + String.join(", ", services.keySet()));
            }
            return services.values().iterator().next();
        }
        URI u = services.get(id);
        if (u == null) {
            throw new IllegalArgumentException("no service " + id + " is registered; registered: " + String.join(", ", services.keySet()));
        }
        return u;
    }
}
