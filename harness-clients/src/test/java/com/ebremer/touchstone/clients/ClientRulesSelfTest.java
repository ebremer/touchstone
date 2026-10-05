package com.ebremer.touchstone.clients;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.ebremer.touchstone.fixtures.client.RefLwsClient;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The client rules, tested the way the server tests are (CLIENT-TESTING.md section 9): the
 * reference client, in a session of its own, passes every rule, because its script gives each a
 * trial and starts each task; and each broken twin, in a session of its own, fails exactly the
 * rules aimed at it and nothing else. A rule no twin fails could never fail. Phase C4's acceptance
 * is here too: the reference client authenticates all three ways, and each broken-credential twin
 * fails.
 */
class ClientRulesSelfTest {

    /** The rules each twin exists to break. */
    static final Map<Flaw, Set<String>> AIMED = Map.ofEntries(
            entry(Flaw.TOKEN_ALSO_IN_OWN_HEADER, Set.of("client-token-in-authorization-header")),
            entry(Flaw.LINKSET_PUT_UNADVERTISED, Set.of("client-linkset-put-only-when-advertised")),
            entry(Flaw.LINKSET_PATCH_FORMAT_UNADVERTISED, Set.of("client-linkset-patch-format-advertised")),
            entry(Flaw.PUT_UNCONDITIONAL, Set.of("client-put-conditional")),
            entry(Flaw.LINKSET_WRITE_UNCONDITIONAL, Set.of("client-linkset-write-conditional")),
            entry(Flaw.BUILDS_PAGE_URL, Set.of("client-page-urls-issued")),
            entry(Flaw.BUILDS_MEMBER_URL, Set.of("client-member-urls-issued")),
            entry(Flaw.ACCESS_CONTEXT_NOT_ARRAY, Set.of("client-access-document-context")),
            entry(Flaw.ACCESS_REQUEST_WRONG_TYPE, Set.of("client-access-request-type")),
            entry(Flaw.ACCESS_GRANT_WITHOUT_TYPE, Set.of("client-access-grant-type")),
            entry(Flaw.ACCESS_WITHOUT_STORAGE, Set.of("client-access-document-storage")),
            entry(Flaw.ACCESS_NOT_ARRAY, Set.of("client-access-document-access")),
            entry(Flaw.POLICY_WITHOUT_TYPE, Set.of("client-access-policy-type")),
            entry(Flaw.POLICY_UNKNOWN_ACTION, Set.of("client-access-policy-action")),
            entry(Flaw.POLICY_ASSIGNEE_NOT_URI, Set.of("client-access-policy-assignee")),
            entry(Flaw.POLICY_TARGET_NOT_OBJECT, Set.of("client-access-policy-target")),
            entry(Flaw.CONSTRAINT_WITHOUT_OPERATOR, Set.of("client-access-policy-constraint")),
            entry(Flaw.ACCESS_INBOX_NOT_URI, Set.of("client-access-document-inbox")),
            entry(Flaw.DELETE_UNCONDITIONAL, Set.of("client-delete-conditional")),
            entry(Flaw.SUBSCRIPTION_AS_PLAIN_JSON, Set.of("client-subscription-media-type")),
            entry(Flaw.SUBSCRIPTION_UNADVERTISED_TYPE, Set.of("client-subscription-type")),
            entry(Flaw.SUBSCRIPTION_TOPIC_NOT_ARRAY, Set.of("client-subscription-topic")),
            entry(Flaw.SUBSCRIPTION_WITHOUT_INBOX, Set.of("client-subscription-inbox")),
            entry(Flaw.QUERY_WITHOUT_CONTENT_TYPE, Set.of("client-query-content-type")),
            entry(Flaw.QUERY_KEEPS_REFUSED_FORMAT, Set.of("client-query-baseline-after-415")),
            entry(Flaw.CREATES_CONTAINER_WITHOUT_TYPE_LINK, Set.of("client-create-container-type-link")),
            entry(Flaw.DELETES_CONTAINER_WITHOUT_DEPTH, Set.of("client-delete-container-depth")),
            // Resending a PUT the 405 told it is not supported also assumes PUT is supported.
            entry(Flaw.REPEATS_REFUSED_PUT, Set.of("client-no-repeat-after-405-415",
                    "client-linkset-put-only-when-advertised")),
            entry(Flaw.RETRIES_LOST_CREATE_BLINDLY, Set.of("client-no-blind-retry-of-create")),
            entry(Flaw.DOES_NOT_RESTART_SEARCH, Set.of("client-restart-after-refused-page")),
            entry(Flaw.CID_UNSIGNED, Set.of("client-cid-credential-signed")),
            entry(Flaw.CID_WITHOUT_SUB, Set.of("client-cid-subject-claim")),
            entry(Flaw.CID_WITHOUT_ISS, Set.of("client-cid-issuer-claim")),
            entry(Flaw.CID_WITHOUT_CLIENT_ID, Set.of("client-cid-client-id-claim")),
            entry(Flaw.CID_CLIENT_ID_DIFFERS, Set.of("client-cid-identifiers-agree")),
            entry(Flaw.CID_AUDIENCE_WITHOUT_AS, Set.of("client-cid-audience-includes-as")),
            entry(Flaw.CID_WITHOUT_AUDIENCE, Set.of("client-cid-audience-restricted")),
            entry(Flaw.CID_WITHOUT_EXP, Set.of("client-cid-expiry-claim")),
            entry(Flaw.CID_WITHOUT_IAT, Set.of("client-cid-issued-at-claim")),
            entry(Flaw.CID_TYPED_AS_ID_TOKEN, Set.of("client-cid-token-type-jwt")),
            entry(Flaw.ID_TOKEN_TYPED_AS_JWT, Set.of("client-oidc-token-type-id-token")),
            entry(Flaw.TOKEN_REQUEST_WITHOUT_RESOURCE, Set.of("client-token-exchange-resource")),
            entry(Flaw.TOKEN_REQUEST_WITHOUT_SUBJECT_TOKEN, Set.of("client-token-exchange-subject-token")),
            entry(Flaw.TOKEN_FOR_FOREIGN_REALM, Set.of("client-token-for-containing-realm")));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final URI INBOX = URI.create("https://client.invalid/inbox/");
    /** Where the OpenID Provider sends the reference client back; it never listens, it reads the Location. */
    private static final URI REDIRECT = URI.create("http://127.0.0.1/callback");
    private static ClientLab lab;
    private static String base;

    @BeforeAll
    static void start() {
        int port = freePort();
        base = "http://localhost:" + port + "/touchstone/clients";
        ClientLabConfig d = ClientLabConfig.defaults(URI.create(base), "127.0.0.1", port);
        // One session per client, all from this address; an index without lag, so a search sees
        // what the client just wrote and has a second page at once.
        ClientLabConfig config = new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, 100, 100,
                d.idleTimeout(), d.maxLifetime(), d.maxBodyBytes(), d.maxRecordedResponseBytes(), d.maxExchanges(),
                d.maxResources(), d.maxStorageBytes(), d.requestBurst(), d.requestsPerSecond(), d.tokenLifetime(),
                java.time.Duration.ZERO);
        lab = ClientLab.start(config, TestRules.RULES);
    }

    @AfterAll
    static void stop() {
        lab.close();
    }

    @Test
    void theReferenceClientPassesEveryRule() throws Exception {
        JsonNode results = run(Flaw.NONE);
        Set<String> notPassed = new TreeSet<>();
        for (JsonNode r : results.get("rules")) {
            if (!r.get("outcome").asText().equals("passed")) {
                notPassed.add(r.get("rule").asText() + ": " + r.get("outcome").asText());
            }
        }
        assertThat(notPassed).as(results.toPrettyString()).isEmpty();
        assertThat(results.get("rules")).hasSize(TestRules.RULES.rules().size());
        assertThat(results.at("/verdict/text").asText()).isEqualTo("no MUST failure in 34 MUST rules exercised, of 34 that apply");
    }

    @ParameterizedTest
    @EnumSource(value = Flaw.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void eachTwinFailsExactlyTheRulesAimedAtIt(Flaw flaw) throws Exception {
        JsonNode results = run(flaw);
        Set<String> failed = new TreeSet<>();
        for (JsonNode r : results.get("rules")) {
            if (r.get("outcome").asText().equals("failed")) {
                failed.add(r.get("rule").asText());
            }
        }
        assertThat(failed).as(flaw + "\n" + results.toPrettyString()).isEqualTo(AIMED.get(flaw));
    }

    @Test
    void everyRuleHasATwinAndEveryTwinARule() {
        Set<String> aimed = new HashSet<>();
        AIMED.values().forEach(aimed::addAll);
        Set<String> rules = new HashSet<>();
        TestRules.RULES.rules().stream().map(RuleDefinition::name).forEach(rules::add);
        assertThat(aimed).isEqualTo(rules);
        assertThat(AIMED.keySet()).isEqualTo(EnumSet.complementOf(EnumSet.of(Flaw.NONE)));
    }

    /**
     * Runs a client, reference or twin, in a session of its own, and returns the session's results.
     * The client authenticates all three ways: alice starts with the token the session handed out
     * and then signs credentials with her key; bob signs in at the session's OpenID Provider, where
     * the client is registered first.
     */
    private static JsonNode run(Flaw flaw) throws Exception {
        HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/sessions"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(created.body());
        String key = session.get("key").asText();
        String api = session.get("api").asText();
        HttpResponse<String> registered = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/clients"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"redirect_uris\": [\"" + REDIRECT + "\"]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        String clientId = JSON.readTree(registered.body()).get("client_id").asText();
        JsonNode aliceSecrets = secrets(api, key, "alice");
        JsonNode bobSecrets = secrets(api, key, "bob");
        RefLwsClient.Tasks tasks = rule -> {
            HttpResponse<String> started = HTTP.send(HttpRequest.newBuilder(URI.create(session.get("api").asText()
                            + "/tasks/" + rule)).header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(started.statusCode()).as(rule + ": " + started.body()).isEqualTo(204);
        };
        new RefLwsClient(URI.create(session.get("storage").asText()),
                new RefLwsClient.Agent(session.at("/identities/alice/webid").asText(), session.at("/tokens/alice").asText(),
                        aliceSecrets.get("privateKeyJwk").toString(), null),
                new RefLwsClient.Agent(session.at("/identities/bob/webid").asText(), null, null,
                        new RefLwsClient.Login(clientId, REDIRECT, "bob", bobSecrets.get("password").asText())),
                INBOX, flaw, tasks).run();
        HttpResponse<String> results = HTTP.send(HttpRequest.newBuilder(URI.create(session.get("results").asText()))
                .header("Authorization", "Bearer " + session.get("key").asText()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(results.statusCode()).as(results.body()).isEqualTo(200);
        return JSON.readTree(results.body());
    }

    /** An identity's password and key, from the session API. */
    private static JsonNode secrets(String api, String key, String name) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/credentials/" + name))
                .header("Authorization", "Bearer " + key).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
