package com.ebremer.touchstone.mcp.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ebremer.touchstone.mcp.manifest.Manifests;
import com.ebremer.touchstone.mcp.run.RunStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the harness-core services the MCP tools sit on top of. */
@Configuration
@EnableConfigurationProperties(TouchstoneProperties.class)
public class HarnessConfig {

    @Bean
    Catalog catalog(TouchstoneProperties props) {
        return new Catalog(props);
    }

    @Bean
    Targets targets(TouchstoneProperties props) {
        return new Targets(props);
    }

    @Bean
    Manifests manifests(TouchstoneProperties props) {
        return new Manifests(props);
    }

    /** Async runs execute here (DESIGN.md paragraph 6: virtual-thread executor). */
    @Bean(destroyMethod = "shutdown")
    ExecutorService runExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    RunStore runStore(TouchstoneProperties props, Catalog catalog, ExecutorService runExecutor) {
        return new RunStore(props, catalog.all(), runExecutor);
    }
}
