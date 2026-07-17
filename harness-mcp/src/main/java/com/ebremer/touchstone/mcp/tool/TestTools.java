package com.ebremer.touchstone.mcp.tool;

import java.util.List;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.mcp.dto.Dtos.TestSummary;
import com.ebremer.touchstone.mcp.manifest.Manifests;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/** Read-only tool over the test manifests — metadata only (DESIGN.md paragraph 6). */
@Service
public class TestTools {

    private final Manifests manifests;

    public TestTools(Manifests manifests) {
        this.manifests = manifests;
    }

    @McpTool(name = "list_tests",
            description = "List test manifests as metadata, optionally filtered by the requirement IRI "
                    + "they verify, spec module, or tag.")
    public List<TestSummary> listTests(
            @McpToolParam(required = false, description = "requirement IRI the test must verify") String requirement,
            @McpToolParam(required = false, description = "spec module key") String module,
            @McpToolParam(required = false, description = "tag the test must carry") String tag) {
        return manifests.all().stream()
                .filter(m -> requirement == null || requirement.isBlank() || m.requirements().contains(requirement))
                .filter(m -> module == null || module.isBlank() || m.module().equals(module))
                .filter(m -> tag == null || tag.isBlank() || m.tags().contains(tag))
                .map(TestTools::summary)
                .toList();
    }

    private static TestSummary summary(Manifest m) {
        return new TestSummary(m.id(), m.title(), m.module(), m.requirements(), m.capabilities(), m.tags());
    }
}
