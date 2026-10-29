package com.ebremer.touchstone.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.ebremer.touchstone.clients.ClientLab;
import com.ebremer.touchstone.clients.ClientLabConfig;
import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.fixtures.client.RefAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code touchstone drive} end to end (CLIENT-TESTING.md section 14.2): a registry naming the
 * reference adapter as a command, a client-session service in process, and the results it writes.
 */
class DriveCommandTest {

    @Test
    void drivesARegisteredClientAndWritesTheResults(@TempDir Path tmp) throws Exception {
        int port = freePort();
        URI service = URI.create("http://localhost:" + port + "/touchstone/clients");
        ClientLabConfig config = ClientLabConfig.defaults(service, "127.0.0.1", port).withPrivateInboxes(true);
        try (ClientLab lab = ClientLab.start(config, DefinitionLoader.loadClientRules(Path.of("..", "definitions"),
                CatalogRepository.load(Path.of("..", "catalog"))))) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace("\\", "/");
            String classpath = System.getProperty("java.class.path").replace("\\", "/");
            Path registry = tmp.resolve("adapters.yaml");
            Files.writeString(registry, "services:\n  here: " + service + "\nclients:\n  ref:\n    command: ["
                    + List.of(java, "-cp", classpath, RefAdapter.class.getName()).stream()
                            .map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")) + "]\n");
            Path out = tmp.resolve("results");
            StringWriter stdout = new StringWriter();
            StringWriter stderr = new StringWriter();
            CommandLine cmd = new CommandLine(new TouchstoneCli());
            cmd.setOut(new PrintWriter(stdout));
            cmd.setErr(new PrintWriter(stderr));
            int code = cmd.execute("drive", "--registry", registry.toString(), "--client", "ref",
                    "--definitions", "../definitions", "--catalog", "../catalog",
                    "--rule", "client-put-conditional", "--rule", "client-delete-conditional", "--out", out.toString());
            assertThat(code).as(stdout + "\n" + stderr).isZero();
            assertThat(stdout.toString()).contains("Touchstone reference client: no MUST failure");
            assertThat(stdout.toString()).doesNotContain("#key=");
            JsonNode results = new ObjectMapper().readTree(Files.readString(out.resolve("results.json")));
            for (JsonNode r : results.path("rules")) {
                if (r.path("rule").asText().equals("client-put-conditional")) {
                    assertThat(r.path("outcome").asText()).isEqualTo("passed");
                    assertThat(r.path("drive").path("status").asText()).isEqualTo("completed");
                }
            }
            assertThat(Files.readString(out.resolve("earl.ttl"))).contains("earl:automatic");
            assertThat(out.resolve("junit.xml")).exists();
            assertThat(out.resolve("adapter-logs").resolve("ref.log")).exists();
        }
    }

    @Test
    void anUnregisteredClientProducesNoVerdict(@TempDir Path tmp) throws Exception {
        Path registry = tmp.resolve("adapters.yaml");
        Files.writeString(registry, "services:\n  here: http://localhost:1/touchstone/clients\nclients: {}\n");
        StringWriter stderr = new StringWriter();
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setErr(new PrintWriter(stderr));
        int code = cmd.execute("drive", "--registry", registry.toString(), "--client", "nobody",
                "--definitions", "../definitions", "--catalog", "../catalog", "--out", tmp.resolve("r").toString());
        assertThat(code).isEqualTo(TouchstoneCli.HARNESS_ERROR);
        assertThat(stderr.toString()).contains("no client nobody");
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
