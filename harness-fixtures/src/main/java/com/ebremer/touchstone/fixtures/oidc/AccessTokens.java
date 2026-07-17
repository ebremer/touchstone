package com.ebremer.touchstone.fixtures.oidc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Mints RFC 9068 access tokens for the negative matrix (DESIGN.md paragraph 5.4). A valid
 * token is signed by the issuer's current key with the subject, the storage realm as
 * audience, and the issuer as {@code iss}; each broken variant corrupts exactly one of
 * those so a failing conformance test names a single cause.
 */
public final class AccessTokens {

    /** The RFC 9068 access-token JOSE type. */
    private static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

    private final OidcIssuer issuer;
    private final String realm;

    /**
     * @param issuer the authorization server minting tokens
     * @param realm  the storage's audience (its base URI) — the {@code aud} a valid token carries
     */
    public AccessTokens(OidcIssuer issuer, String realm) {
        this.issuer = issuer;
        this.realm = realm;
    }

    public String valid(String subject) {
        return sign(base(subject).build(), issuer.currentKey());
    }

    public String expired(String subject) {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        return sign(base(subject)
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(5, ChronoUnit.MINUTES)))
                .build(), issuer.currentKey());
    }

    public String notYetValid(String subject) {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        return sign(base(subject).notBeforeTime(Date.from(future)).build(), issuer.currentKey());
    }

    public String wrongAudience(String subject) {
        return sign(claims(subject).audience("https://not-this-storage.example/").build(), issuer.currentKey());
    }

    public String wrongIssuer(String subject) {
        return sign(claims(subject).issuer("https://evil-issuer.example/").build(), issuer.currentKey());
    }

    /** Signed by a foreign key whose kid claims the real one — signature will not verify. */
    public String badSignature(String subject) {
        RSAKey foreign = foreignKeyWithKid(issuer.currentKey().getKeyID());
        return sign(base(subject).build(), foreign);
    }

    /** Signed by a key whose kid is not in the published JWKS. */
    public String unknownKey(String subject) {
        return sign(base(subject).build(), foreignKeyWithKid("unpublished-kid"));
    }

    /** Unsigned token (alg=none) — must be rejected. */
    public String algNone(String subject) {
        return new PlainJWT(base(subject).build()).serialize();
    }

    public String missingSubject() {
        return sign(claims(null).build(), issuer.currentKey());
    }

    private JWTClaimsSet.Builder base(String subject) {
        return claims(subject).audience(realm);
    }

    private JWTClaimsSet.Builder claims(String subject) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(issuer.issuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .jwtID(UUID.randomUUID().toString())
                .claim("client_id", "https://client.example/app");
        if (subject != null) {
            b.subject(subject);
        }
        return b;
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(AT_JWT).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign token", e);
        }
    }

    private static RSAKey foreignKeyWithKid(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate foreign key", e);
        }
    }
}
