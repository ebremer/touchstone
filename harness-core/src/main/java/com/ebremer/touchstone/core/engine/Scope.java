package com.ebremer.touchstone.core.engine;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The variables of one test (EXECUTION.md section 3): built-ins, derived variables (resolved
 * and cached by the {@link RunSession}), identities and credentials, and whatever a
 * prerequisite or a capture bound. A scope without a test resolves the run-level subset,
 * which is what provisioning and the fixture host need.
 *
 * <p>Subject credentials and access tokens are minted once per test and reused within it, so
 * two references to {@code ${credential.didkey}} in one test are the same credential.
 */
final class Scope implements Templates.Resolver {

    private static final Pattern NOW = Pattern.compile("now([+-])(\\d+)");
    private static final Pattern SERVICE = Pattern.compile("service\\.([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern WEBID = Pattern.compile("identity\\.([a-z][a-z0-9-]*)\\.webid");
    private static final Pattern CREDENTIAL = Pattern.compile("credential\\.([a-z][a-z0-9-]*)");

    private final RunSession run;
    private final TestDefinition test;
    private final Map<String, String> bound = new LinkedHashMap<>();
    private final Map<String, String> credentials = new HashMap<>();
    private final Map<String, Optional<String>> tokens = new HashMap<>();

    Scope(RunSession run, TestDefinition test) {
        this.run = run;
        this.test = test;
    }

    RunSession run() {
        return run;
    }

    TestDefinition test() {
        return test;
    }

    /** Binds a prerequisite or capture. The lint guarantees a name is never bound twice. */
    void bind(String name, String value) {
        bound.put(name, value);
    }

    String bound(String name) {
        return bound.get(name);
    }

    @Override
    public JsonNode resolve(String expression) {
        switch (expression) {
            case "uuid":
                return TextNode.valueOf(UUID.randomUUID().toString());
            case "now":
                return LongNode.valueOf(Instant.now().getEpochSecond());
            case "target.baseUrl":
                return TextNode.valueOf(run.target().baseUrl().toString());
            case "run.root":
                return TextNode.valueOf(run.runRoot().toString());
            default:
                break;
        }
        Matcher now = NOW.matcher(expression);
        if (now.matches()) {
            long offset = Long.parseLong(now.group(2));
            long base = Instant.now().getEpochSecond();
            return LongNode.valueOf(now.group(1).equals("+") ? base + offset : base - offset);
        }
        String value = bound.get(expression);
        if (value != null) {
            return TextNode.valueOf(value);
        }
        switch (expression) {
            case "storage":
                return TextNode.valueOf(run.storage(this));
            case "as.uri":
                return TextNode.valueOf(run.challenge(this).asUri());
            case "as.realm":
                return TextNode.valueOf(run.challenge(this).realm());
            case "as.metadataUrl":
                return TextNode.valueOf(run.metadataUrl(this));
            case "as.issuer":
                return TextNode.valueOf(run.metadataMember(this, "issuer"));
            case "as.tokenEndpoint":
                return TextNode.valueOf(run.metadataMember(this, "token_endpoint"));
            case "as.jwksUri":
                return TextNode.valueOf(run.metadataMember(this, "jwks_uri"));
            case "fixtures.baseUrl":
                return TextNode.valueOf(run.fixturesBaseUrl());
            default:
                break;
        }
        Matcher service = SERVICE.matcher(expression);
        if (service.matches()) {
            return TextNode.valueOf(run.service(this, service.group(1)));
        }
        Matcher webid = WEBID.matcher(expression);
        if (webid.matches()) {
            return TextNode.valueOf(run.credentials().webid(webid.group(1), this));
        }
        Matcher credential = CREDENTIAL.matcher(expression);
        if (credential.matches()) {
            return TextNode.valueOf(credential(credential.group(1)));
        }
        if (expression.equals("test.container")) {
            throw Unresolvable.cantTell("${test.container} is not bound yet");
        }
        throw Unresolvable.cantTell("${" + expression + "} was never bound: the prerequisite or capture"
                + " that binds it did not happen");
    }

    /** The subject credential for a SubjectCredential identity, minted on first use in this test. */
    String credential(String identity) {
        String c = credentials.get(identity);
        if (c == null) {
            c = run.credentials().subjectCredential(identity, this);
            credentials.put(identity, c);
        }
        return c;
    }

    /**
     * The Authorization header a request made as {@code identity} carries: empty for anonymous
     * and on a target that does not enforce authentication (EXECUTION.md section 5.2).
     */
    Map<String, String> authorization(String identity) {
        Optional<String> token = tokens.get(identity);
        if (token == null) {
            token = run.credentials().accessToken(identity, this);
            // A run outlives a minted token (exp is five minutes), so only a test's scope keeps one.
            if (test != null) {
                tokens.put(identity, token);
            }
        }
        return token.map(t -> Map.of("Authorization", "Bearer " + t)).orElse(Map.of());
    }

    /** A resolver that sees {@code self.*} first: the identity being minted (EXECUTION.md section 3). */
    Templates.Resolver withSelf(Map<String, JsonNode> self) {
        return expression -> {
            JsonNode v = self.get(expression);
            return v != null ? v : resolve(expression);
        };
    }
}
