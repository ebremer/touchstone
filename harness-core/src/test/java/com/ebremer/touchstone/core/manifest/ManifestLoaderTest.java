package com.ebremer.touchstone.core.manifest;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void loadsTheFrozenWorkedExample() {
        Manifest m = ManifestLoader.load(
                Path.of("..", "docs", "manifest-schema", "example-container-containment.yaml"));

        assertThat(m.id()).isEqualTo("core/container-containment-after-post");
        assertThat(m.module()).isEqualTo("core");
        assertThat(m.defaultIdentity()).isEqualTo("alice");
        assertThat(m.requirements()).hasSize(2);
        assertThat(m.steps()).hasSize(3);

        Manifest.Step first = m.steps().getFirst();
        assertThat(first.request().method()).isEqualTo("GET");
        assertThat(first.request().headers()).containsEntry("Accept", java.util.List.of("application/lws+json"));
        assertThat(first.expect().status()).containsExactly(200);
        assertThat(first.expect().json()).hasSize(1);

        Manifest.Step second = m.steps().get(1);
        assertThat(second.bind()).containsEntry("created", "header:Location");

        Manifest.Step third = m.steps().get(2);
        assertThat(third.expect().graph().contains()).hasSize(1);
        assertThat(third.expect().json()).hasSize(2);
    }

    @Test
    void rejectsUnknownProperties() throws Exception {
        Path bad = tmp.resolve("bad.yaml");
        Files.writeString(bad, """
                schemaVersion: 1
                id: core/bad-test
                title: has a typo field
                requirements: [https://example.org/touchstone/req/x]
                bogus: true
                steps:
                  - request: { method: GET, target: "${test.container}" }
                """);
        assertThatThrownBy(() -> ManifestLoader.load(bad))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("violates schema");
    }

    @Test
    void rejectsBodyTogetherWithBodyRef() throws Exception {
        Path bad = tmp.resolve("both-bodies.yaml");
        Files.writeString(bad, """
                schemaVersion: 1
                id: core/both-bodies
                title: body and bodyRef are mutually exclusive
                requirements: [https://example.org/touchstone/req/x]
                steps:
                  - request:
                      method: POST
                      target: "${test.container}"
                      body: inline
                      bodyRef: bodies/file.txt
                """);
        assertThatThrownBy(() -> ManifestLoader.load(bad))
                .isInstanceOf(InvalidManifestException.class);
    }
}
