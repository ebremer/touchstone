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
 *   mine:
 *     storage: https://storage.example/
 * </pre>
 *
 * <p>A target names either a {@code baseUrl}, the container its run roots are created in, or a
 * {@code storage}, whose description names the storage root they are created in instead.
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
            if (t.has("baseUrl") == t.has("storage")) {
                throw new IllegalArgumentException("target '" + id + "' needs either a baseUrl or a storage, not "
                        + (t.has("baseUrl") ? "both" : "neither") + ", in " + file);
            }
            Map<String, String> properties = new LinkedHashMap<>();
            t.path("properties").properties()
                    .forEach(p -> properties.put(p.getKey(), p.getValue().asText()));
            Set<String> capabilities = new LinkedHashSet<>();
            t.path("capabilities").forEach(c -> capabilities.add(c.asText()));
            targets.put(id, new Target(
                    id,
                    t.has("baseUrl") ? URI.create(t.get("baseUrl").asText()) : null,
                    t.has("adapter") ? t.get("adapter").asText() : "env",
                    Map.copyOf(properties),
                    Set.copyOf(capabilities),
                    t.has("storage") ? storage(id, t.get("storage").asText(), file) : null));
        });
        return new TargetRegistry(Map.copyOf(targets));
    }

    /** A storage URL: absolute, http or https, since the engine dereferences it first. */
    private static URI storage(String id, String value, Path file) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null) {
            throw new IllegalArgumentException("target '" + id + "': its storage " + value
                    + " is not an absolute http or https URL, in " + file);
        }
        return uri;
    }

    public Optional<Target> find(String id) {
        return Optional.ofNullable(targets.get(id));
    }

    public Set<String> ids() {
        return targets.keySet();
    }
}
