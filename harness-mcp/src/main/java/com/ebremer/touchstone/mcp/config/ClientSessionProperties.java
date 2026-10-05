package com.ebremer.touchstone.mcp.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The client-session services the read-only client tools may read (CLIENT-TESTING.md phase C7),
 * registered out of band as targets are (DESIGN.md paragraph 7.1): a session reference an agent
 * passes must belong to one of {@link #services()}, so the tools never fetch a URL of the agent's
 * choosing.
 *
 * @param services each service's public base, such as {@code https://example.org/touchstone/clients}
 * @param session the session the tools read when a call names none: its page address with the key,
 *     {@code …/sessions/{id}/page#key=…}, so the key need not pass through the agent at all
 */
@ConfigurationProperties(prefix = "touchstone.clients")
public record ClientSessionProperties(List<String> services, String session) {

    public ClientSessionProperties {
        services = services == null ? List.of()
                : services.stream().map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s).toList();
    }
}
