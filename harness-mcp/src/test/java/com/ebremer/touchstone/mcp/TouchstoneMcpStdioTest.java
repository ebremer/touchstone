package com.ebremer.touchstone.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ebremer.touchstone.clients.ClientLab;
import com.ebremer.touchstone.clients.ClientLabConfig;
import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.fixtures.client.RefAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stdio profile, run the way an MCP client runs it: as a separate process spoken to over
 * standard input and output (DESIGN.md paragraph 6).
 *
 * <p>Nothing tested this profile, and it shipped dead. It set {@code protocol: STDIO}, which is
 * not a Spring AI server protocol, so the MCP server auto-configuration never matched: the
 * process started, logged that it had started, and answered nothing (D-0048). A context test
 * would not have caught that, because the context starts either way. Only a reply proves it.
 *
 * <p>The first line the process writes must be the reply to {@code initialize}. Protocol framing
 * lives on stdout, so a banner or a log line ahead of it would corrupt every client.
 */
class TouchstoneMcpStdioTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long REPLY_TIMEOUT_SECONDS = 30;

    @TempDir
    Path work;

    @Test
    void theStdioProfileAnswersOverStandardInputAndOutput() throws Exception {
        Path stderr = work.resolve("stderr.txt");
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                TouchstoneMcpApplication.class.getName(),
                "--spring.profiles.active=stdio",
                "--touchstone.catalog=" + Path.of("..", "catalog").toAbsolutePath(),
                "--touchstone.definitions=" + Path.of("..", "definitions").toAbsolutePath(),
                "--touchstone.targets=" + Path.of("..", "targets.yaml").toAbsolutePath(),
                "--touchstone.runs=" + work.resolve("runs"),
                // The profile logs to a file in the working directory; keep it out of the module.
                "--logging.file.name=" + work.resolve("touchstone-mcp.log"));
        Process process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
        try (BufferedWriter in = process.outputWriter(StandardCharsets.UTF_8);
             BufferedReader out = process.inputReader(StandardCharsets.UTF_8)) {

            send(in, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "stdio-smoke-test", "version", "0"))));
            JsonNode initialized = firstLine(out, stderr);
            assertThat(initialized.path("id").asInt())
                    .as("the first line on stdout is the reply to initialize: %s", initialized)
                    .isEqualTo(1);
            assertThat(initialized.at("/result/serverInfo/name").asText()).isEqualTo("touchstone-harness");

            send(in, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            send(in, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", Map.of(
                    "name", "coverage",
                    "arguments", Map.of("module", "lws10-core"))));
            JsonNode reply = replyTo(2, out, stderr);
            assertThat(reply.at("/result/isError").asBoolean()).as("tool error: %s", reply).isFalse();
            assertThat(toolResult(reply).path("total").asInt()).isGreaterThan(100);
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Phase C8.2's acceptance (CLIENT-TESTING.md section 13): over stdio, an MCP client drives the
     * reference adapter through a session rule by rule, runs one operation, starts a task, arms a
     * fault and reads the findings by the session's id; and the tools refuse a client the adapter
     * registry does not name.
     */
    @Test
    void theDrivingToolsDriveAClientOverStandardInputAndOutput() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String service = "http://localhost:" + port + "/touchstone/clients";
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path registry = work.resolve("adapters.yaml");
        Files.writeString(registry, "services:\n  here: " + service + "\nclients:\n  ref:\n    command: ["
                + Stream.of(java, "-cp", System.getProperty("java.class.path"), RefAdapter.class.getName())
                        .map(a -> "\"" + a.replace('\\', '/') + "\"").collect(Collectors.joining(", ")) + "]\n");
        Path stderr = work.resolve("stderr-driver.txt");
        try (ClientLab lab = ClientLab.start(
                ClientLabConfig.defaults(URI.create(service), "127.0.0.1", port).withPrivateInboxes(true),
                DefinitionLoader.loadClientRules(Path.of("..", "definitions"), CatalogRepository.load(Path.of("..", "catalog"))))) {
            List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                    TouchstoneMcpApplication.class.getName(),
                    "--spring.profiles.active=stdio",
                    "--touchstone.catalog=" + Path.of("..", "catalog").toAbsolutePath(),
                    "--touchstone.definitions=" + Path.of("..", "definitions").toAbsolutePath(),
                    "--touchstone.targets=" + Path.of("..", "targets.yaml").toAbsolutePath(),
                    "--touchstone.runs=" + work.resolve("runs"),
                    "--touchstone.driver.registry=" + registry,
                    "--touchstone.driver.logs=" + work.resolve("adapter-logs"),
                    "--logging.file.name=" + work.resolve("touchstone-mcp-driver.log"));
            Process process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
            try (BufferedWriter in = process.outputWriter(StandardCharsets.UTF_8);
                 BufferedReader out = process.inputReader(StandardCharsets.UTF_8)) {
                send(in, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                        "protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "stdio-driver-test", "version", "0"))));
                assertThat(replyTo(1, out, stderr).path("id").asInt()).isEqualTo(1);
                send(in, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

                send(in, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
                List<String> names = new ArrayList<>();
                replyTo(2, out, stderr).at("/result/tools").forEach(t -> names.add(t.path("name").asText()));
                assertThat(names).contains("list_driven_clients", "drive_client", "get_drive", "drive_rule",
                        "client_operation", "start_task", "arm_fault", "stop_driven_client", "get_client_findings");

                JsonNode driven = call(in, out, stderr, 3, "drive_rule",
                        Map.of("clientId", "ref", "rule", "client-put-conditional"));
                assertThat(driven.path("outcome").asText()).as("%s", driven).isEqualTo("passed");
                String session = driven.path("session").asText();

                JsonNode listed = call(in, out, stderr, 4, "list_driven_clients", Map.of());
                String storage = listed.at("/sessions/0/storage").asText();
                assertThat(listed.toString()).doesNotContain("key=");

                JsonNode found = call(in, out, stderr, 5, "client_operation",
                        Map.of("clientId", "ref", "op", "discover_storage", "args", Map.of("url", storage), "session", session));
                assertThat(found.path("ok").asBoolean()).as("%s", found).isTrue();

                JsonNode task = call(in, out, stderr, 6, "start_task",
                        Map.of("rule", "client-create-container-type-link", "session", session));
                assertThat(task.path("rule").asText()).isEqualTo("client-create-container-type-link");
                call(in, out, stderr, 7, "client_operation", Map.of("clientId", "ref", "op", "create_container",
                        "args", Map.of("parent", found.at("/result/storageRoot").asText(), "slug", "box"), "session", session));
                assertThat(call(in, out, stderr, 8, "arm_fault", Map.of("fault", "pageGone", "session", session))
                        .path("fault").asText()).isEqualTo("pageGone");

                JsonNode findings = call(in, out, stderr, 9, "get_client_findings",
                        Map.of("session", session, "outcome", "passed"));
                List<String> passed = new ArrayList<>();
                findings.path("rules").forEach(r -> passed.add(r.path("rule").asText()));
                assertThat(passed).contains("client-put-conditional", "client-create-container-type-link");

                send(in, Map.of("jsonrpc", "2.0", "id", 10, "method", "tools/call", "params", Map.of(
                        "name", "drive_rule", "arguments", Map.of("clientId", "nobody", "rule", "client-put-conditional"))));
                JsonNode refused = replyTo(10, out, stderr);
                assertThat(refused.at("/result/isError").asBoolean()).as("%s", refused).isTrue();
                assertThat(refused.toString()).contains("no client nobody");
            } finally {
                // The server's adapters exit when their input ends, as the protocol says; wait for them,
                // so that nothing still holds their logs.
                List<ProcessHandle> adapters = process.descendants().toList();
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                for (ProcessHandle adapter : adapters) {
                    try {
                        adapter.onExit().get(10, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        adapter.destroyForcibly();
                    }
                }
            }
        }
    }

    private static JsonNode call(BufferedWriter in, BufferedReader out, Path stderr, int id, String tool,
                                 Map<String, Object> arguments) throws Exception {
        send(in, Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call", "params", Map.of(
                "name", tool, "arguments", arguments)));
        JsonNode reply = replyTo(id, out, stderr);
        assertThat(reply.at("/result/isError").asBoolean()).as("%s failed: %s", tool, reply).isFalse();
        return toolResult(reply);
    }

    private static void send(BufferedWriter in, Map<String, Object> message) throws IOException {
        in.write(JSON.writeValueAsString(message));
        in.newLine();
        in.flush();
    }

    /** A tool's result: its structured content when present, else the JSON its text content carries. */
    private static JsonNode toolResult(JsonNode reply) throws JsonProcessingException {
        JsonNode structured = reply.at("/result/structuredContent");
        return structured.isMissingNode() ? JSON.readTree(reply.at("/result/content/0/text").asText()) : structured;
    }

    /** The reply with this id, skipping notifications; every line must be a JSON-RPC message. */
    private static JsonNode replyTo(int id, BufferedReader out, Path stderr) throws Exception {
        while (true) {
            JsonNode message = firstLine(out, stderr);
            if (message.path("id").asInt(-1) == id) {
                return message;
            }
        }
    }

    private static JsonNode firstLine(BufferedReader out, Path stderr) throws Exception {
        String line;
        try {
            line = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("no reply on stdout within " + REPLY_TIMEOUT_SECONDS
                    + " s: the stdio profile started no MCP server (process stderr: " + stderr + ")", e);
        }
        assertThat(line).as("the server closed stdout (process stderr: %s)", stderr).isNotNull();
        try {
            return JSON.readTree(line);
        } catch (JsonProcessingException e) {
            throw new AssertionError("stdout carried a line that is not a protocol message: " + line, e);
        }
    }
}
