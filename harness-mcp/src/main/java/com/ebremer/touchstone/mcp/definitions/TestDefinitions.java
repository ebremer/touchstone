package com.ebremer.touchstone.mcp.definitions;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.mcp.config.Catalog;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;

/**
 * The YAML-LD test definitions, loaded once at startup: they are repository content, not
 * input. Loading validates and lints them against the loaded catalog (definitions/EXECUTION.md
 * section 2), so a server that starts can run every test it lists, and a set of definitions
 * that cannot be run stops the server rather than failing one tool call at a time.
 */
public final class TestDefinitions {

    private final Definitions definitions;

    public TestDefinitions(TouchstoneProperties props, Catalog catalog) {
        Set<String> iris = catalog.all().isEmpty() ? null
                : catalog.all().stream().map(Requirement::iri).collect(Collectors.toSet());
        this.definitions = Files.isDirectory(props.definitions().resolve("lws10"))
                ? DefinitionLoader.load(props.definitions(), iris)
                : null;
    }

    /** The definitions, for the engine; empty tools list nothing when none are configured. */
    public Definitions definitions() {
        if (definitions == null) {
            throw new IllegalStateException("no definitions are configured (touchstone.definitions)");
        }
        return definitions;
    }

    public List<TestDefinition> all() {
        return definitions == null ? List.of() : definitions.tests();
    }

    /** The tests a selector names: all, a module, a manifest path, a test id or a test name. */
    public List<TestDefinition> select(String selector) {
        return definitions == null ? List.of() : definitions.select(selector);
    }

    public Optional<TestDefinition> find(String idOrName) {
        return definitions == null ? Optional.empty() : definitions.find(idOrName);
    }
}
