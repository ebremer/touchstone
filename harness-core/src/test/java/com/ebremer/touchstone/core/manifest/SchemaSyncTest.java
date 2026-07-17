package com.ebremer.touchstone.core.manifest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The classpath schema must stay byte-identical to the frozen schema in docs/. */
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

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").strip();
    }
}
