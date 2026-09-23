package com.ebremer.touchstone.core.definitions;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The lint of EXECUTION.md section 2.5: what the schema cannot check, checked before a run
 * starts. A definition that fails it could only produce a verdict about the harness, never
 * about the server.
 *
 * <p>{@code tools/definitions/lint_definitions.py} runs the same rules in CI, plus the
 * repository checks an engine has no business making (anchors, lws-test-suite mirrors).
 */
public final class DefinitionLint {

    /** Built-in and derived variables (EXECUTION.md section 3); {@code now±N} is matched separately. */
    public static final Set<String> BUILT_IN = Set.of(
            "target.baseUrl", "run.root", "test.container", "uuid", "now", "storage",
            "as.uri", "as.realm", "as.metadataUrl", "as.issuer", "as.tokenEndpoint", "as.jwksUri",
            "fixtures.baseUrl");

    public static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern NOW = Pattern.compile("now[+-]\\d+");
    private static final Pattern SERVICE = Pattern.compile("service\\.([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern IDENTITY_WEBID = Pattern.compile("identity\\.([a-z][a-z0-9-]*)\\.webid");
    private static final Pattern CREDENTIAL = Pattern.compile("credential\\.([a-z][a-z0-9-]*)");
    private static final Pattern HOST = Pattern.compile("(?i)https?://([^/:?#\"'\\s]+)");
    private static final Set<String> CONDITIONAL_METHODS = Set.of("PUT", "PATCH", "DELETE", "POST");

    private DefinitionLint() {
    }

    static List<String> check(List<TestDefinition> tests, Map<String, IdentityDefinition> identities,
                              Set<String> catalog) {
        List<String> errors = new ArrayList<>();
        checkIdentities(identities, errors);
        Map<String, Integer> names = new HashMap<>();
        for (TestDefinition t : tests) {
            names.merge(t.name(), 1, Integer::sum);
            checkTest(t, identities, catalog, errors);
        }
        names.forEach((name, count) -> {
            if (count > 1) {
                errors.add("test name " + name + " is used " + count + " times; names must be unique");
            }
        });
        return errors;
    }

    private static void checkIdentities(Map<String, IdentityDefinition> identities, List<String> errors) {
        for (IdentityDefinition i : identities.values()) {
            if (i.basis() == null) {
                continue;
            }
            IdentityDefinition basis = identities.get(i.basis());
            if (basis == null) {
                errors.add("identity " + i.name() + ": basis " + i.basis() + " is not an identity");
            } else if (!basis.kind().equals(i.kind())) {
                errors.add("identity " + i.name() + ": its kind differs from its basis " + i.basis());
            } else if (basis.basis() != null) {
                errors.add("identity " + i.name() + ": its basis " + i.basis() + " is itself a fault identity");
            }
        }
    }

    private static void checkTest(TestDefinition t, Map<String, IdentityDefinition> identities,
                                  Set<String> catalog, List<String> errors) {
        String where = t.id();
        if (!t.iri().endsWith("#" + t.name())) {
            errors.add(where + ": id is not #" + t.name());
        }
        Set<String> requires = new HashSet<>(t.requires());
        if (catalog != null) {
            for (String r : t.requirements()) {
                if (!catalog.contains(r)) {
                    errors.add(where + ": requirement " + r + " is not in the catalog");
                }
            }
        }
        if (t.prereqs() != null) {
            exampleHosts(where, "prereqs", t.prereqs(), errors);
        }
        for (StepDefinition s : t.steps()) {
            exampleHosts(where, "request", s.request(), errors);
            exampleHosts(where, "response", s.response(), errors);
        }

        Set<String> bound = new LinkedHashSet<>();
        if (t.prereqs() != null) {
            checkPrereqs(t, identities, requires, bound, errors);
        }
        int n = 0;
        for (StepDefinition s : t.steps()) {
            n++;
            String sw = where + " step " + n;
            String identity = s.identity() != null ? s.identity() : t.identity() != null ? t.identity() : "alice";
            IdentityDefinition id = identities.get(identity);
            if (id == null) {
                errors.add(sw + ": as " + identity + " is not a registered identity");
            } else if (id.kind().equals(IdentityDefinition.SUBJECT_CREDENTIAL)) {
                errors.add(sw + ": as " + identity + " is a SubjectCredential, which cannot act on the storage");
            }
            JsonNode req = s.request();
            JsonNode resp = s.response();
            for (JsonNode h : req.path("otherHeaders")) {
                if (h.path("headerName").asText().equalsIgnoreCase("Authorization") && !"anonymous".equals(identity)) {
                    errors.add(sw + ": sets Authorization itself, so it must run as anonymous (EXECUTION.md 6.2)");
                }
            }
            if (req.has("ifMatch") && !CONDITIONAL_METHODS.contains(req.path("method").asText())) {
                errors.add(sw + ": ifMatch on " + req.path("method").asText());
            }
            fixture(t, sw, "request bodyURL", req.path("bodyURL"), errors);
            fixture(t, sw, "response bodyURL", resp.path("bodyURL"), errors);

            Set<String> here = new LinkedHashSet<>();
            captures(resp, here);
            variables(sw, "request", req, bound, t, identities, requires, errors);
            JsonNode withoutJwt = resp.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) withoutJwt).remove("jwt");
            variables(sw, "response", withoutJwt, bound, t, identities, requires, errors);
            if (resp.has("jwt")) {
                Set<String> jwtScope = new LinkedHashSet<>(bound);
                jwtScope.addAll(here);
                variables(sw, "jwt", resp.get("jwt"), jwtScope, t, identities, requires, errors);
            }
            for (String c : here) {
                if (bound.contains(c) || BUILT_IN.contains(c)) {
                    errors.add(sw + ": capture " + c + " rebinds an existing variable");
                }
            }
            bound.addAll(here);
        }
    }

    private static void checkPrereqs(TestDefinition t, Map<String, IdentityDefinition> identities,
                                     Set<String> requires, Set<String> bound, List<String> errors) {
        Set<String> containers = new HashSet<>();
        int j = 0;
        for (JsonNode e : t.prereqs().path("hierarchy")) {
            j++;
            String pw = t.id() + " prereq " + j;
            boolean container = e.has("container");
            String variable = e.path(container ? "container" : "dataResource").asText();
            if (bound.contains(variable) || BUILT_IN.contains(variable)) {
                errors.add(pw + ": " + variable + " rebinds an existing variable");
            }
            if (e.has("in") && !containers.contains(e.path("in").asText())) {
                errors.add(pw + ": in " + e.path("in").asText() + " is not an earlier container entry");
            }
            fixture(t, pw, "bodyURL", e.path("bodyURL"), errors);
            for (String field : List.of("body", "bodyJSON")) {
                if (e.has(field)) {
                    variables(pw, field, e.get(field), bound, t, identities, requires, errors);
                }
            }
            JsonNode auth = e.path("authorization");
            if (!auth.isMissingNode() && !requires.contains("Authentication")) {
                errors.add(pw + ": grants access without requires Authentication");
            }
            auth.properties().forEach(action -> action.getValue().forEach(who -> {
                String name = who.asText();
                IdentityDefinition id = identities.get(name);
                if (id == null) {
                    errors.add(pw + ": " + action.getKey() + " granted to unknown identity " + name);
                } else if (name.equals("alice")) {
                    errors.add(pw + ": " + action.getKey() + " granted to alice, who creates it and needs no grant");
                } else if (id.isFault() || !(id.kind().equals(IdentityDefinition.NO_CREDENTIAL)
                        || id.kind().equals(IdentityDefinition.STORAGE_ACCESS_TOKEN))) {
                    errors.add(pw + ": " + action.getKey() + " granted to " + name + ", which a grant cannot name");
                }
            }));
            if (container && !e.path("absent").asBoolean(false)) {
                containers.add(variable);
            }
            bound.add(variable);
        }
    }

    /** Every {@code ${...}} is built in, derived, an identity or credential, or already bound. */
    private static void variables(String where, String part, JsonNode node, Set<String> scope, TestDefinition t,
                                  Map<String, IdentityDefinition> identities, Set<String> requires,
                                  List<String> errors) {
        for (String s : strings(node)) {
            Matcher m = VARIABLE.matcher(s);
            while (m.find()) {
                String v = m.group(1);
                if (BUILT_IN.contains(v) || NOW.matcher(v).matches() || scope.contains(v)
                        || SERVICE.matcher(v).matches()) {
                    continue;
                }
                Matcher webid = IDENTITY_WEBID.matcher(v);
                if (webid.matches()) {
                    if (!identities.containsKey(webid.group(1))) {
                        errors.add(where + ": ${" + v + "} names an unknown identity");
                    }
                    continue;
                }
                Matcher credential = CREDENTIAL.matcher(v);
                if (credential.matches()) {
                    IdentityDefinition id = identities.get(credential.group(1));
                    if (id == null || !id.kind().equals(IdentityDefinition.SUBJECT_CREDENTIAL)) {
                        errors.add(where + ": ${" + v + "} is not a SubjectCredential identity");
                        continue;
                    }
                    Set<String> needed = new HashSet<>(id.requires());
                    if (id.basis() != null && identities.containsKey(id.basis())) {
                        needed.addAll(identities.get(id.basis()).requires());
                    }
                    if (!requires.containsAll(needed)) {
                        errors.add(where + ": ${" + v + "} needs the test to require " + needed);
                    }
                    continue;
                }
                errors.add(where + ": ${" + v + "} in the " + part + " is not bound");
            }
        }
    }

    private static void fixture(TestDefinition t, String where, String what, JsonNode path, List<String> errors) {
        if (path.isTextual() && !Files.isRegularFile(t.directory().resolve(path.asText()).normalize())) {
            errors.add(where + ": " + what + " " + path.asText() + " does not exist");
        }
    }

    /** No executable value may name an RFC 2606 or RFC 6761 example host: it can never match a live server. */
    private static void exampleHosts(String where, String part, JsonNode node, List<String> errors) {
        for (String s : strings(node)) {
            Matcher m = HOST.matcher(s);
            while (m.find()) {
                if (isExampleHost(m.group(1))) {
                    errors.add(where + ": the " + part + " names the example host " + m.group(1)
                            + ", which no live server can match");
                }
            }
        }
    }

    static boolean isExampleHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.equals("example") || h.endsWith(".example")) {
            return true;
        }
        for (String d : List.of("example.com", "example.net", "example.org")) {
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /** The names a step's response captures, from wherever the schema allows a capture. */
    static void captures(JsonNode response, Set<String> out) {
        add(response.path("location").path("capture"), out);
        response.path("linkHeaders").forEach(l -> add(l.path("capture"), out));
        response.path("otherHeaders").forEach(h -> add(h.path("capture"), out));
        JsonNode challenge = response.path("authenticationChallenge");
        add(challenge.path("asUri").path("capture"), out);
        add(challenge.path("realm").path("capture"), out);
        challenge.path("params").forEach(p -> add(p.path("capture"), out));
        jsonCaptures(response.path("json"), out);
        jsonCaptures(response.path("jwt").path("header"), out);
        jsonCaptures(response.path("jwt").path("claims"), out);
    }

    private static void jsonCaptures(JsonNode expectations, Set<String> out) {
        for (JsonNode e : expectations) {
            add(e.path("capture"), out);
            for (String nested : List.of("some", "every", "none")) {
                jsonCaptures(e.path(nested), out);
            }
        }
    }

    private static void add(JsonNode capture, Set<String> out) {
        if (capture.isTextual()) {
            out.add(capture.asText());
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            out.add(node.asText());
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, out));
        }
    }
}
