package com.ebremer.touchstone.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ebremer.touchstone.fixtures.ReferenceScenario;
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
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 acceptance (DESIGN.md section 9): a real MCP client can start a run, watch progress,
 * page failures, and pull a redacted trace end-to-end, here over streamable HTTP against the
 * booted Spring Boot server, targeting the secured reference deployment. The definitions are a
 * copy of the real ones with one test broken on purpose, so there is a failure to page and trace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TouchstoneMcpEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static ReferenceScenario sut;
    private static Path work;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void harnessLocations(DynamicPropertyRegistry registry) throws Exception {
        sut = ReferenceScenario.start(ReferenceScenario.Kind.SECURED);
        work = Files.createTempDirectory("mcp-e2e");
        Path definitions = copy(Path.of("..", "definitions"), work.resolve("definitions"));
        // getContainer runs as alice, so it carries an Authorization header the trace must
        // redact; expecting the wrong status makes it fail, for get_failures and get_trace.
        Path containers = definitions.resolve("lws10/core/containers.yamlld");
        String text = Files.readString(containers);
        String from = "      statusCode: 200\n      contentType: application/lws+json\n      otherHeaders:\n"
                + "        - headerName: ETag";
        assertThat(text).contains(from);
        Files.writeString(containers, text.replaceFirst(java.util.regex.Pattern.quote(from),
                from.replace("statusCode: 200", "statusCode: 418")));
        StringBuilder yaml = new StringBuilder("targets:\n  ref:\n    baseUrl: " + sut.storageBaseUri()
                + "\n    adapter: env\n    capabilities: [" + String.join(", ", sut.capabilities())
                + "]\n    properties:\n");
        sut.properties().forEach((k, v) -> yaml.append("      ").append(k).append(": '").append(v).append("'\n"));
        Path targets = work.resolve("targets.yaml");
        Files.writeString(targets, yaml.toString());

        registry.add("touchstone.catalog", () -> Path.of("..", "catalog").toString());
        registry.add("touchstone.definitions", definitions::toString);
        registry.add("touchstone.targets", targets::toString);
        registry.add("touchstone.runs", () -> work.resolve("runs").toString());
    }

    @AfterAll
    static void stop() throws Exception {
        if (sut != null) {
            sut.close();
        }
    }

    private static Path copy(Path source, Path target) throws java.io.IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        return target;
    }

    /**
     * Clients decide from these hints whether to ask before a call (D-0049). Without them,
     * every tool advertised the protocol's worst case: not read-only, destructive, open-world.
     * The two tools that send traffic to a target keep that; the nine that only read the
     * catalog, the definitions and recorded runs say so.
     */
    @Test
    void toolsDeclareWhetherTheyChangeAnything() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build()) {
            client.initialize();
            Map<String, ToolAnnotations> hints = client.listTools().tools().stream()
                    .collect(Collectors.toMap(Tool::name, Tool::annotations));

            assertThat(hints).hasSize(11);
            Set<String> drivesTheTarget = Set.of("start_run", "run_one");
            hints.forEach((tool, a) -> {
                boolean readOnly = !drivesTheTarget.contains(tool);
                assertThat(a.readOnlyHint()).as("%s readOnlyHint", tool).isEqualTo(readOnly);
                assertThat(a.destructiveHint()).as("%s destructiveHint", tool).isEqualTo(!readOnly);
                assertThat(a.idempotentHint()).as("%s idempotentHint", tool).isEqualTo(readOnly);
                assertThat(a.openWorldHint()).as("%s openWorldHint", tool).isEqualTo(!readOnly);
            });
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
            assertThat(tests.size()).isEqualTo(101);
            JsonNode containerTests = call(client, "list_tests", Map.of("module", "core/containers", "level", "MUST"));
            assertThat(containerTests.size()).isEqualTo(9);
            assertThat(containerTests.get(0).get("id").asText()).isEqualTo("core/containers#getContainer");

            // start an async run with a progress token, then watch it to completion
            CallToolResult started = client.callTool(new CallToolRequest("start_run",
                    Map.of("targetId", "ref", "module", "core/containers"),
                    Map.of("progressToken", "run-progress")));
            String runId = structured(started).get("runId").asText();
            assertThat(runId).isNotBlank();

            JsonNode run = null;
            for (int i = 0; i < 400; i++) {
                run = call(client, "get_run", Map.of("runId", runId));
                if ("COMPLETE".equals(run.get("status").asText())) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(run).isNotNull();
            assertThat(run.get("status").asText()).isEqualTo("COMPLETE");
            assertThat(run.get("completed").asInt()).isEqualTo(14);
            assertThat(run.get("total").asInt()).isEqualTo(14);
            assertThat(run.get("passed").asInt()).isEqualTo(13);
            assertThat(run.get("failed").asInt()).isEqualTo(1);
            assertThat(run.get("conformant").asBoolean()).isFalse();
            assertThat(run.get("byLevel").get(0).get("level").asText()).isEqualTo("MUST");
            assertThat(run.get("byLevel").get(0).get("failed").asInt()).isEqualTo(1);

            // progress notifications streamed during execution
            for (int i = 0; i < 40 && progress.isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertThat(progress).as("progress notifications received").isNotEmpty();
            assertThat(progress).allMatch(p -> "run-progress".equals(p.progressToken()));

            // page the failures — summaries only
            JsonNode failures = call(client, "get_failures", Map.of("runId", runId));
            assertThat(failures.get("totalFailures").asInt()).isEqualTo(1);
            assertThat(failures.get("failures").get(0).get("testId").asText())
                    .isEqualTo("core/containers#getContainer");
            assertThat(failures.get("failures").get(0).get("level").asText()).isEqualTo("MUST");

            // pull the one redacted trace: the Authorization header must be stripped
            JsonNode trace = call(client, "get_trace", Map.of("runId", runId, "testId", "getContainer"));
            assertThat(trace.get("untrustedNote").asText()).contains("untrusted");
            assertThat(trace.get("outcome").asText()).isEqualTo("failed");
            JsonNode requestHeaders = trace.get("steps").get(0).get("exchange").get("requestHeaders");
            assertThat(requestHeaders.get("Authorization").get(0).asText()).isEqualTo("[REDACTED]");
            assertThat(trace.toString()).doesNotContain("Bearer ey");

            // diff against a second run — identical outcomes, no regressions
            String runId2 = structured(client.callTool(new CallToolRequest("start_run",
                    Map.of("targetId", "ref", "module", "core/containers"), Map.of()))).get("runId").asText();
            for (int i = 0; i < 400; i++) {
                if ("COMPLETE".equals(call(client, "get_run", Map.of("runId", runId2)).get("status").asText())) {
                    break;
                }
                Thread.sleep(50);
            }
            JsonNode diff = call(client, "diff_runs", Map.of("before", runId, "after", runId2));
            assertThat(diff.get("hasRegressions").asBoolean()).isFalse();
            assertThat(diff.get("unchanged").asInt()).isEqualTo(14);

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
