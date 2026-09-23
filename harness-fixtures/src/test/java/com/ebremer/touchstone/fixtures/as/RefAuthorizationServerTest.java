package com.ebremer.touchstone.fixtures.as;

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

class RefAuthorizationServerTest {

    private static RefAuthorizationServer issuer;
    private static HttpClient http;

    @BeforeAll
    static void start() {
        issuer = RefAuthorizationServer.start(0);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        issuer.close();
    }

    @Test
    void publishesMetadataAndJwks() throws Exception {
        String lws = get(issuer.baseUri().resolve(".well-known/lws-configuration"));
        assertThat(lws).contains("\"issuer\":\"" + issuer.issuer() + "\"")
                .contains(issuer.jwksUri().toString())
                .contains(issuer.tokenEndpoint().toString())
                .contains("urn:ietf:params:oauth:grant-type:token-exchange")
                .contains("did:key");

        String jwks = get(issuer.jwksUri());
        assertThat(jwks).contains("\"keys\"").contains(issuer.currentKey().getKeyID());
    }

    @Test
    void refusesAnExchangeWithoutASubjectTokenOrForAnUnknownStorage() throws Exception {
        HttpResponse<String> missing = post("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
                + "&resource=http%3A%2F%2Fstorage%2F");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("\"error\":\"invalid_request\"");

        HttpResponse<String> unknown = post("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
                + "&resource=http%3A%2F%2Fnowhere%2F&subject_token=x"
                + "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("\"error\":\"invalid_target\"");
        assertThat(unknown.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    private static HttpResponse<String> post(String form) throws Exception {
        return http.send(HttpRequest.newBuilder(issuer.tokenEndpoint())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
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
