package com.ebremer.touchstone.core.definitions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** The variables of a drive (OBSERVATION.md section 12), besides earlier captures. */
    static final Set<String> DRIVE_VARIABLES = Set.of("storage", "drive.container", "drive.inbox", "uuid");
    static final Pattern DRIVE_VARIABLE = Pattern.compile(
            "identity\\.(alice|bob)\\.cidUrl|credential\\.(alice|bob)\\.(privateJwk|kid|token|idToken|samlAssertion)"
                    + "|service\\.[A-Za-z]+");

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
            checkDrive(r, errors);
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

    /**
     * A drive (OBSERVATION.md section 12) names operations and arguments of the frozen driver
     * protocol, uses only the drive's variables and earlier captures, and starts a task only if the
     * rule has one, at most once.
     */
    private static void checkDrive(RuleDefinition r, List<String> errors) {
        Set<String> bound = new HashSet<>();
        int started = 0;
        int i = 0;
        for (DriveStep step : r.drive()) {
            String where = r.id() + " drive step " + ++i;
            if (step.startTask()) {
                if (r.task() == null) {
                    errors.add(where + ": startTask in a rule without a task");
                } else if (++started > 1) {
                    errors.add(where + ": the task is started twice");
                }
                continue;
            }
            if (!step.isOperation()) {
                continue;
            }
            Set<String> arguments = DriverProtocol.arguments(step.op());
            if (!DriverProtocol.operations().contains(step.op()) || step.op().equals("shutdown")) {
                errors.add(where + ": " + step.op() + " is not an operation a drive may name");
                continue;
            }
            step.args().fieldNames().forEachRemaining(a -> {
                if (!arguments.contains(a)) {
                    errors.add(where + ": " + step.op() + " has no argument " + a);
                }
            });
            for (String a : DriverProtocol.required(step.op())) {
                if (!step.args().has(a)) {
                    errors.add(where + ": " + step.op() + " needs the argument " + a);
                }
            }
            DefinitionLint.exampleHosts(where, "drive", step.args(), errors);
            for (String s : DefinitionLint.strings(step.args())) {
                Matcher m = DefinitionLint.VARIABLE.matcher(s);
                while (m.find()) {
                    String v = m.group(1);
                    if (!DRIVE_VARIABLES.contains(v) && !DRIVE_VARIABLE.matcher(v).matches() && !bound.contains(v)) {
                        errors.add(where + ": unbound variable ${" + v + "}");
                    }
                }
            }
            for (String c : step.capture().keySet()) {
                if (!bound.add(c) || DRIVE_VARIABLES.contains(c)) {
                    errors.add(where + ": capture " + c + " rebinds an existing variable");
                }
            }
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
