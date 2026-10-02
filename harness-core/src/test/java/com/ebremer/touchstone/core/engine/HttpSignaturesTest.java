package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import org.junit.jupiter.api.Test;

/** The fixture host's RFC 9421 / RFC 9530 inspection of a delivery (EXECUTION.md section 5.4). */
class HttpSignaturesTest {

    private static final URI INBOX = URI.create("https://fixtures.test/touchstone-fixtures/t/inbox/abc/");
    private static final String STORAGE = "https://storage.test/lws/";
    private static final byte[] BODY = "{\"type\":\"Notification\"}".getBytes(StandardCharsets.UTF_8);

    private static String digest(byte[] body) throws Exception {
        return "sha-256=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":";
    }

    /** Signs the six components the webhook suite requires, as a conforming server does. */
    private static Map<String, List<String>> signed(String jdkAlg, java.security.PrivateKey key, String alg,
                                                    String keyid, URI target, byte[] body) throws Exception {
        String params = "(\"@method\" \"@scheme\" \"@authority\" \"@path\" \"content-type\" \"content-digest\")"
                + ";created=1790000000;keyid=\"" + keyid + "\";alg=\"" + alg + "\"";
        String cd = digest(body);
        String base = "\"@method\": POST\n\"@scheme\": " + target.getScheme() + "\n\"@authority\": "
                + target.getRawAuthority() + "\n\"@path\": " + target.getRawPath()
                + "\n\"content-type\": application/lws+json\n\"content-digest\": " + cd
                + "\n\"@signature-params\": " + params;
        Signature s = Signature.getInstance(jdkAlg);
        s.initSign(key);
        s.update(base.getBytes(StandardCharsets.UTF_8));
        return Map.of("Content-Type", List.of("application/lws+json"), "Content-Digest", List.of(cd),
                "Signature-Input", List.of("sig1=" + params),
                "Signature", List.of("sig1=:" + Base64.getEncoder().encodeToString(s.sign()) + ":"));
    }

    private static ObjectNode description(JsonNode publicJwk, boolean inAuthentication) {
        ObjectNode d = Templates.JSON.createObjectNode();
        d.put("id", STORAGE);
        ObjectNode vm = d.putArray("verificationMethod").addObject();
        vm.put("id", STORAGE + "#key-1");
        vm.put("type", "JsonWebKey");
        vm.set("publicKeyJwk", publicJwk);
        if (inAuthentication) {
            d.putArray("authentication").add(STORAGE + "#key-1");
        }
        return d;
    }

    @Test
    void anEcdsaSignedDeliveryVerifies() throws Exception {
        ECKey ec = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256).keyID("key-1").generate();
        var headers = signed("SHA256withECDSAinP1363Format", ec.toECPrivateKey(), "ecdsa-p256-sha256",
                STORAGE + "#key-1", INBOX, BODY);
        JsonNode jwk = Templates.JSON.readTree(ec.toPublicJWK().toJSONString());

        ObjectNode r = HttpSignatures.inspect(INBOX, "POST", headers, BODY, u -> description(jwk, true));

        assertThat(r.path("contentDigest").path("valid").asBoolean()).isTrue();
        JsonNode s = r.path("signature");
        assertThat(s.path("verified").asBoolean()).as(s.toString()).isTrue();
        assertThat(s.path("keyResolved").asBoolean()).isTrue();
        assertThat(s.path("keyInAuthentication").asBoolean()).isTrue();
        assertThat(s.path("storageDescriptionIdMatches").asBoolean()).isTrue();
        assertThat(s.path("created").asLong()).isEqualTo(1790000000L);
        assertThat(s.path("covered").toString()).contains("@authority", "content-digest");
    }

    @Test
    void anEd25519SignedDeliveryVerifies() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = kp.getPublic().getEncoded();
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(spki, 12, 44));
        JsonNode jwk = Templates.JSON.readTree("{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + x + "\"}");
        var headers = signed("Ed25519", kp.getPrivate(), "ed25519", STORAGE + "#key-1", INBOX, BODY);

        JsonNode s = HttpSignatures.inspect(INBOX, "POST", headers, BODY, u -> description(jwk, true)).path("signature");
        assertThat(s.path("verified").asBoolean()).as(s.toString()).isTrue();
    }

    @Test
    void aSignatureOverAnotherAuthorityOrBodyDoesNotVerify() throws Exception {
        ECKey ec = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256).keyID("key-1").generate();
        JsonNode jwk = Templates.JSON.readTree(ec.toPublicJWK().toJSONString());
        // signed for a different inbox: the authority in the base differs
        var elsewhere = signed("SHA256withECDSAinP1363Format", ec.toECPrivateKey(), "ecdsa-p256-sha256",
                STORAGE + "#key-1", URI.create("https://other.test/hook/"), BODY);
        assertThat(HttpSignatures.inspect(INBOX, "POST", elsewhere, BODY, u -> description(jwk, true))
                .path("signature").path("verified").asBoolean()).isFalse();
        // a body that is not the one digested
        var headers = signed("SHA256withECDSAinP1363Format", ec.toECPrivateKey(), "ecdsa-p256-sha256",
                STORAGE + "#key-1", INBOX, BODY);
        byte[] other = "{\"type\":\"Other\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(HttpSignatures.inspect(INBOX, "POST", headers, other, u -> description(jwk, true))
                .path("contentDigest").path("valid").asBoolean()).isFalse();
    }

    @Test
    void anUnsignedDeliveryAndAnUnresolvableKeyAreRecordedAsSuch() throws Exception {
        JsonNode unsigned = HttpSignatures.inspect(INBOX, "POST", Map.of("Content-Type", List.of("application/lws+json")),
                BODY, u -> null);
        assertThat(unsigned.path("signature").path("present").asBoolean()).isFalse();
        assertThat(unsigned.path("contentDigest").path("present").asBoolean()).isFalse();

        ECKey ec = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256).keyID("key-1").generate();
        var headers = signed("SHA256withECDSAinP1363Format", ec.toECPrivateKey(), "ecdsa-p256-sha256",
                STORAGE + "#key-2", INBOX, BODY);
        JsonNode jwk = Templates.JSON.readTree(ec.toPublicJWK().toJSONString());
        JsonNode s = HttpSignatures.inspect(INBOX, "POST", headers, BODY, u -> description(jwk, false)).path("signature");
        assertThat(s.path("keyResolved").asBoolean()).isFalse();
        assertThat(s.path("verified").asBoolean()).isFalse();
    }
}
