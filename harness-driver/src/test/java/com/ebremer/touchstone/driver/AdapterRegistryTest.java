package com.ebremer.touchstone.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The registry is the boundary of CLIENT-TESTING.md section 14.3: only what it names is started or used. */
class AdapterRegistryTest {

    @Test
    void readsServicesAndClients(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("adapters.yaml");
        Files.writeString(file, """
                services:
                  local: http://localhost:18090/touchstone/clients/
                clients:
                  node-client:
                    command: [node, adapter.mjs]
                    directory: clients/node
                    environment: {DEBUG: "0"}
                """);
        AdapterRegistry r = AdapterRegistry.load(file);
        assertThat(r.service(null)).isEqualTo(URI.create("http://localhost:18090/touchstone/clients"));
        AdapterRegistry.Client c = r.clients().get("node-client");
        assertThat(c.command()).containsExactly("node", "adapter.mjs");
        assertThat(c.directory()).isEqualTo(tmp.toAbsolutePath().resolve("clients/node").normalize());
        assertThat(c.environment()).containsEntry("DEBUG", "0");
        assertThatThrownBy(() -> r.launcher("not-registered", tmp)).hasMessageContaining("no client not-registered");
        assertThatThrownBy(() -> r.service("elsewhere")).hasMessageContaining("no service elsewhere");
    }

    @Test
    void aClientWithoutACommandIsRefused(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("adapters.yaml");
        Files.writeString(file, "services: {}\nclients:\n  broken: {command: node adapter.mjs}\n");
        assertThatThrownBy(() -> AdapterRegistry.load(file)).hasMessageContaining("has no command");
    }

    @Test
    void aRelativeProgramIsFoundInTheWorkingDirectoryOnEveryPlatform(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("build"));
        Files.writeString(tmp.resolve("build").resolve("adapter"), "");
        assertThat(AdapterLauncher.resolved(java.util.List.of("build/adapter", "--flag"), tmp))
                .containsExactly(tmp.resolve("build").resolve("adapter").toAbsolutePath().toString(), "--flag");
        assertThat(AdapterLauncher.resolved(java.util.List.of("node", "adapter.mjs"), tmp)).containsExactly("node", "adapter.mjs");
        assertThat(AdapterLauncher.resolved(java.util.List.of("build/missing"), tmp)).containsExactly("build/missing");
    }

    @Test
    void theRepositorysRegistryLoads() {
        AdapterRegistry r = AdapterRegistry.load(Path.of("..", "adapters.yaml"));
        assertThat(r.services()).containsKey("local");
    }
}
