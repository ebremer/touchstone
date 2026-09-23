package com.ebremer.touchstone.core.definitions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ebremer.touchstone.core.Touchstone;

/**
 * The loaded, validated and linted definitions: every test in traversal order (EXECUTION.md
 * section 2.4) and the identity registry. Immutable, so one instance serves every run.
 */
public final class Definitions {

    /** The format this engine implements (DECISIONS.md D-0053). */
    public static final String FORMAT_VERSION = "0.2.0";

    /** The schema {@code $id} of that format; definitions whose schema names another are refused. */
    public static final String SCHEMA_ID = "https://example.org/touchstone/schema/definitions/0-2-0";

    /**
     * Base of every definition IRI: a manifest's IRI is this plus its path under
     * {@code lws10/} without extension, so a test's IRI is the same whether it was read from
     * YAML-LD or from its JSON-LD export (definitions/README.md, "Export").
     */
    public static final String BASE = Touchstone.TEST_NS + "lws10/";

    private final Path root;
    private final List<TestDefinition> tests;
    private final Map<String, IdentityDefinition> identities;

    Definitions(Path root, List<TestDefinition> tests, Map<String, IdentityDefinition> identities) {
        this.root = root;
        this.tests = List.copyOf(tests);
        this.identities = Map.copyOf(identities);
    }

    /** The {@code definitions/} directory these were loaded from. */
    public Path root() {
        return root;
    }

    public List<TestDefinition> tests() {
        return tests;
    }

    public Map<String, IdentityDefinition> identities() {
        return identities;
    }

    public Optional<IdentityDefinition> identity(String name) {
        return Optional.ofNullable(identities.get(name));
    }

    /** A test by its id ({@code core/containers#getContainer}) or, since names are unique, by name. */
    public Optional<TestDefinition> find(String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        for (TestDefinition t : tests) {
            if (t.id().equals(idOrName) || t.name().equals(idOrName)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * The tests a selector names, in traversal order:
     * <ul>
     *   <li>{@code all}, or nothing: every test;</li>
     *   <li>a test id or name: that test;</li>
     *   <li>a module or manifest path ({@code core}, {@code auth/oidc},
     *       {@code core/containers}): every test in it.</li>
     * </ul>
     * An empty list means the selector names nothing.
     */
    public List<TestDefinition> select(String selector) {
        if (selector == null || selector.isBlank() || selector.equals("all")) {
            return tests;
        }
        Optional<TestDefinition> one = find(selector);
        if (one.isPresent()) {
            return List.of(one.get());
        }
        String prefix = selector.endsWith("/") ? selector.substring(0, selector.length() - 1) : selector;
        List<TestDefinition> out = new ArrayList<>();
        for (TestDefinition t : tests) {
            String path = t.manifestPath();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                out.add(t);
            }
        }
        return out;
    }

    /** The modules and manifest paths a selector may name, for error messages and tool listings. */
    public List<String> manifestPaths() {
        return tests.stream().map(TestDefinition::manifestPath).distinct().toList();
    }
}
