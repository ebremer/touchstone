package com.ebremer.touchstone.core.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * Compact JWS minting, decoding and the three signature faults a real token can be given
 * without the issuer's key (vocab.yamlld): SignatureCorrupted, AlgNone and UnknownKeyId.
 */
final class Jwts {

    /** The kid a re-signed token names, which no JWKS or controlled identifier document publishes. */
    static final String UNKNOWN_KID = "touchstone-unknown-kid";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jwts() {
    }

    /** A token decoded without verification. */
    record Decoded(ObjectNode header, ObjectNode claims, String[] parts) {
    }

    /** Signs {@code claims} under {@code header}, both taken exactly as given (member order kept). */
    static String sign(JsonNode header, JsonNode claims, JWK key) {
        try {
            JWSHeader h = JWSHeader.parse(header.toString());
            JWSObject jws = new JWSObject(h, new Payload(claims.toString()));
            jws.sign(signer(key));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign a token: " + e.getMessage(), e);
        }
    }

    static Decoded decode(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a compact JWS: " + parts.length + " parts");
        }
        try {
            JsonNode header = Templates.JSON.readTree(B64D.decode(parts[0]));
            JsonNode claims = Templates.JSON.readTree(B64D.decode(parts[1]));
            if (!header.isObject() || !claims.isObject()) {
                throw new IllegalArgumentException("header or payload is not a JSON object");
            }
            return new Decoded((ObjectNode) header, (ObjectNode) claims, parts);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a decodable JWS: " + e.getMessage(), e);
        }
    }

    /** SignatureCorrupted: the last byte of the signature flipped; header and claims unchanged. */
    static String corruptSignature(String token) {
        Decoded d = decode(token);
        byte[] sig = B64D.decode(d.parts()[2]);
        if (sig.length == 0) {
            throw new IllegalArgumentException("the token has no signature to corrupt");
        }
        sig[sig.length - 1] ^= (byte) 0xFF;
        return d.parts()[0] + "." + d.parts()[1] + "." + B64.encodeToString(sig);
    }

    /** AlgNone: the header's alg set to "none" and the signature part left empty; claims unchanged. */
    static String algNone(String token) {
        Decoded d = decode(token);
        ObjectNode header = d.header().deepCopy();
        header.put("alg", "none");
        return B64.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "." + d.parts()[1] + ".";
    }

    /** UnknownKeyId: the same header and claims, re-signed by a fresh key whose kid nobody publishes. */
    static String resignWithUnknownKey(String token) {
        Decoded d = decode(token);
        String alg = d.header().path("alg").asText();
        JWK fresh = alg.startsWith("ES") ? freshEc(UNKNOWN_KID) : freshRsa(UNKNOWN_KID);
        ObjectNode header = d.header().deepCopy();
        header.put("kid", UNKNOWN_KID);
        return sign(header, d.claims(), fresh);
    }

    static boolean verify(String token, JWK key) {
        try {
            JWSObject jws = JWSObject.parse(token);
            if (JWSAlgorithm.NONE.getName().equals(jws.getHeader().getAlgorithm().getName())) {
                return false;
            }
            JWSVerifier verifier = key instanceof ECKey ec ? new ECDSAVerifier(ec.toPublicJWK())
                    : new RSASSAVerifier(((RSAKey) key).toPublicJWK());
            return jws.verify(verifier);
        } catch (Exception e) {
            return false;
        }
    }

    static ECKey freshEc(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate a P-256 key", e);
        }
    }

    static RSAKey freshRsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate an RSA key", e);
        }
    }

    /** RS256 for an RSA key, ES256 for a P-256 key: the algorithms EXECUTION.md 5.2 names. */
    static String algorithmFor(JWK key) {
        return key instanceof ECKey ? "ES256" : "RS256";
    }

    private static JWSSigner signer(JWK key) throws JOSEException {
        if (key instanceof ECKey ec) {
            return new ECDSASigner(ec);
        }
        if (key instanceof RSAKey rsa) {
            return new RSASSASigner(rsa);
        }
        throw new IllegalArgumentException("unsupported signing key type " + key.getKeyType());
    }
}
