package com.ebremer.touchstone.clients;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.ebremer.touchstone.core.engine.SamlAssertions;

/**
 * A session's SAML 2.0 identity provider (lws10-authn-saml; DECISIONS.md D-0087). It has no
 * endpoint: the suite leaves how a client obtains an assertion open, and trust in the provider to
 * configuration, so the session API hands out its assertions about alice and bob, as it hands out
 * tokens, and the session's authorization server trusts its key in process.
 *
 * <p>Its assertions are the ones harness-core mints for the server self-test
 * ({@link SamlAssertions#issue}), which the reference authorization server, written apart from that
 * code, verifies: the subject in NameID, the provider in Issuer, the client in Recipient, the client
 * and the authorization server in Audience, signed RSA-SHA256 with the key by value.
 *
 * <p>It remembers what it issued, by the SHA-256 of the assertion's XML, so the recorder can tell
 * its assertions in a token request however the client base64-encoded them (OBSERVATION.md
 * section 4.9). The key is made on first use: most sessions never ask for an assertion.
 */
final class SamlIdentityProvider {

    /** Assertions remembered at most; the oldest are forgotten first. They are valid for minutes anyway. */
    private static final int MAX_REMEMBERED = 1000;

    private final String entityId;
    private final Consumer<java.security.PublicKey> trust;
    private final Set<String> issued = new LinkedHashSet<>();
    private KeyPair key;

    /**
     * @param entityId the provider's entity identifier, the Issuer of its assertions
     * @param trust told the provider's public key when it is made, so the authorization server trusts it
     */
    SamlIdentityProvider(String entityId, Consumer<java.security.PublicKey> trust) {
        this.entityId = entityId;
        this.trust = trust;
    }

    String entityId() {
        return entityId;
    }

    /**
     * A signed assertion about {@code subject}, base64url-encoded (RFC 8693 section 3).
     *
     * @param recipient the client identifier
     * @param audience the authorization server it is for; the client is an audience too
     */
    synchronized String issue(String subject, String recipient, String audience, Duration lifetime) {
        KeyPair k = key();
        Instant now = Instant.now();
        String assertion = SamlAssertions.issue(entityId, subject, recipient, List.of(recipient, audience), now,
                now.plus(lifetime), k.getPrivate(), k.getPublic());
        issued.add(digest(Base64.getUrlDecoder().decode(assertion)));
        while (issued.size() > MAX_REMEMBERED) {
            issued.remove(issued.iterator().next());
        }
        return assertion;
    }

    /**
     * Whether {@code token} is an assertion this provider issued: base64url or base64, with or
     * without padding, of the same XML. A token that is not base64 is not one.
     */
    synchronized boolean issued(String token) {
        if (issued.isEmpty() || token == null) {
            return false;
        }
        byte[] xml;
        try {
            xml = Base64.getUrlDecoder().decode(token.strip());
        } catch (IllegalArgumentException e) {
            try {
                xml = Base64.getMimeDecoder().decode(token.strip());
            } catch (IllegalArgumentException again) {
                return false;
            }
        }
        return issued.contains(digest(xml));
    }

    private KeyPair key() {
        if (key == null) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                key = generator.generateKeyPair();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            trust.accept(key.getPublic());
        }
        return key;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
