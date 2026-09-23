package com.ebremer.touchstone.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
                "--touchstone.manifests=" + Path.of("..", "manifests").toAbsolutePath(),
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
