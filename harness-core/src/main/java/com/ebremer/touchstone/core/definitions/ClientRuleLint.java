package com.ebremer.touchstone.core.definitions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The lint of OBSERVATION.md section 2: what the schema cannot check about client rules, checked
 * before the client service starts. {@code tools/definitions/lint_definitions.py} runs the same
 * rules in CI.
 */
final class ClientRuleLint {

    private static final List<String> LEVELS = List.of("MAY", "SHOULD", "MUST");
    /** The roles a client session answers for (D-0076). */
    private static final Set<String> CLIENT_SIDE = Set.of("Client", "Receiver");

    private ClientRuleLint() {
    }

    /**
     * @param serverNames the names of the server tests, which client rules must not reuse
     * @param catalog     the catalog by requirement IRI, or null to skip the checks that need it
     */
    static List<String> check(List<RuleDefinition> rules, Set<String> serverNames, Map<String, Requirement> catalog) {
        List<String> errors = new ArrayList<>();
        Map<String, Integer> names = new HashMap<>();
        for (RuleDefinition r : rules) {
            names.merge(r.name(), 1, Integer::sum);
            String where = r.id();
            if (serverNames.contains(r.name())) {
                errors.add(where + ": the name " + r.name() + " is a server test's too; names are unique across both");
            }
            for (String part : List.of("observe", "expect")) {
                JsonNode node = part.equals("observe") ? r.observe() : r.expect();
                DefinitionLint.exampleHosts(where, part, node, errors);
                for (String s : DefinitionLint.strings(node)) {
                    if (s.contains("${")) {
                        errors.add(where + ": the " + part + " has " + s + ", but client rules have no variables");
                    }
                }
                if (hasCapture(node)) {
                    errors.add(where + ": the " + part + " captures a value, but nothing passes between trials");
                }
            }
            if (catalog != null) {
                checkRequirements(r, catalog, errors);
            }
        }
        names.forEach((name, count) -> {
            if (count > 1) {
                errors.add("rule name " + name + " is used " + count + " times; names must be unique");
            }
        });
        return errors;
    }

    private static void checkRequirements(RuleDefinition r, Map<String, Requirement> catalog, List<String> errors) {
        boolean clientSide = false;
        int strongest = -1;
        for (String iri : r.requirements()) {
            Requirement req = catalog.get(iri);
            if (req == null) {
                errors.add(r.id() + ": requirement " + iri + " is not in the catalog");
                continue;
            }
            clientSide |= req.appliesTo().stream().anyMatch(CLIENT_SIDE::contains);
            strongest = Math.max(strongest, LEVELS.indexOf(req.level()));
        }
        if (!clientSide) {
            errors.add(r.id() + ": cites no requirement that binds a Client or a Receiver");
        }
        if (strongest >= 0 && LEVELS.indexOf(r.level()) > strongest) {
            errors.add(r.id() + ": is " + r.level() + ", stronger than any requirement it cites ("
                    + LEVELS.get(strongest) + ")");
        }
    }

    private static boolean hasCapture(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equals("capture") || hasCapture(e.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasCapture(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
