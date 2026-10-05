package com.ebremer.touchstone.core.definitions;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The loaded, validated and linted client rules (OBSERVATION.md section 2), in traversal order
 * from {@code lws10/clients/manifest.yamlld}. Immutable, so one instance serves every session.
 */
public record ClientRules(Path definitionsDir, List<RuleDefinition> rules) {

    public ClientRules {
        rules = List.copyOf(rules);
    }

    /** The rule with this name, or with this id. */
    public Optional<RuleDefinition> find(String nameOrId) {
        return rules.stream().filter(r -> r.name().equals(nameOrId) || r.id().equals(nameOrId)).findFirst();
    }
}
