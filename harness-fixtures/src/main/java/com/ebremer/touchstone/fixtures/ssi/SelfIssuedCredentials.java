package com.ebremer.touchstone.fixtures.ssi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Mints self-issued authentication credentials (did:key and CID suites) — a JWT the
 * subject signs about itself, with {@code sub = iss = client_id}. Provides a valid
 * credential and every one-fault-each broken variant the negative matrix needs.
 */
public final class SelfIssuedCredentials {

    private final String authorizationServer;

    public SelfIssuedCredentials(String authorizationServer) {
        this.authorizationServer = authorizationServer;
    }

    /** A valid credential: subject signs over its own did:key / CID identifier. */
    public String valid(String subject, String kid, DidKey key) {
        return SelfIssuedJwt.signEdDsa(kid, claims(subject, subject, subject, authorizationServer,
                Instant.now().plus(5, ChronoUnit.MINUTES)), key.privateKey());
    }

    /** alg=none — a verifier must reject it. */
    public String algNone(String subject, String kid) {
        return SelfIssuedJwt.unsecured(kid, claims(subject, subject, subject, authorizationServer,
                Instant.now().plus(5, ChronoUnit.MINUTES)));
    }

    /** Signed by a different key than the identifier names — signature will not verify. */
    public String badSignature(String subject, String kid, DidKey foreignKey) {
        return SelfIssuedJwt.signEdDsa(kid, claims(subject, subject, subject, authorizationServer,
                Instant.now().plus(5, ChronoUnit.MINUTES)), foreignKey.privateKey());
    }

    public String expired(String subject, String kid, DidKey key) {
        return SelfIssuedJwt.signEdDsa(kid, claims(subject, subject, subject, authorizationServer,
                Instant.now().minus(5, ChronoUnit.MINUTES)), key.privateKey());
    }

    /** sub, iss, client_id must be the same URI; here iss differs. */
    public String mismatchedClaims(String subject, String kid, DidKey key) {
        return SelfIssuedJwt.signEdDsa(kid, claims(subject, "https://someone-else.example/", subject,
                authorizationServer, Instant.now().plus(5, ChronoUnit.MINUTES)), key.privateKey());
    }

    /** The aud claim must include the authorization server; here it does not. */
    public String wrongAudience(String subject, String kid, DidKey key) {
        return SelfIssuedJwt.signEdDsa(kid, claims(subject, subject, subject,
                "https://not-the-as.example/", Instant.now().plus(5, ChronoUnit.MINUTES)), key.privateKey());
    }

    private static String claims(String sub, String iss, String clientId, String aud, Instant exp) {
        long now = Instant.now().getEpochSecond();
        return "{"
                + "\"sub\":" + q(sub) + ","
                + "\"iss\":" + q(iss) + ","
                + "\"client_id\":" + q(clientId) + ","
                + "\"aud\":[" + q(aud) + "],"
                + "\"iat\":" + now + ","
                + "\"exp\":" + exp.getEpochSecond()
                + "}";
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
