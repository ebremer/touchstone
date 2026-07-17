package com.ebremer.touchstone.mcp.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Harness locations for the MCP server. The target registry is pre-registered
 * out-of-band (DESIGN.md paragraph 7.1): MCP tools accept only target ids, and the set
 * of targets comes from {@link #targets()} — never from tool input. This is the
 * SSRF/abuse boundary.
 */
@ConfigurationProperties(prefix = "touchstone")
public record TouchstoneProperties(
        Path catalog,
        Path manifests,
        Path targets,
        Path runs) {

    public TouchstoneProperties {
        catalog = catalog != null ? catalog : Path.of("catalog");
        manifests = manifests != null ? manifests : Path.of("manifests");
        targets = targets != null ? targets : Path.of("targets.yaml");
        runs = runs != null ? runs : Path.of("runs");
    }
}
