package com.ebremer.touchstone.core.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Loads the out-of-band target registry (targets.yaml):
 *
 * <pre>
 * targets:
 *   ref:
 *     baseUrl: http://localhost:4711/
 *     adapter: env
 *     capabilities: []
 *     properties: {}
 * </pre>
 */
public final class TargetRegistry {

    private final Map<String, Target> targets;

    private TargetRegistry(Map<String, Target> targets) {
        this.targets = targets;
    }

    public static TargetRegistry load(Path file) {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read target registry " + file, e);
        }
        Map<String, Target> targets = new LinkedHashMap<>();
        JsonNode entries = root.path("targets");
        entries.properties().forEach(entry -> {
            String id = entry.getKey();
            JsonNode t = entry.getValue();
            if (!t.has("baseUrl")) {
                throw new IllegalArgumentException("target '" + id + "' has no baseUrl in " + file);
            }
            Map<String, String> properties = new LinkedHashMap<>();
            t.path("properties").properties()
                    .forEach(p -> properties.put(p.getKey(), p.getValue().asText()));
            Set<String> capabilities = new LinkedHashSet<>();
            t.path("capabilities").forEach(c -> capabilities.add(c.asText()));
            targets.put(id, new Target(
                    id,
                    URI.create(t.get("baseUrl").asText()),
                    t.has("adapter") ? t.get("adapter").asText() : "env",
                    Map.copyOf(properties),
                    Set.copyOf(capabilities)));
        });
        return new TargetRegistry(Map.copyOf(targets));
    }

    public Optional<Target> find(String id) {
        return Optional.ofNullable(targets.get(id));
    }

    public Set<String> ids() {
        return targets.keySet();
    }
}
