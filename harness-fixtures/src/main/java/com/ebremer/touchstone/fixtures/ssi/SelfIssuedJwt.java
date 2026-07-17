package com.ebremer.touchstone.fixtures.ssi;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Minimal EdDSA (Ed25519) compact JWS, hand-built so the did:key / CID suites need no
 * JOSE Ed25519 provider. Enough to mint valid credentials and every broken variant the
 * negative matrix requires — including an {@code alg=none} unsecured token.
 */
public final class SelfIssuedJwt {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private SelfIssuedJwt() {
    }

    /** Signs {@code claimsJson} with EdDSA; header carries alg=EdDSA, typ=JWT and the kid. */
    public static String signEdDsa(String kid, String claimsJson, Ed25519PrivateKeyParameters key) {
        String header = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":" + quote(kid) + "}";
        String signingInput = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + B64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        byte[] input = signingInput.getBytes(StandardCharsets.US_ASCII);
        signer.update(input, 0, input.length);
        return signingInput + "." + B64.encodeToString(signer.generateSignature());
    }

    /** An unsecured token (alg=none, empty signature) — must be rejected by a verifier. */
    public static String unsecured(String kid, String claimsJson) {
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":" + quote(kid) + "}";
        return B64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + B64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    /** Parsed view of a compact JWS. */
    public record Parsed(JsonNode header, JsonNode claims, String signingInput, byte[] signature) {

        public String alg() {
            return header.path("alg").asText(null);
        }

        public String kid() {
            return header.path("kid").asText(null);
        }
    }

    public static Parsed parse(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a compact JWS (expected 3 parts)");
        }
        try {
            JsonNode header = JSON.readTree(B64D.decode(parts[0]));
            JsonNode claims = JSON.readTree(B64D.decode(parts[1]));
            byte[] signature = parts[2].isEmpty() ? new byte[0] : B64D.decode(parts[2]);
            return new Parsed(header, claims, parts[0] + "." + parts[1], signature);
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed JWS: " + e.getMessage(), e);
        }
    }

    public static boolean verifyEdDsa(Parsed parsed, Ed25519PublicKeyParameters key) {
        if (parsed.signature().length == 0) {
            return false;
        }
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, key);
        byte[] input = parsed.signingInput().getBytes(StandardCharsets.US_ASCII);
        verifier.update(input, 0, input.length);
        return verifier.verifySignature(parsed.signature());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
