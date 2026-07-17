package com.ebremer.touchstone.fixtures.ssi;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Negative matrix for the self-signed identity suites (did:key and CID). A conforming
 * verifier accepts a valid self-issued credential and rejects every broken variant — the
 * substance of the two suites' validation clauses. Real Ed25519 crypto throughout.
 */
class SelfIssuedNegativeMatrixTest {

    private static final String AS = "https://as.example/";

    @Test
    void didKeyRoundTripAndEncoding() {
        DidKey key = DidKey.generate();
        assertThat(key.did()).startsWith("did:key:z");
        // the public key recovered from the identifier equals the original
        assertThat(DidKey.publicKeyFromDid(key.did()).getEncoded())
                .isEqualTo(key.publicKey().getEncoded());
    }

    @Test
    void didKeyValidCredentialVerifiesAndBrokenVariantsAreRejected() {
        DidKey alice = DidKey.generate();
        DidKey foreign = DidKey.generate();
        SelfIssuedCredentials creds = new SelfIssuedCredentials(AS);
        SelfIssuedVerifier verifier = new SelfIssuedVerifier(AS);
        String sub = alice.did();
        String kid = alice.verificationMethodId();

        assertThat(verifier.verifyDidKey(creds.valid(sub, kid, alice))).isEqualTo(sub);

        assertReject(() -> verifier.verifyDidKey(creds.algNone(sub, kid)), "none");
        assertReject(() -> verifier.verifyDidKey(creds.badSignature(sub, kid, foreign)), "signature");
        assertReject(() -> verifier.verifyDidKey(creds.expired(sub, kid, alice)), "expired");
        assertReject(() -> verifier.verifyDidKey(creds.mismatchedClaims(sub, kid, alice)), "same URI");
        assertReject(() -> verifier.verifyDidKey(creds.wrongAudience(sub, kid, alice)), "authorization server");
    }

    @Test
    void cidValidCredentialVerifiesAndBrokenVariantsAreRejected() throws Exception {
        DidKey alice = DidKey.generate();
        DidKey foreign = DidKey.generate();
        try (IdentityDocumentHost host = IdentityDocumentHost.start(0);
             HttpClient http = HttpClient.newHttpClient()) {
            String sub = host.hostControlledIdentifier("alice/whoami", alice);
            String kid = IdentityDocumentHost.verificationMethodId(sub);
            SelfIssuedCredentials creds = new SelfIssuedCredentials(AS);
            SelfIssuedVerifier verifier = new SelfIssuedVerifier(AS);

            // valid: the credential's key matches the one named in the hosted CID document
            assertThat(verifier.verifyCid(creds.valid(sub, kid, alice), http)).isEqualTo(sub);

            assertReject(() -> verifier.verifyCid(creds.algNone(sub, kid), http), "none");
            // signed by a key the CID document does not name
            assertReject(() -> verifier.verifyCid(creds.badSignature(sub, kid, foreign), http), "signature");
            assertReject(() -> verifier.verifyCid(creds.expired(sub, kid, alice), http), "expired");
            assertReject(() -> verifier.verifyCid(creds.mismatchedClaims(sub, kid, alice), http), "same URI");
            assertReject(() -> verifier.verifyCid(creds.wrongAudience(sub, kid, alice), http), "authorization server");
        }
    }

    private static void assertReject(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call)
                .isInstanceOf(SelfIssuedVerifier.InvalidCredentialException.class)
                .hasMessageContaining(reason);
    }
}
