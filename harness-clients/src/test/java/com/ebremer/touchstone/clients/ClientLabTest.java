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
        ClientLabConfig d = ClientLabConfig.defaults(URI.create(base), "127.0.0.1", port);
        // The defaults, but more sessions from this one address than the tests start.
        lab = ClientLab.start(new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, d.maxSessions(), 100,
                d.idleTimeout(), d.maxLifetime(), d.maxBodyBytes(), d.maxRecordedResponseBytes(), d.maxExchanges(),
                d.maxResources(), d.maxStorageBytes(), d.requestBurst(), d.requestsPerSecond(), d.tokenLifetime(),
                d.indexLag(), d.maxDeliveries(), false), TestRules.RULES);
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
                d.maxStorageBytes(), 8, 0.001, d.tokenLifetime(), d.indexLag(), d.maxDeliveries(), false);
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

    @Test
    void theOpenIdProviderSignsInRegisteredClientsOnly() throws Exception {
        JsonNode session = startSession(base);
        String api = session.get("api").asText();
        String key = session.get("key").asText();
        String issuer = session.at("/openidProvider/issuer").asText();
        assertThat(issuer).isEqualTo(base + "/s/" + session.get("id").asText() + "/op");

        // Registration takes the session key, and absolute redirect URIs without fragments.
        assertThat(register(api, null, "{\"redirect_uris\": [\"http://127.0.0.1/cb\"]}").statusCode()).isEqualTo(401);
        assertThat(register(api, key, "{\"redirect_uris\": [\"javascript:alert(1)\"]}").statusCode()).isEqualTo(400);
        assertThat(register(api, key, "{\"redirect_uris\": [\"https://app.example/cb#x\"]}").statusCode()).isEqualTo(400);
        assertThat(register(api, key, "{\"redirect_uris\": [\"https://app.example/cb\"], \"client_id\": \"my app\"}")
                .statusCode()).isEqualTo(400);
        HttpResponse<String> made = register(api, key, "{\"redirect_uris\": [\"http://127.0.0.1/cb\"]}");
        assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
        String clientId = JSON.readTree(made.body()).get("client_id").asText();
        assertThat(JSON.readTree(get(api + "/clients", key).body()).at("/clients/0/client_id").asText()).isEqualTo(clientId);
        assertThat(get(api + "/credentials/bob", null).statusCode()).isEqualTo(401);
        JsonNode bob = JSON.readTree(get(api + "/credentials/bob", key).body());

        // bob's identity document names the provider; its discovery names the endpoints.
        HttpResponse<String> doc = send("GET", bob.get("webid").asText(), null, null, null);
        assertThat(doc.statusCode()).isEqualTo(200);
        assertThat(doc.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/cid");
        assertThat(JSON.readTree(doc.body()).at("/service/0/serviceEndpoint").asText()).isEqualTo(issuer);
        assertThat(JSON.readTree(doc.body()).at("/authentication/0/id").asText()).isEqualTo(bob.get("verificationMethod").asText());
        JsonNode discovery = JSON.readTree(send("GET", issuer + "/.well-known/openid-configuration", null, null, null).body());
        assertThat(discovery.get("issuer").asText()).isEqualTo(issuer);
        String authorize = discovery.get("authorization_endpoint").asText();

        String verifier = "a-verifier-of-at-least-forty-three-characters-0123456789";
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        String enc = java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8);
        String common = "?response_type=code&scope=openid&state=s1&client_id=" + enc;
        // An unknown client or redirect URI gets a page, never a redirect.
        HttpResponse<String> unknown = send("GET", authorize + "?response_type=code&scope=openid&client_id=nobody"
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%2Fcb&code_challenge=" + challenge + "&code_challenge_method=S256", null, null, null);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.headers().firstValue("Location")).isEmpty();
        assertThat(send("GET", authorize + common + "&redirect_uri=https%3A%2F%2Fevil.example%2Fcb&code_challenge=" + challenge
                + "&code_challenge_method=S256", null, null, null).statusCode()).isEqualTo(400);
        // Without PKCE: back to the client with an error.
        HttpResponse<String> noPkce = send("GET", authorize + common + "&redirect_uri=http%3A%2F%2F127.0.0.1%2Fcb", null, null, null);
        assertThat(noPkce.statusCode()).isEqualTo(303);
        assertThat(noPkce.headers().firstValue("Location").orElseThrow()).startsWith("http://127.0.0.1/cb?error=invalid_request");

        // A loopback redirect URI may use any port (RFC 8252 section 7.3).
        String redirect = "http://127.0.0.1:5555/cb";
        HttpResponse<String> form = send("GET", authorize + common + "&redirect_uri="
                + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8)
                + "&nonce=n1&code_challenge=" + challenge + "&code_challenge_method=S256", null, null, null);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("default-src 'none'");
        java.util.regex.Matcher handle = java.util.regex.Pattern.compile("name=\"request\" value=\"([^\"]*)\"").matcher(form.body());
        assertThat(handle.find()).isTrue();
        HttpResponse<String> wrong = send("POST", authorize, null, "request=" + handle.group(1) + "&username=bob&password=nope",
                "application/x-www-form-urlencoded");
        assertThat(wrong.statusCode()).isEqualTo(200);
        assertThat(wrong.body()).contains("Wrong username or password");
        HttpResponse<String> signedIn = send("POST", authorize, null, "request=" + handle.group(1) + "&username=bob&password="
                + bob.get("password").asText(), "application/x-www-form-urlencoded");
        assertThat(signedIn.statusCode()).isEqualTo(303);
        String location = signedIn.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(redirect + "?code=").contains("&state=s1&iss=");
        String code = java.net.URLDecoder.decode(location.replaceAll("^.*[?&]code=([^&]*).*$", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);

        String redeem = "grant_type=authorization_code&code=" + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8)
                + "&redirect_uri=" + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8)
                + "&client_id=" + enc + "&code_verifier=";
        HttpResponse<String> badVerifier = send("POST", discovery.get("token_endpoint").asText(), null, redeem + "nope",
                "application/x-www-form-urlencoded");
        assertThat(badVerifier.statusCode()).isEqualTo(400);
        assertThat(badVerifier.body()).contains("invalid_grant");
        // A code is good once, even after a failed redemption.
        assertThat(send("POST", discovery.get("token_endpoint").asText(), null, redeem + verifier,
                "application/x-www-form-urlencoded").statusCode()).isEqualTo(400);
        assertThat(log(session).toString()).doesNotContain(bob.get("password").asText()).doesNotContain(code);
    }

    @Test
    void anIdTokenNamesTheClientAndTheAuthorizationServer() throws Exception {
        JsonNode session = startSession(base);
        String api = session.get("api").asText();
        String key = session.get("key").asText();
        String clientId = "https://app.example/id";
        assertThat(register(api, key, "{\"redirect_uris\": [\"https://app.example/cb\"], \"client_id\": \"" + clientId + "\"}")
                .statusCode()).isEqualTo(201);
        JsonNode bob = JSON.readTree(get(api + "/credentials/bob", key).body());
        String issuer = session.at("/openidProvider/issuer").asText();
        String verifier = "another-verifier-of-at-least-forty-three-characters-xyz";
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        HttpResponse<String> form = send("GET", issuer + "/authorize?response_type=code&scope=openid&client_id="
                + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8)
                + "&redirect_uri=https%3A%2F%2Fapp.example%2Fcb&code_challenge=" + challenge + "&code_challenge_method=S256",
                null, null, null);
        java.util.regex.Matcher handle = java.util.regex.Pattern.compile("name=\"request\" value=\"([^\"]*)\"").matcher(form.body());
        assertThat(handle.find()).isTrue();
        String location = send("POST", issuer + "/authorize", null, "request=" + handle.group(1) + "&username=bob&password="
                + bob.get("password").asText(), "application/x-www-form-urlencoded").headers().firstValue("Location").orElseThrow();
        String code = java.net.URLDecoder.decode(location.replaceAll("^.*[?&]code=([^&]*).*$", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> tokens = send("POST", issuer + "/token", null, "grant_type=authorization_code&code="
                + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8)
                + "&redirect_uri=https%3A%2F%2Fapp.example%2Fcb&client_id="
                + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8) + "&code_verifier=" + verifier,
                "application/x-www-form-urlencoded");
        assertThat(tokens.statusCode()).as(tokens.body()).isEqualTo(200);
        String idToken = JSON.readTree(tokens.body()).get("id_token").asText();
        JsonNode claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(idToken.split("\\.")[1]));
        assertThat(claims.get("iss").asText()).isEqualTo(issuer);
        assertThat(claims.get("sub").asText()).isEqualTo(bob.get("webid").asText());
        assertThat(claims.get("azp").asText()).isEqualTo(clientId);
        assertThat(claims.get("aud").toString()).contains(clientId).contains(session.at("/authorizationServer/issuer").asText());

        // The session's authorization server takes it, for the session's storage.
        HttpResponse<String> exchanged = send("POST", session.at("/authorizationServer/issuer").asText() + "/token", null,
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&resource="
                        + java.net.URLEncoder.encode(session.get("storage").asText(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&subject_token=" + idToken + "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aid_token",
                "application/x-www-form-urlencoded");
        assertThat(exchanged.statusCode()).as(exchanged.body()).isEqualTo(200);
        JsonNode exchange = list(log(session).get("exchanges")).getLast();
        assertThat(exchange.at("/annotations/credentialSource").asText()).isEqualTo("openidProvider");
        assertThat(exchange.at("/annotations/credential/claims/azp").asText()).isEqualTo(clientId);
        assertThat(exchange.toString()).doesNotContain(idToken);
    }

    @Test
    void theAuthorizationServerFetchesNothingOutsideTheSession() throws Exception {
        JsonNode session = startSession(base);
        // A credential about a subject elsewhere: the session must not dereference it.
        com.nimbusds.jose.jwk.ECKey stranger = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256)
                .keyID("k").generate();
        String subject = "http://127.0.0.1:" + freePort() + "/agent";
        long now = Instant.now().getEpochSecond();
        com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.ES256).keyID(subject + "#k").build(),
                new com.nimbusds.jose.Payload("{\"sub\":\"" + subject + "\",\"iss\":\"" + subject + "\",\"client_id\":\""
                        + subject + "\",\"aud\":\"" + session.at("/authorizationServer/issuer").asText() + "\",\"iat\":" + now
                        + ",\"exp\":" + (now + 300) + "}"));
        jws.sign(new com.nimbusds.jose.crypto.ECDSASigner(stranger));
        HttpResponse<String> refused = send("POST", session.at("/authorizationServer/issuer").asText() + "/token", null,
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&resource="
                        + java.net.URLEncoder.encode(session.get("storage").asText(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&subject_token=" + jws.serialize() + "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt",
                "application/x-www-form-urlencoded");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("is not a document this authorization server trusts");
        JsonNode exchange = list(log(session).get("exchanges")).getLast();
        assertThat(exchange.at("/annotations/credentialSource").asText()).isEqualTo("other");
        assertThat(exchange.at("/annotations/audienceIncludesAs").asBoolean()).isTrue();
    }

    @Test
    void anExpiredTokenStaysRefused() throws Exception {
        JsonNode session = startSession(base);
        String api = session.get("api").asText();
        String key = session.get("key").asText();
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();
        HttpRequest arm = HttpRequest.newBuilder(URI.create(api + "/faults/tokenExpired"))
                .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(HTTP.send(arm, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        // The decoy does not use it up.
        assertThat(send("GET", storage + "_t/decoy", alice, null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> expired = send("GET", storage, alice, null, "application/lws+json");
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("error=\"invalid_token\"")
                .contains("realm=\"" + storage + "\"");
        assertThat(send("GET", storage, alice, null, "application/lws+json").statusCode()).isEqualTo(401);
        String fresh = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "/tokens/alice"))
                .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("access_token").asText();
        assertThat(send("GET", storage, fresh, null, "application/lws+json").statusCode()).isEqualTo(200);
        List<JsonNode> all = list(log(session).get("exchanges"));
        JsonNode fired = all.stream().filter(e -> "tokenExpired".equals(e.at("/annotations/fault").asText())).findFirst().orElseThrow();
        assertThat(fired.at("/annotations/role").asText()).isEqualTo("container");
        assertThat(fired.at("/annotations/identity").isNull()).isTrue();
    }

    @Test
    void notificationsGoNowhereTheGuardForbids() throws Exception {
        JsonNode session = startSession(base);
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();
        String subscriptions = storage + "_subscriptions/";
        for (String inbox : new String[] {"http://inbox.example/plain", "https://localhost:9/loopback"}) {
            HttpResponse<String> made = send("POST", subscriptions, alice, "{\"type\": \"WebhookSubscription\", \"topic\": [\""
                    + storage + "\"], \"inbox\": \"" + inbox + "\"}", "application/lws+json");
            assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
        }
        assertThat(send("POST", storage, alice, "a change", "text/plain").statusCode()).isEqualTo(201);
        List<JsonNode> deliveries = new ArrayList<>();
        for (int i = 0; i < 50 && deliveries.stream().noneMatch(d -> d.get("url").asText().contains("localhost")); i++) {
            Thread.sleep(100);
            deliveries = list(log(session).get("exchanges")).stream()
                    .filter(e -> e.at("/annotations/role").asText().equals("delivery")).toList();
        }
        JsonNode plain = deliveries.stream().filter(d -> d.get("url").asText().startsWith("http://inbox.example"))
                .findFirst().orElseThrow();
        assertThat(plain.get("status").asInt()).isZero();
        assertThat(plain.at("/annotations/limit").asText()).isEqualTo("inbox");
        assertThat(plain.at("/responseBody/text").asText()).contains("https");
        JsonNode loopback = deliveries.stream().filter(d -> d.get("url").asText().contains("localhost")).findFirst().orElseThrow();
        assertThat(loopback.get("status").asInt()).isZero();
        assertThat(loopback.at("/responseBody/text").asText()).contains("no public address");
        assertThat(loopback.at("/annotations/deliverySignature").asText()).isEqualTo("genuine");
        assertThat(loopback.at("/requestHeaders/Signature-Input/0").asText()).contains("keyid=\"" + storage + "#notify-key\"");
    }

    // ---- helpers ----

    @Test
    void theResultsExportAsJsonEarlAndJunitAboutTheNamedClient() throws Exception {
        String rule = "client-token-in-authorization-header";
        // Settings are checked whole before a session starts.
        for (String bad : new String[] {"[1]", "{\"areas\": []}", "{\"areas\": [\"everything\"]}", "{\"colour\": 1}",
                "{\"clientUnderTest\": {\"homepage\": \"javascript:alert(1)\"}}", "{\"clientUnderTest\": {\"name\": 7}}",
                "not json"}) {
            HttpResponse<String> refused = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/sessions"))
                    .POST(HttpRequest.BodyPublishers.ofString(bad)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(refused.statusCode()).as(bad).isEqualTo(400);
            assertThat(JSON.readTree(refused.body()).get("error").asText()).isEqualTo("invalid_settings");
        }
        JsonNode session = startSession(base, "{\"clientUnderTest\": {\"name\": \"Example client\", \"version\": \"1.2.3\","
                + " \"homepage\": \"https://client.example/\"}, \"areas\": [\"authentication\", \"core\"]}");
        assertThat(session.at("/clientUnderTest/name").asText()).isEqualTo("Example client");
        assertThat(session.get("areas").toString()).isEqualTo("[\"core\",\"authentication\"]");
        String api = session.get("api").asText();
        String key = session.get("key").asText();
        assertThat(session.at("/exports/earl").asText()).isEqualTo(api + "/results?format=earl");
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();

        // One trial of the rule passes, one fails: the token in the query string.
        assertThat(send("GET", storage, alice, null, "application/lws+json").statusCode()).isEqualTo(200);
        send("GET", storage + "?access_token=" + alice, null, null, "application/lws+json");

        JsonNode results = JSON.readTree(get(api + "/results", key).body());
        JsonNode judged = list(results.get("rules")).stream().filter(r -> r.get("rule").asText().equals(rule)).findFirst()
                .orElseThrow();
        assertThat(judged.get("outcome").asText()).isEqualTo("failed");
        assertThat(judged.at("/evidence/status").asInt()).isPositive();
        assertThat(judged.get("source").get(0).asText()).startsWith("https://www.w3.org/TR/");
        assertThat(list(results.get("rules")).stream().filter(r -> r.get("area").asText().equals("notifications"))
                .map(r -> r.get("outcome").asText()).distinct().toList()).containsExactly("inapplicable");
        assertThat(results.at("/clientUnderTest/version").asText()).isEqualTo("1.2.3");
        assertThat(results.at("/harness/name").asText()).isEqualTo("Touchstone");
        assertThat(results.get("since").asText()).isEqualTo(session.get("created").asText());
        int rules = results.get("rules").size();

        HttpResponse<String> json = get(api + "/results?format=json", key);
        assertThat(json.headers().firstValue("Content-Disposition").orElseThrow())
                .isEqualTo("attachment; filename=\"touchstone-client-session-" + session.get("id").asText() + ".json\"");

        // EARL: one assertion per rule, semi-automatic, about the client by its homepage.
        HttpResponse<String> earl = get(api + "/results?format=earl", key);
        assertThat(earl.statusCode()).isEqualTo(200);
        assertThat(earl.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/turtle");
        assertThat(earl.body()).doesNotContain(alice);
        org.apache.jena.rdf.model.Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        org.apache.jena.riot.RDFParser.fromString(earl.body(), org.apache.jena.riot.Lang.TURTLE).parse(m);
        String earlNs = "http://www.w3.org/ns/earl#";
        org.apache.jena.rdf.model.Property mode = m.createProperty(earlNs, "mode");
        assertThat(m.listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type, m.createResource(earlNs + "Assertion"))
                .toList()).hasSize(rules);
        assertThat(m.listObjectsOfProperty(mode).toList()).containsExactly(m.createResource(earlNs + "semiAuto"));
        org.apache.jena.rdf.model.Resource client = m.createResource("https://client.example/");
        assertThat(m.contains(client, m.createProperty("http://usefulinc.com/ns/doap#", "name"), "Example client")).isTrue();
        org.apache.jena.rdf.model.Resource testCase = m.createResource(judged.get("iri").asText());
        assertThat(m.contains(testCase, org.apache.jena.vocabulary.RDF.type, m.createResource(earlNs + "TestCase"))).isTrue();
        org.apache.jena.rdf.model.Resource assertion = m.listResourcesWithProperty(m.createProperty(earlNs, "test"), testCase)
                .next();
        org.apache.jena.rdf.model.Resource result = assertion.getPropertyResourceValue(m.createProperty(earlNs, "result"));
        assertThat(result.getPropertyResourceValue(m.createProperty(earlNs, "outcome")).getURI()).isEqualTo(earlNs + "failed");
        assertThat(result.getProperty(m.createProperty(earlNs, "info")).getString()).contains("exchange #").contains("presentation");

        // JUnit XML: one case per rule, the failure among them.
        HttpResponse<String> junit = get(api + "/results?format=junit", key);
        assertThat(junit.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/xml");
        assertThat(junit.body()).doesNotContain(alice);
        org.w3c.dom.Document xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(junit.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(xml.getDocumentElement().getAttribute("tests")).isEqualTo(String.valueOf(rules));
        boolean failure = false;
        org.w3c.dom.NodeList cases = xml.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) cases.item(i);
            if (c.getAttribute("name").equals(rule)) {
                assertThat(c.getAttribute("classname")).isEqualTo("clients/core");
                failure = c.getElementsByTagName("failure").getLength() == 1;
            }
        }
        assertThat(failure).isTrue();
        assertThat(get(api + "/results?format=pdf", key).statusCode()).isEqualTo(400);

        // PATCH changes the settings; a bad one changes nothing.
        HttpResponse<String> patched = patch(api, key, "{\"areas\": [\"authentication\"], \"clientUnderTest\": null}");
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(JSON.readTree(patched.body()).get("areas").toString()).isEqualTo("[\"authentication\"]");
        assertThat(JSON.readTree(patched.body()).at("/clientUnderTest/name").isNull()).isTrue();
        assertThat(patch(api, key, "{\"areas\": [\"core\"], \"clientUnderTest\": {\"name\": \"" + "x".repeat(101) + "\"}}")
                .statusCode()).isEqualTo(400);
        JsonNode after = JSON.readTree(get(api + "/results", key).body());
        assertThat(list(after.get("rules")).stream().filter(r -> r.get("rule").asText().equals(rule)).findFirst().orElseThrow()
                .get("outcome").asText()).isEqualTo("inapplicable");
        assertThat(get(api + "/results?format=earl", key).body()).contains("the client tested in session " + session.get("id").asText());

        // A reset starts the results, and their date, over.
        assertThat(send("POST", api + "/reset", key, null, null).statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(get(api + "/results", key).body()).get("since").asText())
                .isNotEqualTo(session.get("created").asText());
    }

    private static HttpResponse<String> patch(String api, String key, String body) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api)).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> register(String api, String key, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + "/clients")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode log(JsonNode session) throws Exception {
        return exchanges(session, 0);
    }

    private static JsonNode startSession(String serviceBase) throws Exception {
        return startSession(serviceBase, null);
    }

    private static JsonNode startSession(String serviceBase, String settings) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(serviceBase + "/sessions"))
                .header("Content-Type", "application/json")
                .POST(settings == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(settings))
                .build(), HttpResponse.BodyHandlers.ofString());
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
