package com.ebremer.touchstone.mcp.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the driving tools (CLIENT-TESTING.md section 14.2, stdio profile only) find what they may
 * start and use: the adapter registry, which registers adapters and client-session services out of
 * band as targets.yaml registers targets (section 14.3), and how the driver's inbox is reached.
 *
 * @param registry         the adapter registry, {@code adapters.yaml}
 * @param logs             where each adapter's stderr is kept
 * @param operationTimeout how long one operation may take
 * @param inboxHost        the address the driver's inbox binds
 * @param inboxPort        its port, 0 for a free one
 * @param inboxPublicBase  the inbox as the session reaches it, ending in {@code /inbox/}, or null
 */
@ConfigurationProperties(prefix = "touchstone.driver")
public record DriverProperties(Path registry, Path logs, Duration operationTimeout, String inboxHost, Integer inboxPort,
        URI inboxPublicBase) {

    public DriverProperties {
        registry = registry != null ? registry : Path.of("adapters.yaml");
        logs = logs != null ? logs : Path.of("runs", "adapter-logs");
        operationTimeout = operationTimeout != null ? operationTimeout : Duration.ofSeconds(60);
        inboxHost = inboxHost != null ? inboxHost : "127.0.0.1";
        inboxPort = inboxPort != null ? inboxPort : 0;
    }
}
