package com.ebremer.touchstone.clients;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client-session service end to end over HTTP (CLIENT-TESTING.md phase C1): a session's
 * storage answers a client with a session token, its traffic log shows the exchanges redacted
 * and annotated, its traps show, and its bounds and key hold.
 */
class ClientLabTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static ClientLab lab;
    private static String base;

    @BeforeAll
    static void start() {
        int port = freePort();
        base = "http://localhost:" + port + "/touchstone/clients";
        lab = ClientLab.start(ClientLabConfig.defaults(URI.create(base), "127.0.0.1", port), TestRules.RULES);
    }

    @AfterAll
    static void stop() {
        lab.close();
    }

    @Test
    void aSessionStorageServesAClientWithASessionTokenAndTheLogShowsIt() throws Exception {
        JsonNode session = startSession(base);
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();
        assertThat(storage).isEqualTo(base + "/s/" + session.get("id").asText() + "/storage/");

        // Anonymous: the challenge names the session's authorization server and the storage as realm.
        HttpResponse<String> anonymous = send("GET", storage, null, null, "application/lws+json");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        String challenge = anonymous.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertThat(challenge).contains("as_uri=\"" + session.at("/authorizationServer/issuer").asText() + "\"")
                .contains("realm=\"" + storage + "\"");
        HttpResponse<String> metadata = send("GET", session.at("/authorizationServer/metadata").asText(), null, null, null);
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(metadata.body()).get("issuer").asText())
                .isEqualTo(session.at("/authorizationServer/issuer").asText());

        HttpResponse<String> created = send("POST", storage, alice, "hello", "text/plain");
        assertThat(created.statusCode()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(storage + "_r/");

        HttpResponse<String> listing = send("GET", storage, alice, null, "application/lws+json");
        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(listing.body()).contains(location).contains(storage + "_t/decoy");

        assertThat(send("DELETE", location, alice, null, null).statusCode()).isEqualTo(204);

        JsonNode log = exchanges(session, 0);
        List<JsonNode> all = list(log.get("exchanges"));
        assertThat(all).hasSizeGreaterThanOrEqualTo(5);
        assertThat(log.toString()).doesNotContain(alice);

        JsonNode post = find(all, "POST", storage);
        assertThat(post.at("/annotations/role").asText()).isEqualTo("container");
        assertThat(post.at("/annotations/identity").asText()).isEqualTo("alice");
        assertThat(post.at("/annotations/server").asText()).isEqualTo("storage");
        assertThat(post.at("/annotations/presentation").toString()).isEqualTo("[\"bearer\"]");
        assertThat(post.at("/annotations/token").asText()).hasSize(12);
        assertThat(post.at("/annotations/issued").asBoolean()).isTrue();
        assertThat(post.at("/annotations/issuedVia").asText()).isEqualTo("session");
        assertThat(post.at("/requestHeaders/Authorization/0").asText()).startsWith("Bearer [redacted ");
        assertThat(post.at("/requestBody/text").asText()).isEqualTo("hello");

        JsonNode delete = find(all, "DELETE", location);
        assertThat(delete.at("/annotations/role").asText()).isEqualTo("dataResource");
        assertThat(delete.at("/annotations/issuedVia").asText()).isEqualTo("location");
        assertThat(delete.get("status").asInt()).isEqualTo(204);

        JsonNode first = find(all, "GET", storage);
        assertThat(first.at("/annotations/identity").isNull()).isTrue();
        assertThat(first.at("/annotations/presentation").toString()).isEqualTo("[\"none\"]");
        JsonNode md = find(all, "GET", session.at("/authorizationServer/metadata").asText());
        assertThat(md.at("/annotations/role").asText()).isEqualTo("asMetadata");
        assertThat(md.at("/annotations/server").asText()).isEqualTo("authorizationServer");
        assertThat(md.at("/annotations/issuedVia").asText()).isEqualTo("challenge:as_uri");
    }

    @Test
    void aUrlTheClientBuiltIsMarkedAndTheDecoyNamesAForeignRealm() throws Exception {
        JsonNode session = startSession(base);
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();

        assertThat(send("GET", storage + "?page=2", alice, null, "application/lws+json").statusCode()).isEqualTo(404);
        // Before the client has seen the listing, the decoy's URL is one it built.
        HttpResponse<String> decoy = send("GET", storage + "_t/decoy", alice, null, null);
        assertThat(decoy.statusCode()).isEqualTo(401);
        assertThat(decoy.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("realm=\"" + storage + "_t/vault/\"").contains("error=\"invalid_token\"");
        // Six members: the decoy is listed first, so it is on the first page.
        for (int i = 0; i < 5; i++) {
            send("POST", storage, alice, "note " + i, "text/plain");
        }
        HttpResponse<String> listing = send("GET", storage, alice, null, "application/lws+json");
        assertThat(JSON.readTree(listing.body()).at("/items/0/id").asText()).isEqualTo(storage + "_t/decoy");
        send("GET", storage + "_t/decoy", alice, null, null);

        List<JsonNode> all = list(exchanges(session, 0).get("exchanges"));
        JsonNode built = find(all, "GET", storage + "?page=2");
        assertThat(built.at("/annotations/issued").asBoolean()).isFalse();
        assertThat(built.at("/annotations/issuedVia").isNull()).isTrue();
        assertThat(built.at("/annotations/role").asText()).isEqualTo("unknown");
        assertThat(built.at("/annotations/builtBy").asText()).isEqualTo("query");
        assertThat(built.at("/annotations/builtFrom").asText()).isEqualTo(storage);
        List<JsonNode> toDecoy = all.stream().filter(e -> e.get("url").asText().equals(storage + "_t/decoy")).toList();
        assertThat(toDecoy).hasSize(2);
        assertThat(toDecoy).allSatisfy(e -> {
            assertThat(e.at("/annotations/role").asText()).isEqualTo("decoy");
            assertThat(e.at("/annotations/identity").asText()).isEqualTo("alice");
        });
        assertThat(toDecoy.get(0).at("/annotations/issued").asBoolean()).isFalse();
        assertThat(toDecoy.get(0).at("/annotations/builtBy").asText()).isEqualTo("path");
        assertThat(toDecoy.get(0).at("/annotations/builtFrom").asText()).isEqualTo(storage);
        // The client had not requested the root yet, so nothing says what it is.
        assertThat(toDecoy.get(0).at("/annotations/builtFromRole").isNull()).isTrue();
        assertThat(toDecoy.get(1).at("/annotations/issuedVia").asText()).isEqualTo("body:container");
    }

    @Test
    void theTrapsShowInWhatTheStorageAnswers() throws Exception {
        JsonNode session = startSession(base);
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();
        String binary = null;
        String text = null;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = send("POST", storage, alice, "x" + i, i == 0 ? "application/octet-stream" : "text/plain");
            if (i == 0) {
                binary = r.headers().firstValue("Location").orElseThrow();
            } else {
                text = r.headers().firstValue("Location").orElseThrow();
            }
        }
        // Six members with the decoy: two pages, linked by opaque URLs.
        HttpResponse<String> listing = send("GET", storage, alice, null, "application/lws+json");
        String next = link(listing, "next");
        assertThat(next).startsWith(storage + "_t/p/");
        HttpResponse<String> page2 = send("GET", next, alice, null, "application/lws+json");
        assertThat(page2.statusCode()).isEqualTo(200);
        assertThat(link(page2, "prev")).startsWith(storage + "_t/p/");

        // A binary resource does not take PUT, and says so; a text one does.
        HttpResponse<String> read = send("GET", binary, alice, null, null);
        assertThat(read.headers().firstValue("Allow").orElseThrow()).doesNotContain("PUT");
        assertThat(link(read, "linkset")).startsWith(storage + "_t/l/");
        HttpResponse<String> put = send("PUT", binary, alice, "y", "application/octet-stream");
        assertThat(put.statusCode()).isEqualTo(405);
        assertThat(put.headers().firstValue("Allow").orElseThrow()).doesNotContain("PUT");
        assertThat(send("GET", text, alice, null, null).headers().firstValue("Allow").orElseThrow()).contains("PUT");
        assertThat(send("PUT", text, alice, "z", "text/plain").statusCode()).isEqualTo(204);

        List<JsonNode> all = list(exchanges(session, 0).get("exchanges"));
        JsonNode second = find(all, "GET", next);
        assertThat(second.at("/annotations/role").asText()).isEqualTo("page");
        assertThat(second.at("/annotations/issuedVia").asText()).isEqualTo("link:next");
        JsonNode refused = find(all, "PUT", binary);
        assertThat(refused.at("/annotations/advertised/Allow").asText()).doesNotContain("PUT");
    }

    @Test
    void theSessionApiTakesOnlyItsOwnKey() throws Exception {
        JsonNode one = startSession(base);
        JsonNode two = startSession(base);
        String api = one.get("api").asText();
        assertThat(get(api, null).statusCode()).isEqualTo(401);
        assertThat(get(api, "not-the-key").statusCode()).isEqualTo(401);
        assertThat(get(api, two.get("key").asText()).statusCode()).isEqualTo(401);
        assertThat(get(api, one.get("key").asText()).statusCode()).isEqualTo(200);
        assertThat(get(api + "x", one.get("key").asText()).statusCode()).isEqualTo(404);
        assertThat(get(api, one.get("key").asText()).body()).doesNotContain(one.get("key").asText());

        HttpResponse<String> page = get(one.get("page").asText(), null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("script-src 'self'");
        assertThat(page.body()).doesNotContain(one.get("key").asText());

        // A token of one session is nothing to another session's storage.
        HttpResponse<String> foreign = send("GET", two.get("storage").asText(), one.at("/tokens/alice").asText(),
                null, "application/lws+json");
        assertThat(foreign.statusCode()).isEqualTo(401);
        assertThat(foreign.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("invalid_token");

        // Ended: the storage and the API are gone.
        HttpRequest end = HttpRequest.newBuilder(URI.create(api)).DELETE()
                .header("Authorization", "Bearer " + one.get("key").asText()).build();
        assertThat(HTTP.send(end, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        assertThat(get(api, one.get("key").asText()).statusCode()).isEqualTo(404);
        assertThat(send("GET", one.get("storage").asText(), null, null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void aBrowserClientGetsCors() throws Exception {
        JsonNode session = startSession(base);
        String storage = session.get("storage").asText();
        HttpRequest preflight = HttpRequest.newBuilder(URI.create(storage))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://app.test")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization, content-type")
                .build();
        HttpResponse<String> answer = HTTP.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(answer.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://app.test");
        assertThat(answer.headers().firstValue("Access-Control-Allow-Headers")).hasValue("authorization, content-type");

        HttpRequest get = HttpRequest.newBuilder(URI.create(storage)).header("Origin", "https://app.test").GET().build();
        HttpResponse<String> got = HTTP.send(get, HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(401);
        assertThat(got.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://app.test");
        assertThat(got.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("WWW-Authenticate");

        JsonNode recorded = find(list(exchanges(session, 0).get("exchanges")), "OPTIONS", storage);
        assertThat(recorded.at("/annotations/role").asText()).isEqualTo("preflight");
    }

    @Test
    void theBoundsHold() throws Exception {
        int port = freePort();
        String tightBase = "http://localhost:" + port + "/tc";
        ClientLabConfig d = ClientLabConfig.defaults(URI.create(tightBase), "127.0.0.1", port);
        ClientLabConfig tight = new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, 10, 2,
                d.idleTimeout(), d.maxLifetime(), 1000, d.maxRecordedResponseBytes(), d.maxExchanges(), 2,
                d.maxStorageBytes(), 8, 0.001, d.tokenLifetime(), d.indexLag());
        try (ClientLab small = ClientLab.start(tight, TestRules.RULES)) {
            JsonNode session = startSession(tightBase);
            String storage = session.get("storage").asText();
            String alice = session.at("/tokens/alice").asText();
            assertThat(send("POST", storage, alice, "x".repeat(2000), "text/plain").statusCode()).isEqualTo(413);
            assertThat(send("POST", storage, alice, "a", "text/plain").statusCode()).isEqualTo(201);
            assertThat(send("POST", storage, alice, "b", "text/plain").statusCode()).isEqualTo(201);
            assertThat(send("POST", storage, alice, "c", "text/plain").statusCode()).isEqualTo(507);
            int limited = 0;
            for (int i = 0; i < 10; i++) {
                if (send("GET", storage, alice, null, null).statusCode() == 429) {
                    limited++;
                }
            }
            assertThat(limited).isGreaterThan(0);
            List<JsonNode> all = list(exchanges(session, 0).get("exchanges"));
            assertThat(all).extracting(e -> e.at("/annotations/limit").asText())
                    .contains("body", "storage", "rate");

            startSession(tightBase);
            HttpResponse<String> third = HTTP.send(HttpRequest.newBuilder(URI.create(tightBase + "/sessions"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(third.statusCode()).isEqualTo(429);
        }
    }

    @Test
    void anIdleSessionEnds() throws Exception {
        int port = freePort();
        String idleBase = "http://localhost:" + port + "/idle";
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T12:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        try (ClientLab idle = ClientLab.start(ClientLabConfig.defaults(URI.create(idleBase), "127.0.0.1", port), TestRules.RULES, clock)) {
            JsonNode session = startSession(idleBase);
            String api = session.get("api").asText();
            String key = session.get("key").asText();
            now.set(now.get().plus(Duration.ofMinutes(119)));
            assertThat(get(api, key).statusCode()).isEqualTo(200);
            now.set(now.get().plus(Duration.ofMinutes(119)));
            assertThat(get(api, key).statusCode()).as("touched two minutes ago, by the request above")
                    .isEqualTo(200);
            now.set(now.get().plus(Duration.ofHours(2)));
            assertThat(idle.sessions().sweep()).isEqualTo(1);
            assertThat(get(api, key).statusCode()).isEqualTo(404);
            assertThat(idle.sessions().size()).isZero();
        }
    }

    // ---- helpers ----

    private static JsonNode startSession(String serviceBase) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(serviceBase + "/sessions"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return JSON.readTree(r.body());
    }

    private static JsonNode exchanges(JsonNode session, long after) throws Exception {
        HttpResponse<String> r = get(session.get("api").asText() + "/exchanges?after=" + after,
                session.get("key").asText());
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static HttpResponse<String> get(String url, String key) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> send(String method, String url, String token, String body, String type)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (type != null) {
            b.header(body == null ? "Accept" : "Content-Type", type);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String link(HttpResponse<String> response, String rel) {
        for (String value : response.headers().allValues("Link")) {
            for (String part : value.split(",")) {
                if (part.contains("rel=\"" + rel + "\"")) {
                    return part.substring(part.indexOf('<') + 1, part.indexOf('>'));
                }
            }
        }
        throw new AssertionError("no rel=" + rel + " link in " + response.headers().allValues("Link"));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static JsonNode find(List<JsonNode> exchanges, String method, String url) {
        return exchanges.stream().filter(e -> e.get("method").asText().equals(method) && e.get("url").asText().equals(url))
                .findFirst().orElseThrow(() -> new AssertionError("no " + method + " " + url + " in the log"));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
