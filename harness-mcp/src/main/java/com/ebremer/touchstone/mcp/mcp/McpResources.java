package com.ebremer.touchstone.mcp.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.mcp.config.Catalog;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Service;

/** MCP resources (DESIGN.md paragraph 6): the EARL report and requirement clause text. */
@Service
public class McpResources {

    private final TouchstoneProperties props;
    private final Catalog catalog;

    public McpResources(TouchstoneProperties props, Catalog catalog) {
        this.props = props;
        this.catalog = catalog;
    }

    @McpResource(uri = "report://{runId}/earl", name = "earl-report",
            description = "The W3C EARL conformance report (Turtle) for a completed run.",
            mimeType = "text/turtle")
    public ReadResourceResult earlReport(String runId) {
        Path earl = props.runs().resolve(runId).resolve("earl.ttl");
        if (!Files.isRegularFile(earl)) {
            throw new IllegalArgumentException("no EARL report for run '" + runId + "' (has it completed?)");
        }
        try {
            String text = Files.readString(earl);
            return new ReadResourceResult(List.of(
                    new TextResourceContents("report://" + runId + "/earl", "text/turtle", text)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read EARL report for run " + runId, e);
        }
    }

    @McpResource(uri = "requirement://{module}/{slug}", name = "requirement",
            description = "The verbatim spec clause text for one catalog requirement.",
            mimeType = "text/plain")
    public ReadResourceResult requirement(String module, String slug) {
        Requirement r = catalog.all().stream()
                .filter(req -> req.specModule().equals(module) && req.iri().endsWith("/" + slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no requirement " + module + "/" + slug));
        String text = "[" + r.level() + "] " + r.summary() + "\n\n" + r.clauseText()
                + "\n\nsection: " + r.section();
        return new ReadResourceResult(List.of(
                new TextResourceContents("requirement://" + module + "/" + slug, "text/plain", text)));
    }
}
