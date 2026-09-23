package com.ebremer.touchstone.core.definitions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.apicatalog.jsonld.JsonLd;
import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.document.Document;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.http.media.MediaType;
import com.apicatalog.jsonld.loader.DocumentLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/**
 * Loads {@code definitions/} as EXECUTION.md section 2 prescribes: YAML 1.2 Core Schema, then
 * the JSON Schema, then JSON-LD expansion in safe mode against the repository's own contexts,
 * then the lint. Any failure refuses the whole set with an {@link InvalidDefinitionsException}
 * before a single request is sent.
 *
 * <p>Traversal starts at {@code lws10/manifest.yamlld} and visits each manifest's
 * {@code include}s depth-first, in order, then its entries; the result is the run order and
 * the order of every report.
 */
public final class DefinitionLoader {

    /** Classpath copy of {@code definitions/schema/definitions.schema.json}; a test keeps them identical. */
    static final String SCHEMA_RESOURCE = "/touchstone/definitions/definitions.schema.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema SCHEMA = loadSchema();
    private static final Set<String> CONTEXTS = Set.of("context.jsonld", "touchstone.jsonld");

    private DefinitionLoader() {
    }

    /**
     * @param definitionsDir the {@code definitions/} directory, holding {@code lws10/} and {@code schema/}
     * @param catalog        requirement IRIs of the loaded catalog; a test citing any other is
     *                       refused (D-0039). Null skips that one check, for tools that load no catalog.
     */
    public static Definitions load(Path definitionsDir, Set<String> catalog) {
        Path lws10 = definitionsDir.resolve("lws10");
        if (!Files.isRegularFile(lws10.resolve("manifest.yamlld"))) {
            throw new InvalidDefinitionsException("no definitions at " + definitionsDir
                    + " (expected lws10/manifest.yamlld)");
        }
        checkFormat(definitionsDir);

        JsonNode registry = document(lws10, "identities.yamlld");
        Map<String, IdentityDefinition> identities = identities(registry);

        List<TestDefinition> tests = new ArrayList<>();
        visit(lws10, "manifest.yamlld", new HashSet<>(), tests);

        List<String> problems = DefinitionLint.check(tests, identities, catalog);
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("the definitions fail the lint (EXECUTION.md section 2.5):");
            problems.forEach(p -> sb.append("\n  - ").append(p));
            throw new InvalidDefinitionsException(sb.toString());
        }
        return new Definitions(definitionsDir, tests, identities);
    }

    /** The definitions must be written in the format this engine implements. */
    private static void checkFormat(Path definitionsDir) {
        Path schema = definitionsDir.resolve("schema").resolve("definitions.schema.json");
        String id;
        try {
            id = JSON.readTree(schema.toFile()).path("$id").asText();
        } catch (IOException e) {
            throw new InvalidDefinitionsException("cannot read " + schema
                    + ", so the definitions' format version is unknown: " + e.getMessage());
        }
        if (!Definitions.SCHEMA_ID.equals(id)) {
            throw new InvalidDefinitionsException("the definitions are written in format " + id
                    + ", but this engine implements " + Definitions.SCHEMA_ID
                    + " (format " + Definitions.FORMAT_VERSION + ")");
        }
    }

    private static void visit(Path lws10, String rel, Set<String> seen, List<TestDefinition> tests) {
        if (!seen.add(rel)) {
            throw new InvalidDefinitionsException(rel + " is included more than once");
        }
        JsonNode doc = document(lws10, rel);
        if (!"Manifest".equals(doc.path("type").asText())) {
            throw new InvalidDefinitionsException(rel + " is included as a manifest but is not one");
        }
        String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/') + 1) : "";
        for (JsonNode include : doc.path("include")) {
            visit(lws10, normalize(dir + include.asText()), seen, tests);
        }
        String manifestPath = rel.substring(0, rel.length() - ".yamlld".length());
        Path directory = lws10.resolve(rel).getParent();
        for (JsonNode entry : doc.path("entries")) {
            tests.add(test(entry, manifestPath, directory));
        }
    }

    private static TestDefinition test(JsonNode t, String manifestPath, Path directory) {
        String name = t.path("name").asText();
        List<StepDefinition> steps = new ArrayList<>();
        String identity = text(t, "as");
        if (t.has("steps")) {
            for (JsonNode s : t.path("steps")) {
                steps.add(new StepDefinition(s.path("label").asText(), text(s, "as"),
                        s.path("precondition").asBoolean(false), s.path("request"), s.path("response")));
            }
        } else {
            // The short form is a test of exactly one step, labelled with the test's label; its
            // identity is the test's (EXECUTION.md section 4.2).
            steps.add(new StepDefinition(t.path("label").asText(), null, false,
                    t.path("request"), t.path("response")));
        }
        return new TestDefinition(
                manifestPath + "#" + name,
                manifestPath,
                name,
                Definitions.BASE + manifestPath + "#" + name,
                t.path("type").asText(),
                t.path("label").asText(),
                t.path("level").asText(),
                t.path("status").asText(),
                strings(t.path("source")),
                strings(t.path("traits")),
                strings(t.path("requires")),
                identity,
                strings(t.path("requirements")),
                t.has("prereqs") ? t.get("prereqs") : null,
                List.copyOf(steps),
                directory);
    }

    private static Map<String, IdentityDefinition> identities(JsonNode registry) {
        Map<String, IdentityDefinition> out = new LinkedHashMap<>();
        for (JsonNode i : registry.path("identities")) {
            String name = i.path("id").asText().substring(1);
            if (out.containsKey(name)) {
                throw new InvalidDefinitionsException("identities.yamlld: identity " + name + " is defined twice");
            }
            out.put(name, new IdentityDefinition(
                    name,
                    i.path("label").asText(),
                    i.path("kind").asText(),
                    text(i, "basis"),
                    text(i, "fault"),
                    strings(i.path("requires")),
                    text(i, "suite"),
                    text(i, "tokenType"),
                    text(i, "algorithm"),
                    text(i, "webid"),
                    i.get("credentialHeader"),
                    i.get("credentialClaims"),
                    i.get("identityDocument"),
                    i.get("samlAssertion")));
        }
        return out;
    }

    /** Reads, validates and expands one document; {@code rel} is its path under {@code lws10/}. */
    private static JsonNode document(Path lws10, String rel) {
        Path file = lws10.resolve(rel);
        if (!Files.isRegularFile(file)) {
            throw new InvalidDefinitionsException(rel + " does not exist under " + lws10);
        }
        JsonNode doc = Yaml12.read(file);
        List<com.networknt.schema.Error> violations = SCHEMA.validate(doc.toString(), InputFormat.JSON);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder(rel).append(" violates the definitions schema:");
            violations.forEach(v -> sb.append("\n  - ").append(v.getInstanceLocation()).append(": ")
                    .append(v.getMessage()));
            throw new InvalidDefinitionsException(sb.toString());
        }
        expand(lws10, rel, doc);
        return doc;
    }

    /**
     * JSON-LD expansion with the repository's contexts only, never the network (D-0026), and
     * with undefined terms failing, which is JSON-LD's safe mode: a key the context does not
     * define would otherwise be dropped without a word.
     */
    private static void expand(Path lws10, String rel, JsonNode doc) {
        String stem = rel.substring(0, rel.length() - ".yamlld".length());
        DocumentLoader loader = (url, options) -> context(lws10, url);
        try {
            Document input = JsonDocument.of(new ByteArrayInputStream(JSON.writeValueAsBytes(doc)));
            JsonLd.expand(input)
                    .base(URI.create(Definitions.BASE + stem))
                    .loader(loader)
                    .undefinedTermsPolicy(JsonLdOptions.ProcessingPolicy.Fail)
                    .get();
        } catch (JsonLdError e) {
            throw new InvalidDefinitionsException(rel + " is not valid JSON-LD under its context: "
                    + e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Document context(Path lws10, URI url) throws JsonLdError {
        String iri = url.toString();
        String name = iri.startsWith(Definitions.BASE) ? iri.substring(Definitions.BASE.length()) : null;
        if (name == null || !CONTEXTS.contains(name)) {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                    "refusing to load <" + iri + ">: definitions may use only lws10/context.jsonld"
                            + " and lws10/touchstone.jsonld");
        }
        try (InputStream in = Files.newInputStream(lws10.resolve(name))) {
            return JsonDocument.of(MediaType.JSON_LD, in);
        } catch (IOException e) {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, "cannot read " + name, e);
        }
    }

    private static String normalize(String path) {
        return URI.create("x:/" + path).normalize().getPath().substring(1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isTextual()) {
            out.add(array.asText());
        }
        array.forEach(v -> out.add(v.asText()));
        return List.copyOf(out);
    }

    private static Schema loadSchema() {
        try (InputStream in = DefinitionLoader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing schema resource " + SCHEMA_RESOURCE);
            }
            // The string API on purpose: networknt 3.x parses with its own Jackson 3, and this
            // keeps tools.jackson types out of Touchstone (D-0016).
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read schema resource", e);
        }
    }
}
