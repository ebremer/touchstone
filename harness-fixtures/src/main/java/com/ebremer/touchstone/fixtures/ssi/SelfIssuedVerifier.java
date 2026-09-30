package com.ebremer.touchstone.fixtures.ssi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

/**
 * Validates a self-issued authentication credential the way a conforming verifier must
 * (CID suite): reject {@code alg=none}; require {@code sub = iss = client_id} as one URI;
 * require the {@code aud} to include the authorization server; require {@code exp} in the
 * future; then take the verification method {@code kid} names from the subject's controlled
 * identifier document and verify the signature with it. For a did:key subject that document
 * is the DID document the did:key method derives from the identifier, which the CID suite
 * accepts because DID documents extend controlled identifier documents.
 */
public final class SelfIssuedVerifier {

    /** A credential that fails any validation check. */
    public static final class InvalidCredentialException extends RuntimeException {
        public InvalidCredentialException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String authorizationServer;

    public SelfIssuedVerifier(String authorizationServer) {
        this.authorizationServer = authorizationServer;
    }

    /**
     * Validates a credential whose subject is a did:key. Its DID document has one
     * verification method, {@code did:key:z...#z...}, so {@code kid} must name that one.
     */
    public String verifyDidKey(String token) {
        return verify(token, (sub, kid) -> {
            String method = sub + "#" + sub.substring("did:key:".length());
            if (kid == null || !(kid.equals(method) || (sub + "#" + kid).equals(method))) {
                throw new IllegalArgumentException("kid " + kid + " names no verification method of " + sub);
            }
            return DidKey.publicKeyFromDid(sub);
        });
    }

    /** Validates a CID credential; the key comes from the dereferenced CID document. */
    public String verifyCid(String token, HttpClient http) {
        return verify(token, (sub, kid) -> resolveFromCidDocument(sub, kid, http));
    }

    private String verify(String token,
                          BiFunction<String, String, Ed25519PublicKeyParameters> keyResolver) {
        SelfIssuedJwt.Parsed parsed;
        try {
            parsed = SelfIssuedJwt.parse(token);
        } catch (RuntimeException e) {
            throw new InvalidCredentialException("unparseable credential: " + e.getMessage());
        }
        if (parsed.alg() == null || parsed.alg().equalsIgnoreCase("none")) {
            throw new InvalidCredentialException("signing algorithm 'none' is rejected");
        }
        String sub = text(parsed.claims(), "sub");
        String iss = text(parsed.claims(), "iss");
        String clientId = text(parsed.claims(), "client_id");
        if (sub == null || !sub.equals(iss) || !sub.equals(clientId)) {
            throw new InvalidCredentialException("sub, iss, and client_id must be the same URI");
        }
        if (!audienceIncludes(parsed.claims(), authorizationServer)) {
            throw new InvalidCredentialException("aud must include the authorization server");
        }
        JsonNode exp = parsed.claims().get("exp");
        if (exp == null || exp.asLong() <= Instant.now().getEpochSecond()) {
            throw new InvalidCredentialException("credential is expired or missing exp");
        }
        if (!parsed.claims().has("iat")) {
            throw new InvalidCredentialException("missing iat");
        }
        Ed25519PublicKeyParameters key;
        try {
            key = keyResolver.apply(sub, parsed.kid());
        } catch (RuntimeException e) {
            throw new InvalidCredentialException("cannot resolve verification key: " + e.getMessage());
        }
        if (!SelfIssuedJwt.verifyEdDsa(parsed, key)) {
            throw new InvalidCredentialException("signature does not verify");
        }
        return sub;
    }

    private static Ed25519PublicKeyParameters resolveFromCidDocument(String sub, String kid, HttpClient http) {
        JsonNode doc;
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(sub)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("CID document fetch returned " + response.statusCode());
            }
            doc = JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("cannot dereference subject: " + e.getMessage(), e);
        }
        if (!sub.equals(text(doc, "id"))) {
            throw new IllegalStateException("CID document id does not equal the subject");
        }
        for (JsonNode vm : doc.path("verificationMethod")) {
            if (kid != null && kid.equals(text(vm, "id"))) {
                return DidKey.publicKeyFromMultibase(text(vm, "publicKeyMultibase"));
            }
        }
        throw new IllegalStateException("no verification method matches kid " + kid);
    }

    private static boolean audienceIncludes(JsonNode claims, String audience) {
        JsonNode aud = claims.get("aud");
        if (aud == null) {
            return false;
        }
        if (aud.isTextual()) {
            return aud.asText().equals(audience);
        }
        for (JsonNode value : aud) {
            if (value.asText().equals(audience)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }
}
