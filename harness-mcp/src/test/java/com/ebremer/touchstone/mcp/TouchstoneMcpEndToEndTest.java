package com.ebremer.touchstone.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 acceptance (DESIGN.md paragraph 9): a real MCP client can start a run, watch
 * progress, page failures, and pull a redacted trace end-to-end — here over streamable
 * HTTP against the booted Spring Boot server, targeting the in-memory reference LWS server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TouchstoneMcpEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static RefLwsServer sut;
    private static Path work;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void harnessLocations(DynamicPropertyRegistry registry) throws Exception {
        sut = RefLwsServer.start(0);
        work = Files.createTempDirectory("mcp-e2e");
        Path manifests = work.resolve("manifests").resolve("core");
        Files.createDirectories(manifests);
        Files.writeString(manifests.resolve("passing.yaml"), """
                schemaVersion: 1
                id: core/passing
                title: the run root is retrievable
                requirements: [https://example.org/touchstone/req/lws10-core/conneg-media-type-equivalence]
                steps:
                  - request: { method: GET, target: "${run.root}", headers: { Accept: application/lws+json } }
                    expect: { status: 200 }
                """);
        // Carries an Authorization header (as: alice) that the trace must redact, and expects the
        // wrong status so it fails — exercising get_failures and get_trace.
        Files.writeString(manifests.resolve("failing.yaml"), """
                schemaVersion: 1
                id: core/failing
                title: deliberately expects the wrong status
                requirements: [https://example.org/touchstone/req/lws10-core/head-parity-with-get]
                as: alice
                steps:
                  - request: { method: GET, target: "${run.root}", headers: { Accept: application/lws+json } }
                    expect: { status: 418 }
                """);
        Path targets = work.resolve("targets.yaml");
        Files.writeString(targets, """
                targets:
                  ref:
                    baseUrl: %s
                    adapter: env
                    properties:
                      token.alice: "secret-bearer-token-value"
                """.formatted(sut.baseUri()));

        registry.add("touchstone.catalog", () -> Path.of("..", "catalog").toString());
        registry.add("touchstone.manifests", () -> work.resolve("manifests").toString());
        registry.add("touchstone.targets", targets::toString);
        registry.add("touchstone.runs", () -> work.resolve("runs").toString());
    }

    @AfterAll
    static void stop() throws Exception {
        if (sut != null) {
            sut.close();
        }
    }

    @Test
    void startWatchPageAndPullRedactedTraceOverMcp() throws Exception {
        List<ProgressNotification> progress = new CopyOnWriteArrayList<>();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .progressConsumer(progress::add)
                .build()) {
            client.initialize();

            // read-only catalog tools
            JsonNode requirements = call(client, "list_requirements", Map.of("module", "lws10-core"));
            assertThat(requirements.isArray()).isTrue();
            assertThat(requirements.size()).isGreaterThan(100);

            JsonNode detail = call(client, "get_requirement",
                    Map.of("iri", "https://example.org/touchstone/req/lws10-core/conneg-media-type-equivalence"));
            assertThat(detail.get("clauseText").asText()).contains("Content-Type response header");

            JsonNode tests = call(client, "list_tests", Map.of());
            assertThat(tests.size()).isEqualTo(2);

            // start an async run with a progress token, then watch it to completion
            CallToolResult started = client.callTool(new CallToolRequest("start_run",
                    Map.of("targetId", "ref", "module", "core"),
                    Map.of("progressToken", "run-progress")));
            String runId = structured(started).get("runId").asText();
            assertThat(runId).isNotBlank();

            JsonNode run = null;
            for (int i = 0; i < 100; i++) {
                run = call(client, "get_run", Map.of("runId", runId));
                if ("COMPLETE".equals(run.get("status").asText())) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(run).isNotNull();
            assertThat(run.get("status").asText()).isEqualTo("COMPLETE");
            assertThat(run.get("completed").asInt()).isEqualTo(2);
            assertThat(run.get("total").asInt()).isEqualTo(2);
            assertThat(run.get("passed").asInt()).isEqualTo(1);
            assertThat(run.get("failed").asInt()).isEqualTo(1);
            assertThat(run.get("conformant").asBoolean()).isFalse();

            // progress notifications streamed during execution
            for (int i = 0; i < 40 && progress.isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertThat(progress).as("progress notifications received").isNotEmpty();
            assertThat(progress).allMatch(p -> "run-progress".equals(p.progressToken()));

            // page the failures — summaries only
            JsonNode failures = call(client, "get_failures", Map.of("runId", runId));
            assertThat(failures.get("totalFailures").asInt()).isEqualTo(1);
            assertThat(failures.get("failures").get(0).get("testId").asText()).isEqualTo("core/failing");

            // pull the one redacted trace: the Authorization header must be stripped
            JsonNode trace = call(client, "get_trace", Map.of("runId", runId, "testId", "core/failing"));
            assertThat(trace.get("untrustedNote").asText()).contains("untrusted");
            JsonNode requestHeaders = trace.get("steps").get(0).get("exchange").get("requestHeaders");
            assertThat(requestHeaders.get("Authorization").get(0).asText()).isEqualTo("[REDACTED]");
            assertThat(trace.toString()).doesNotContain("secret-bearer-token-value");

            // diff against a second run — identical outcomes, no regressions
            String runId2 = structured(client.callTool(new CallToolRequest("start_run",
                    Map.of("targetId", "ref", "module", "core"), Map.of()))).get("runId").asText();
            for (int i = 0; i < 100; i++) {
                if ("COMPLETE".equals(call(client, "get_run", Map.of("runId", runId2)).get("status").asText())) {
                    break;
                }
                Thread.sleep(50);
            }
            JsonNode diff = call(client, "diff_runs", Map.of("before", runId, "after", runId2));
            assertThat(diff.get("hasRegressions").asBoolean()).isFalse();
            assertThat(diff.get("unchanged").asInt()).isEqualTo(2);

            // the EARL report is available as an MCP resource
            ReadResourceResult earl = client.readResource(new ReadResourceRequest("report://" + runId + "/earl"));
            String earlText = ((TextResourceContents) earl.contents().get(0)).text();
            assertThat(earlText).contains("earl:Assertion").contains("earl:TestSubject");

            // the triage prompt guides the agent through the tools
            GetPromptResult triage = client.getPrompt(new GetPromptRequest("triage_run", Map.of("run_id", runId)));
            String promptText = ((TextContent) triage.messages().get(0).content()).text();
            assertThat(promptText).contains("get_failures").contains("get_trace");
        }
    }

    private static JsonNode call(McpSyncClient client, String tool, Map<String, Object> args) throws Exception {
        return structured(client.callTool(new CallToolRequest(tool, args)));
    }

    private static JsonNode structured(CallToolResult result) throws Exception {
        assertThat(result.isError()).as("tool error: %s", result.content()).isNotEqualTo(Boolean.TRUE);
        if (result.structuredContent() != null) {
            JsonNode node = JSON.valueToTree(result.structuredContent());
            // Spring AI wraps a non-object return (e.g. a list) under a synthetic property; unwrap it.
            if (node.isObject() && node.size() == 1 && node.properties().iterator().next().getValue().isArray()) {
                return node.properties().iterator().next().getValue();
            }
            return node;
        }
        String text = ((TextContent) result.content().get(0)).text();
        return JSON.readTree(text);
    }
}
