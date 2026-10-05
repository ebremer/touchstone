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
 * trial; and each broken twin, in a session of its own, fails exactly the rules aimed at it and
 * nothing else. A rule no twin fails could never fail.
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
            entry(Flaw.QUERY_KEEPS_REFUSED_FORMAT, Set.of("client-query-baseline-after-415")));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final URI INBOX = URI.create("https://client.invalid/inbox/");
    private static ClientLab lab;
    private static String base;

    @BeforeAll
    static void start() {
        int port = freePort();
        base = "http://localhost:" + port + "/touchstone/clients";
        ClientLabConfig d = ClientLabConfig.defaults(URI.create(base), "127.0.0.1", port);
        // One session per client, all from this address.
        ClientLabConfig config = new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, 100, 100,
                d.idleTimeout(), d.maxLifetime(), d.maxBodyBytes(), d.maxRecordedResponseBytes(), d.maxExchanges(),
                d.maxResources(), d.maxStorageBytes(), d.requestBurst(), d.requestsPerSecond(), d.tokenLifetime(),
                d.indexLag());
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
        assertThat(results.at("/verdict/text").asText()).isEqualTo("no MUST failure in 18 MUST rules exercised, of 18 that apply");
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

    /** Runs a client, reference or twin, in a session of its own, and returns the session's results. */
    private static JsonNode run(Flaw flaw) throws Exception {
        HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/sessions"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(created.body());
        new RefLwsClient(URI.create(session.get("storage").asText()),
                new RefLwsClient.Agent(session.at("/tokens/alice").asText(), session.at("/identities/alice/webid").asText()),
                new RefLwsClient.Agent(session.at("/tokens/bob").asText(), session.at("/identities/bob/webid").asText()),
                INBOX, flaw).run();
        HttpResponse<String> results = HTTP.send(HttpRequest.newBuilder(URI.create(session.get("results").asText()))
                .header("Authorization", "Bearer " + session.get("key").asText()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(results.statusCode()).as(results.body()).isEqualTo(200);
        return JSON.readTree(results.body());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
