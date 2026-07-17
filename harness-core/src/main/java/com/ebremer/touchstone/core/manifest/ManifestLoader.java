package com.ebremer.touchstone.core.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/**
 * Loads YAML manifests, validating each against the frozen manifest schema v1
 * before mapping — a manifest that does not validate never reaches the executor.
 */
public final class ManifestLoader {

    /** Classpath copy of the frozen schema; a unit test keeps it byte-identical to docs/manifest-schema. */
    static final String SCHEMA_RESOURCE = "/touchstone/manifest-schema-1-0-0.json";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Schema SCHEMA = loadSchema();

    private ManifestLoader() {
    }

    public static Manifest load(Path file) {
        JsonNode root;
        try {
            root = YAML.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read manifest " + file, e);
        } catch (RuntimeException e) {
            throw new InvalidManifestException("manifest " + file + " is not parseable YAML: " + e.getMessage());
        }
        // Validation goes through the string API on purpose: networknt 3.x parses with its
        // own Jackson 3 internally, keeping tools.jackson types out of Touchstone (D-0016).
        List<com.networknt.schema.Error> violations = SCHEMA.validate(root.toString(), InputFormat.JSON);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("manifest ").append(file).append(" violates schema v1:");
            violations.forEach(v -> sb.append("\n  - ").append(v.getInstanceLocation()).append(": ").append(v.getMessage()));
            throw new InvalidManifestException(sb.toString());
        }
        return map(root, file);
    }

    /** Loads every {@code *.yaml} under {@code dir} (recursively, stable order). */
    public static List<Manifest> loadDirectory(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(ManifestLoader::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read manifests directory " + dir, e);
        }
    }

    private static Schema loadSchema() {
        try (InputStream in = ManifestLoader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing schema resource " + SCHEMA_RESOURCE);
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read schema resource", e);
        }
    }

    // ---- mapping (schema-validated input, so shapes are trusted here) ----

    private static Manifest map(JsonNode n, Path file) {
        return new Manifest(
                n.get("id").asText(),
                n.get("title").asText(),
                text(n, "description"),
                strings(n.get("requirements")),
                strings(n.get("capabilities")),
                strings(n.get("tags")),
                n.has("as") ? n.get("as").asText() : "anonymous",
                mapSteps(n.get("steps")),
                file);
    }

    private static List<Manifest.Step> mapSteps(JsonNode steps) {
        List<Manifest.Step> out = new ArrayList<>();
        for (JsonNode s : steps) {
            out.add(new Manifest.Step(
                    text(s, "name"),
                    text(s, "as"),
                    s.has("request") ? mapRequest(s.get("request")) : null,
                    text(s, "rawRequest"),
                    s.has("expect") ? mapExpect(s.get("expect")) : null,
                    stringMap(s.get("bind")),
                    s.has("timeoutMillis") ? s.get("timeoutMillis").asInt() : null));
        }
        return List.copyOf(out);
    }

    private static Manifest.RequestSpec mapRequest(JsonNode r) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (r.has("headers")) {
            r.get("headers").properties().forEach(e -> {
                JsonNode v = e.getValue();
                headers.put(e.getKey(), v.isArray() ? strings(v) : List.of(v.asText()));
            });
        }
        return new Manifest.RequestSpec(
                r.get("method").asText(),
                r.get("target").asText(),
                Map.copyOf(headers),
                text(r, "body"),
                text(r, "bodyRef"));
    }

    private static Manifest.Expectations mapExpect(JsonNode e) {
        Set<Integer> status = null;
        if (e.has("status")) {
            status = new LinkedHashSet<>();
            JsonNode st = e.get("status");
            if (st.isArray()) {
                for (JsonNode v : st) {
                    status.add(v.asInt());
                }
            } else {
                status.add(st.asInt());
            }
        }
        Map<String, Manifest.HeaderAssertion> headers = null;
        if (e.has("headers")) {
            headers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : e.get("headers").properties()) {
                headers.put(entry.getKey(), mapHeaderAssertion(entry.getValue()));
            }
        }
        List<Manifest.JsonAssertion> json = null;
        if (e.has("json")) {
            json = new ArrayList<>();
            for (JsonNode j : e.get("json")) {
                json.add(new Manifest.JsonAssertion(
                        j.get("pointer").asText(),
                        j.has("equals") ? j.get("equals") : null,
                        text(j, "matches"),
                        j.has("exists") ? j.get("exists").asBoolean() : null,
                        j.has("count") ? j.get("count").asInt() : null));
            }
            json = List.copyOf(json);
        }
        return new Manifest.Expectations(
                status == null ? null : Set.copyOf(status),
                headers == null ? null : Map.copyOf(headers),
                json,
                e.has("graph") ? mapGraph(e.get("graph")) : null,
                e.has("body") ? mapBody(e.get("body")) : null,
                e.has("connegEquivalent") ? strings(e.get("connegEquivalent").get("accepts")) : null);
    }

    private static Manifest.HeaderAssertion mapHeaderAssertion(JsonNode h) {
        if (h.isTextual()) {
            return new Manifest.HeaderAssertion(null, null, h.asText(), null, null);
        }
        return new Manifest.HeaderAssertion(
                h.has("present") ? h.get("present").asBoolean() : null,
                h.has("absent") ? h.get("absent").asBoolean() : null,
                text(h, "equals"),
                text(h, "matches"),
                text(h, "contains"));
    }

    private static Manifest.GraphAssertion mapGraph(JsonNode g) {
        return new Manifest.GraphAssertion(
                text(g, "parseAs"),
                g.has("contains") ? triples(g.get("contains")) : null,
                g.has("notContains") ? triples(g.get("notContains")) : null,
                text(g, "isomorphicTo"),
                text(g, "shacl"));
    }

    private static List<Manifest.TriplePattern> triples(JsonNode array) {
        List<Manifest.TriplePattern> out = new ArrayList<>();
        for (JsonNode t : array) {
            JsonNode o = t.get("o");
            Manifest.ObjectTerm term = o.isTextual()
                    ? new Manifest.ObjectTerm(o.asText(), null, null, null)
                    : new Manifest.ObjectTerm(null, o.get("value").asText(), text(o, "lang"), text(o, "datatype"));
            out.add(new Manifest.TriplePattern(t.get("s").asText(), t.get("p").asText(), term));
        }
        return List.copyOf(out);
    }

    private static Manifest.BodyAssertion mapBody(JsonNode b) {
        return new Manifest.BodyAssertion(
                text(b, "equalsRef"),
                text(b, "matches"),
                b.has("empty") ? b.get("empty").asBoolean() : null);
    }

    private static String text(JsonNode n, String field) {
        return n.has(field) ? n.get(field).asText() : null;
    }

    private static List<String> strings(JsonNode array) {
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return List.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonNode obj) {
        if (obj == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        obj.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        return Map.copyOf(out);
    }
}
