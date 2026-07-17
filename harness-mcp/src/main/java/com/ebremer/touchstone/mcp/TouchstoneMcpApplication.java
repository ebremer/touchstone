package com.ebremer.touchstone.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP front end over harness-core. The tool surface (DESIGN.md paragraph 6) arrives in
 * Phase 5; Phase 0 only proves the stack: Spring AI MCP server, WebMVC transport,
 * Jetty (never Tomcat), streamable HTTP.
 */
@SpringBootApplication
public class TouchstoneMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(TouchstoneMcpApplication.class, args);
    }
}
