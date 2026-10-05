package com.ebremer.touchstone.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.ebremer.touchstone.fixtures.as.RefAuthorizationServer;
import com.ebremer.touchstone.fixtures.client.RefInbox;
import com.ebremer.touchstone.fixtures.client.RefLwsClient;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.ebremer.touchstone.fixtures.lws.Traps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proxy mode (CLIENT-TESTING.md section 10), proved as the session's own storage is: the reference
 * client, talking through the proxy to a real server behind it, passes every rule a proxy session
 * can judge, and each twin whose rules a proxy session can judge fails exactly those. The server
 * is the reference server deployed as a front-door target: it knows the proxy's URLs as its own,
 * sets no trap, and has its own authorization server, which trusts the session's identities.
 */
class ProxyRulesSelfTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final URI REDIRECT = URI.create("http://127.0.0.1/callback");
    private static final Set<String> UNAVAILABLE = ProxySession.unavailable(TestRules.RULES);
    private static ClientLab lab;
    private static String base;
    private static int backendPort;
    /** What the server behind the proxy found each request it was sent to address: "METHOD path?query role". */
    private static final java.util.Queue<String> SERVER_ROLES = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @BeforeAll
    static void start() throws IOException {
        int port = freePort();
        backendPort = freePort();
        base = "http://localhost:" + port + "/touchstone/clients";
        Path registry = Files.createTempFile("proxy-targets", ".yaml");
        Files.writeString(registry, """
                targets:
                  ref:
                    baseUrl: %s/p/ref/storage/
                    adapter: env
                    properties:
                      proxy.backend: http://127.0.0.1:%d/touchstone/clients/p/ref/
                      proxy.issuer: %s/p/ref/as
                """.formatted(base, backendPort, base));
        ClientLabConfig d = ClientLabConfig.defaults(URI.create(base), "127.0.0.1", port);
        ClientLabConfig config = new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, 100, 100,
                d.idleTimeout(), d.maxLifetime(), d.maxBodyBytes(), d.maxRecordedResponseBytes(), d.maxExchanges(),
                d.maxResources(), d.maxStorageBytes(), d.requestBurst(), d.requestsPerSecond(), d.tokenLifetime(),
                java.time.Duration.ZERO, d.maxDeliveries(), true);
        lab = ClientLab.start(config, TestRules.RULES, Clock.systemUTC(), ProxyTargets.load(registry, config.publicBase()));
        Files.delete(registry);
    }

    @AfterAll
    static void stop() {
        lab.close();
    }

    /**
     * Twins whose mistake only a trap brings out (CLIENT-TESTING.md section 6.1), so a server
     * without traps cannot show it: one builds the "?page=2" URL that a server without opaque page
     * URLs really does hand out, and one asks for a token for the decoy's realm, and there is no
     * decoy. Their rules still apply; these twins just cannot break them here.
     */
    static final Set<Flaw> NEED_A_TRAP = Set.of(Flaw.BUILDS_PAGE_URL, Flaw.TOKEN_FOR_FOREIGN_REALM);

    /** The twins whose rules a proxy session can judge, and whose mistakes show without a trap. */
    static Stream<Flaw> applicableTwins() {
        return ClientRulesSelfTest.AIMED.entrySet().stream()
                .filter(e -> e.getValue().stream().noneMatch(UNAVAILABLE::contains))
                .filter(e -> !NEED_A_TRAP.contains(e.getKey()))
                .map(java.util.Map.Entry::getKey).sorted();
    }

    @Test
    void aProxySessionCannotJudgeWhatOnlyItsOwnServersKnow() {
        assertThat(UNAVAILABLE).contains("client-cid-subject-claim", "client-oidc-token-type-id-token",
                "client-inbox-refuses-altered-body", "client-subscription-own-inbox", "client-delete-container-depth");
        assertThat(UNAVAILABLE).doesNotContain("client-token-exchange-resource", "client-token-for-containing-realm",
                "client-no-repeat-after-405-415", "client-page-urls-issued", "client-subscription-inbox");
        assertThat(UNAVAILABLE).hasSize(18);
    }

    @Test
    void theReferenceClientPassesEveryRuleAProxySessionCanJudge() throws Exception {
        JsonNode results = run(Flaw.NONE);
        Set<String> notPassed = new TreeSet<>();
        Set<String> inapplicable = new TreeSet<>();
        for (JsonNode r : results.get("rules")) {
            String outcome = r.get("outcome").asText();
            if (outcome.equals("inapplicable")) {
                inapplicable.add(r.get("rule").asText());
                assertThat(r.get("inapplicableBecause").asText()).isEqualTo("proxy");
            } else if (!outcome.equals("passed")) {
                notPassed.add(r.get("rule").asText() + ": " + outcome);
            }
        }
        assertThat(notPassed).as(results.toPrettyString()).isEmpty();
        assertThat(inapplicable).isEqualTo(new TreeSet<>(UNAVAILABLE));
        // The roles the proxy inferred are the ones the server itself gave each request it was sent.
        java.util.List<String> inferred = new java.util.ArrayList<>();
        for (JsonNode x : lastLog) {
            // Every exchange the server was sent: all but those the proxy answered itself with a fault.
            // A lost create is forwarded, and only its answer lost.
            String fault = x.at("/annotations/fault").asText(null);
            if (x.at("/annotations/server").asText().equals("storage") && (fault == null || fault.equals("lostCreateResponse"))) {
                URI u = URI.create(x.get("url").asText());
                inferred.add(x.get("method").asText() + " " + u.getRawPath()
                        + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()) + " " + x.at("/annotations/role").asText());
            }
        }
        java.util.List<String> unmatched = new java.util.ArrayList<>();
        for (String said : lastServerRoles) {
            if (!inferred.remove(said)) {
                unmatched.add(said);
            }
        }
        assertThat(lastServerRoles).hasSizeGreaterThan(30);
        assertThat(unmatched).as("the server's roles the proxy did not infer; left over: " + inferred).isEmpty();
        assertThat(results.at("/verdict/text").asText()).isEqualTo("no MUST failure in 23 MUST rules exercised, of 23 that apply");
    }

    @ParameterizedTest
    @MethodSource("applicableTwins")
    void eachTwinFailsExactlyTheRulesAimedAtIt(Flaw flaw) throws Exception {
        JsonNode results = run(flaw);
        Set<String> failed = new TreeSet<>();
        for (JsonNode r : results.get("rules")) {
            if (r.get("outcome").asText().equals("failed")) {
                failed.add(r.get("rule").asText());
            }
        }
        assertThat(failed).as(flaw + "\n" + results.toPrettyString()).isEqualTo(ClientRulesSelfTest.AIMED.get(flaw));
    }

    @Test
    void aTargetIsForwardedOnlyForTheOneSessionThatHoldsIt() throws Exception {
        // Nobody holds it: nothing is forwarded.
        HttpResponse<String> unheld = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/p/ref/storage/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unheld.statusCode()).isEqualTo(503);
        assertThat(start("{\"proxy\": \"nowhere\"}").statusCode()).isEqualTo(400);
        HttpResponse<String> first = start("{\"proxy\": \"ref\"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(first.body());
        try {
            assertThat(session.get("storage").asText()).isEqualTo(base + "/p/ref/storage/");
            assertThat(session.has("tokens")).isFalse();
            assertThat(session.at("/proxy/target").asText()).isEqualTo("ref");
            assertThat(start("{\"proxy\": \"ref\"}").statusCode()).isEqualTo(409);
            String api = session.get("api").asText();
            String key = session.get("key").asText();
            // Its own storage is not served; the proxy target is chosen once; a forgery cannot be armed.
            assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/s/" + session.get("id").asText()
                    + "/storage/")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(api)).header("Authorization", "Bearer " + key)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"proxy\": \"ref\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(post(api + "/faults/forgedAlteredBody", key).statusCode()).isEqualTo(404);
            assertThat(post(api + "/faults/pageGone", key).statusCode()).isEqualTo(204);
            // The storage's root is its description or its root container, by what is asked for, with
            // or without an earlier answer for the other.
            Server backend = backend("https://example.org/owner");
            try {
                for (String accept : new String[] {"application/lws+cid", "application/lws+json", "application/lws+cid"}) {
                    HTTP.send(HttpRequest.newBuilder(URI.create(session.get("storage").asText())).header("Accept", accept)
                            .build(), HttpResponse.BodyHandlers.discarding());
                }
            } finally {
                backend.stop();
            }
            JsonNode roles = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "/exchanges"))
                    .header("Authorization", "Bearer " + key).build(), HttpResponse.BodyHandlers.ofString()).body());
            java.util.List<String> seen = new java.util.ArrayList<>();
            roles.get("exchanges").forEach(x -> seen.add(x.get("status").asInt() + " " + x.at("/annotations/role").asText()));
            assertThat(seen).containsExactly("200 storageDescription", "401 container", "200 storageDescription");
            // The server behind the proxy is down: the client gets a 502, recorded.
            HttpResponse<String> down = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/p/ref/storage/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(down.statusCode()).isEqualTo(502);
            JsonNode log = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "/exchanges"))
                    .header("Authorization", "Bearer " + key).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertThat(log.at("/exchanges/3/status").asInt()).isEqualTo(502);
            assertThat(log.at("/exchanges/3/annotations/server").asText()).isEqualTo("storage");
        } finally {
            end(session);
        }
        HttpResponse<String> again = start("{\"proxy\": \"ref\"}");
        assertThat(again.statusCode()).as("free once the holder ends").isEqualTo(201);
        end(JSON.readTree(again.body()));
    }

    /**
     * Runs a client through the proxy, in a proxy session of its own, against a fresh reference
     * server owned by the session's alice, and returns the session's results. alice authenticates
     * with credentials she signs, bob with an OpenID sign-in at the session's provider: the server's
     * authorization server trusts both, as the identity documents name them.
     */
    private static java.util.List<JsonNode> lastLog = java.util.List.of();
    private static java.util.List<String> lastServerRoles = java.util.List.of();

    private static JsonNode run(Flaw flaw) throws Exception {
        SERVER_ROLES.clear();
        HttpResponse<String> created = start("{\"proxy\": \"ref\"}");
        if (created.statusCode() == 409) {
            // The previous test's session still holds the target.
            throw new AssertionError(created.body());
        }
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode session = JSON.readTree(created.body());
        String key = session.get("key").asText();
        String api = session.get("api").asText();
        Server backend = backend(session.at("/identities/alice/webid").asText());
        try {
            HttpResponse<String> registered = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/clients"))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"redirect_uris\": [\"" + REDIRECT + "\"]}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
            String clientId = JSON.readTree(registered.body()).get("client_id").asText();
            JsonNode alice = secrets(api, key, "alice");
            JsonNode bob = secrets(api, key, "bob");
            // A task of a rule the proxy session cannot judge is refused, and the client goes on.
            RefLwsClient.Tasks tasks = rule -> assertThat(post(api + "/tasks/" + rule, key).statusCode()).as(rule)
                    .isEqualTo(UNAVAILABLE.contains(rule) ? 409 : 204);
            try (RefInbox inbox = RefInbox.start(flaw)) {
                new RefLwsClient(URI.create(session.get("storage").asText()),
                        new RefLwsClient.Agent(alice.get("webid").asText(), null, alice.get("privateKeyJwk").toString(), null),
                        new RefLwsClient.Agent(bob.get("webid").asText(), null, null,
                                new RefLwsClient.Login(clientId, REDIRECT, "bob", bob.get("password").asText())),
                        inbox, flaw, tasks).run();
            }
            HttpResponse<String> results = HTTP.send(HttpRequest.newBuilder(URI.create(session.get("results").asText()))
                    .header("Authorization", "Bearer " + key).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(results.statusCode()).as(results.body()).isEqualTo(200);
            java.util.List<JsonNode> log = new java.util.ArrayList<>();
            JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "/exchanges?limit=500"))
                    .header("Authorization", "Bearer " + key).build(), HttpResponse.BodyHandlers.ofString()).body())
                    .get("exchanges").forEach(log::add);
            lastLog = log;
            lastServerRoles = new java.util.ArrayList<>(SERVER_ROLES);
            return JSON.readTree(results.body());
        } finally {
            end(session);
            backend.stop();
        }
    }

    /**
     * The reference server as a front-door target: its storage and authorization server at the
     * proxy's URLs, served on the backend's port at the same paths, with no trap set.
     */
    private static Server backend(String owner) throws Exception {
        RefAuthorizationServer as = RefAuthorizationServer.mounted(URI.create(base + "/p/ref/as"));
        RefLwsServer storage = RefLwsServer.mounted(URI.create(base + "/p/ref/storage/"), as, owner, Traps.NONE);
        storage.linksetPutOnDataResources(true);
        // A real server delivers its notifications itself, not through the proxy.
        storage.deliverOnlyTo(uri -> true);
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(backendPort);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String path = request.getHttpURI().getPath();
                if (path.startsWith("/touchstone/clients/p/ref/as") || path.startsWith("/.well-known/")) {
                    return as.handler().handle(request, response, callback);
                }
                boolean handled = storage.handler().handle(request, response, callback);
                // The server sets the role as it dispatches, before it answers.
                String query = request.getHttpURI().getQuery();
                SERVER_ROLES.add(request.getMethod() + " " + path + (query == null ? "" : "?" + query) + " "
                        + request.getAttribute(RefLwsServer.ROLE_ATTRIBUTE));
                return handled;
            }
        });
        server.start();
        return server;
    }

    private static HttpResponse<String> start(String settings) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/sessions")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(settings)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String key) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void end(JsonNode session) throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(session.get("api").asText()))
                .header("Authorization", "Bearer " + session.get("key").asText()).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

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
