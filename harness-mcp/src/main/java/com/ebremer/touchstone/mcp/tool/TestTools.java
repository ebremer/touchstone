package com.ebremer.touchstone.mcp.tool;

import java.util.List;

import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.mcp.definitions.TestDefinitions;
import com.ebremer.touchstone.mcp.dto.Dtos.TestSummary;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/** Read-only tool over the YAML-LD test definitions: metadata only (DESIGN.md section 6). */
@Service
public class TestTools {

    private final TestDefinitions definitions;

    public TestTools(TestDefinitions definitions) {
        this.definitions = definitions;
    }

    @McpTool(name = "list_tests",
            description = "List the test definitions as metadata, optionally filtered by the requirement IRI "
                    + "they verify, a selector (a module such as core or auth, a manifest such as "
                    + "core/containers, or a test id), their level (MUST, SHOULD, MAY), or a trait.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public List<TestSummary> listTests(
            @McpToolParam(required = false, description = "requirement IRI the test must verify") String requirement,
            @McpToolParam(required = false, description = "module, manifest path or test id") String module,
            @McpToolParam(required = false, description = "MUST, SHOULD or MAY") String level,
            @McpToolParam(required = false, description = "trait the test must carry, such as Container") String trait) {
        return definitions.select(module).stream()
                .filter(t -> requirement == null || requirement.isBlank() || t.requirements().contains(requirement))
                .filter(t -> level == null || level.isBlank() || t.level().equalsIgnoreCase(level))
                .filter(t -> trait == null || trait.isBlank() || t.traits().contains(trait))
                .map(TestTools::summary)
                .toList();
    }

    private static TestSummary summary(TestDefinition t) {
        return new TestSummary(t.id(), t.label(), t.level(), t.type(), t.manifestPath(), t.requirements(),
                t.requires(), t.traits());
    }
}
