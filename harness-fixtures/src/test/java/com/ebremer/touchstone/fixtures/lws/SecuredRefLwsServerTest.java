package com.ebremer.touchstone.fixtures.lws;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.ebremer.touchstone.fixtures.oidc.AccessTokens;
import com.ebremer.touchstone.fixtures.oidc.OidcIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct HTTP checks of secured mode, including the temporal cases (key rotation
 * mid-session) that don't fit a declarative manifest.
 */
class SecuredRefLwsServerTest {

    private static OidcIssuer issuer;
    private static RefLwsServer storage;
    private static AccessTokens tokens;
    private static HttpClient http;
    private static URI aliceContainer;

    @BeforeAll
    static void start() throws Exception {
        issuer = OidcIssuer.start(0);
        storage = RefLwsServer.startSecured(0, issuer);
        tokens = new AccessTokens(issuer, storage.baseUri().toString());
        http = HttpClient.newHttpClient();
        // alice creates a container she owns
        HttpResponse<Void> created = http.send(HttpRequest.newBuilder(storage.baseUri())
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Link", "<https://www.w3.org/ns/lws#Container>; rel=\"type\"")
                .header("Slug", "alice-space")
                .header("Authorization", "Bearer " + tokens.valid("alice"))
                .build(), HttpResponse.BodyHandlers.discarding());
        aliceContainer = storage.baseUri().resolve(created.headers().firstValue("Location").orElseThrow());
    }

    @AfterAll
    static void stop() {
        storage.close();
        issuer.close();
    }

    @Test
    void anonymousRequestGets401WithConformingChallenge() throws Exception {
        HttpResponse<Void> r = get(aliceContainer, null);
        assertThat(r.statusCode()).isEqualTo(401);
        String challenge = r.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertThat(challenge).contains("Bearer").contains("as_uri=").contains("realm=");
    }

    @Test
    void validOwnerTokenGrantsAccess() throws Exception {
        assertThat(get(aliceContainer, tokens.valid("alice")).statusCode()).isEqualTo(200);
    }

    @Test
    void brokenTokensAllGet401() throws Exception {
        assertThat(get(aliceContainer, tokens.expired("alice")).statusCode()).isEqualTo(401);
        assertThat(get(aliceContainer, tokens.wrongAudience("alice")).statusCode()).isEqualTo(401);
        assertThat(get(aliceContainer, tokens.wrongIssuer("alice")).statusCode()).isEqualTo(401);
        assertThat(get(aliceContainer, tokens.badSignature("alice")).statusCode()).isEqualTo(401);
        assertThat(get(aliceContainer, tokens.unknownKey("alice")).statusCode()).isEqualTo(401);
        assertThat(get(aliceContainer, tokens.algNone("alice")).statusCode()).isEqualTo(401);

        HttpResponse<Void> expired = get(aliceContainer, tokens.expired("alice"));
        assertThat(expired.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("error=\"invalid_token\"");
    }

    @Test
    void validNonOwnerGets403NotConfusedWith401() throws Exception {
        assertThat(get(aliceContainer, tokens.valid("bob")).statusCode()).isEqualTo(403);
    }

    @Test
    void keyRotatedMidSessionInvalidatesPreviouslyValidToken() throws Exception {
        try (OidcIssuer rotating = OidcIssuer.start(0);
             RefLwsServer secured = RefLwsServer.startSecured(0, rotating)) {
            AccessTokens issued = new AccessTokens(rotating, secured.baseUri().toString());
            String token = issued.valid("alice");
            // alice creates her space with the token, proving it is valid now
            HttpResponse<Void> created = http.send(HttpRequest.newBuilder(secured.baseUri())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Link", "<https://www.w3.org/ns/lws#Container>; rel=\"type\"")
                    .header("Slug", "space")
                    .header("Authorization", "Bearer " + token)
                    .build(), HttpResponse.BodyHandlers.discarding());
            URI space = secured.baseUri().resolve(created.headers().firstValue("Location").orElseThrow());
            assertThat(get(space, token).statusCode()).isEqualTo(200);

            rotating.rotateKeys();

            // the same token no longer validates against the rotated JWKS
            assertThat(get(space, token).statusCode()).isEqualTo(401);
        }
    }

    private static HttpResponse<Void> get(URI uri, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Accept", "application/lws+json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding());
    }
}
