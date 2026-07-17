package com.ebremer.touchstone.fixtures.lws;

import java.net.URL;
import java.util.Set;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Validates RFC 9068 access tokens the way a compliant LWS storage server must
 * (core WD authorization §): RS256 only (so {@code alg=none} is rejected), signature
 * verified against the authorization server's {@code jwks_uri}, and {@code iss} /
 * {@code aud} / {@code exp} / {@code nbf} checked — {@code aud} must contain the storage
 * realm. Returns the authenticated subject, or throws {@link InvalidTokenException}.
 *
 * Client-side JWK caching is disabled so a mid-session key rotation at the issuer is
 * observed immediately (the rotation negative case); the spec's caching guidance is a
 * SHOULD and not what these tests exercise.
 */
public final class TokenValidator {

    /** A token that fails any validation check — the storage must answer 401. */
    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final String realm;

    public TokenValidator(String issuer, URL jwksUri, String realm) {
        this.realm = realm;
        JWKSource<SecurityContext> jwks = JWKSourceBuilder.<SecurityContext>create(jwksUri)
                .cache(false)
                .refreshAheadCache(false)
                .rateLimited(false)
                .build();
        this.processor = new DefaultJWTProcessor<>();
        // RFC 9068 access tokens carry typ=at+jwt; the default verifier only allows JWT.
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                new JOSEObjectType("at+jwt"), JOSEObjectType.JWT, null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                realm,
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("sub", "iss", "aud", "exp")));
    }

    /** @return the validated subject (sub claim) */
    public String validate(String bearerToken) {
        try {
            JWTClaimsSet claims = processor.process(bearerToken, null);
            return claims.getSubject();
        } catch (Exception e) {
            throw new InvalidTokenException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public String realm() {
        return realm;
    }
}
