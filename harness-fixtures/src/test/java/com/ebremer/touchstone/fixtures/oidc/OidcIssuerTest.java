package com.ebremer.touchstone.fixtures.oidc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OidcIssuerTest {

    private static OidcIssuer issuer;
    private static HttpClient http;

    @BeforeAll
    static void start() {
        issuer = OidcIssuer.start(0);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        issuer.close();
    }

    @Test
    void publishesDiscoveryAndJwks() throws Exception {
        String discovery = get(issuer.baseUri().resolve(".well-known/openid-configuration"));
        assertThat(discovery).contains("\"issuer\"").contains(issuer.jwksUri().toString());

        String lws = get(issuer.baseUri().resolve(".well-known/lws-configuration"));
        assertThat(lws).contains("jwks_uri");

        String jwks = get(issuer.jwksUri());
        assertThat(jwks).contains("\"keys\"").contains(issuer.currentKey().getKeyID());
    }

    @Test
    void mintsValidRfc9068TokensAndBrokenVariants() throws Exception {
        AccessTokens tokens = new AccessTokens(issuer, "http://storage.example/");

        SignedJWT valid = SignedJWT.parse(tokens.valid("alice"));
        assertThat(valid.getHeader().getType().toString()).isEqualTo("at+jwt");
        assertThat(valid.getJWTClaimsSet().getSubject()).isEqualTo("alice");
        assertThat(valid.getJWTClaimsSet().getAudience()).containsExactly("http://storage.example/");
        assertThat(valid.getJWTClaimsSet().getIssuer()).isEqualTo(issuer.issuer());

        assertThat(SignedJWT.parse(tokens.expired("alice")).getJWTClaimsSet().getExpirationTime())
                .isBefore(new java.util.Date());
        assertThat(SignedJWT.parse(tokens.wrongAudience("alice")).getJWTClaimsSet().getAudience())
                .doesNotContain("http://storage.example/");
        // alg=none serializes as an unsecured JWT (two dots, empty signature)
        assertThat(tokens.algNone("alice")).endsWith(".");
    }

    /**
     * The published key ids, parsed rather than grepped. Key ids here are two characters
     * ("k1", "k2") and an RSA modulus is a few hundred characters of base64url, so a
     * substring search over the raw JWKS finds "k1" inside the modulus of an unrelated key
     * roughly one run in twelve — which made this test fail at random and, worse, could have
     * passed for the wrong reason in the other direction.
     */
    private static List<String> publishedKids() throws Exception {
        return JWKSet.parse(get(issuer.jwksUri())).getKeys().stream().map(JWK::getKeyID).toList();
    }

    @Test
    void rotationReplacesThePublishedKey() throws Exception {
        String beforeKid = issuer.currentKey().getKeyID();
        assertThat(publishedKids()).contains(beforeKid);

        issuer.rotateKeys();

        String afterKid = issuer.currentKey().getKeyID();
        assertThat(afterKid).isNotEqualTo(beforeKid);
        assertThat(publishedKids())
                .as("rotation retires the old key rather than publishing both")
                .containsExactly(afterKid);
    }

    private static String get(URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
