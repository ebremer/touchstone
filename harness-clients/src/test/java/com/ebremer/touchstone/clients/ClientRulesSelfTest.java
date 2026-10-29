package com.ebremer.touchstone.clients;

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
import com.ebremer.touchstone.fixtures.client.RefInbox;
import com.ebremer.touchstone.fixtures.client.RefLwsClient;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.ebremer.touchstone.fixtures.client.Twins;
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
 * is here too: the reference client authenticates all four ways, and each broken-credential twin
 * fails.
 */
class ClientRulesSelfTest {

    /** The rules each twin exists to break, which the driver's self-test checks too. */
    static final Map<Flaw, Set<String>> AIMED = Twins.AIMED;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
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
        // what the client just wrote and has a second page at once; and notifications to the
        // reference inbox on this machine, which only a test or a developer's laptop allows.
        ClientLabConfig config = new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, 100, 100,
                d.idleTimeout(), d.maxLifetime(), d.maxBodyBytes(), d.maxRecordedResponseBytes(), d.maxExchanges(),
                d.maxResources(), d.maxStorageBytes(), d.requestBurst(), d.requestsPerSecond(), d.tokenLifetime(),
                java.time.Duration.ZERO, d.maxDeliveries(), true);
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
        assertThat(results.at("/verdict/text").asText()).isEqualTo("no MUST failure in 41 MUST rules exercised, of 41 that apply");
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
     * The client authenticates all four ways: alice starts with the token the session handed out
     * and then signs credentials with her key; bob signs in at the session's OpenID Provider, where
     * the client is registered first, and then presents an assertion from the session's SAML
     * identity provider, which the session API hands out.
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
        try (RefInbox inbox = RefInbox.start(flaw)) {
            new RefLwsClient(URI.create(session.get("storage").asText()),
                    new RefLwsClient.Agent(session.at("/identities/alice/cidUrl").asText(), session.at("/tokens/alice").asText(),
                            aliceSecrets.get("privateKeyJwk").toString(), null),
                    new RefLwsClient.Agent(session.at("/identities/bob/cidUrl").asText(), null, null,
                            new RefLwsClient.Login(clientId, REDIRECT, "bob", bobSecrets.get("password").asText()),
                            () -> assertion(api, key, "bob", clientId)),
                    inbox, flaw, tasks).run();
            settle(api, key, inbox);
        }
        HttpResponse<String> results = HTTP.send(HttpRequest.newBuilder(URI.create(session.get("results").asText()))
                .header("Authorization", "Bearer " + session.get("key").asText()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(results.statusCode()).as(results.body()).isEqualTo(200);
        return JSON.readTree(results.body());
    }

    /**
     * Waits until the session has no notification in flight and the inbox has had none for a
     * moment, so that every delivery is judged before the results are read.
     */
    private static void settle(String api, String key, RefInbox inbox) throws Exception {
        long end = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        int seen = -1;
        while (System.nanoTime() < end) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api))
                    .header("Authorization", "Bearer " + key).build(), HttpResponse.BodyHandlers.ofString());
            int inFlight = JSON.readTree(r.body()).at("/deliveries/inFlight").asInt();
            int now = inbox.received();
            if (inFlight == 0 && now == seen) {
                return;
            }
            seen = now;
            Thread.sleep(300);
        }
    }

    /** A SAML assertion about an identity, for the client {@code clientId}, from the session API. */
    private static String assertion(String api, String key, String name, String clientId) throws IOException,
            InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/assertions/" + name))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"client_id\": \"" + clientId + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("assertion").asText();
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
