package com.ebremer.touchstone.core.manifest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The classpath schema must stay byte-identical to the schema in docs/, and must load. */
class SchemaSyncTest {

    @Test
    void classpathSchemaMatchesTheFrozenDocsSchema() throws Exception {
        String resource;
        try (InputStream in = ManifestLoader.class.getResourceAsStream(ManifestLoader.SCHEMA_RESOURCE)) {
            assertThat(in).as("schema resource present").isNotNull();
            resource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String docs = Files.readString(Path.of("..", "docs", "manifest-schema", "manifest.schema.json"));
        assertThat(normalize(resource)).isEqualTo(normalize(docs));
    }

    /**
     * The schema has to be loadable, not merely present and in sync. {@code ManifestLoader}
     * compiles it in a static initialiser, so a schema that does not parse takes the class
     * down with an {@code ExceptionInInitializerError} and every test that loads a manifest
     * fails at once, saying nothing about why. A stray backslash did exactly that.
     */
    @Test
    void theSchemaIsValidJsonAndDeclaresTheVersionItClaims() throws Exception {
        JsonNode schema;
        try (InputStream in = ManifestLoader.class.getResourceAsStream(ManifestLoader.SCHEMA_RESOURCE)) {
            schema = new ObjectMapper().readTree(in);
        }
        assertThat(schema.get("$id").asText())
                .as("the $id is the schema's version and changing it is a recorded decision")
                .isEqualTo("https://example.org/touchstone/schema/manifest/1-1-0");

        String bind = schema.at("/$defs/step/properties/bind/additionalProperties/pattern").asText();
        assertThat("link:https://www.w3.org/ns/lws#storage").matches(bind);
        assertThat("header:Location").matches(bind);
        assertThat("status").matches(bind);
        assertThat("nonsense:x").doesNotMatch(bind);
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").strip();
    }
}
