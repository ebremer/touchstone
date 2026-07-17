package com.ebremer.touchstone.fixtures.lws;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ebremer.touchstone.core.exec.Executor;
import com.ebremer.touchstone.core.exec.ProvisioningAdapters;
import com.ebremer.touchstone.core.exec.RunContext;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.TestResult;
import com.ebremer.touchstone.fixtures.oidc.AccessTokens;
import com.ebremer.touchstone.fixtures.oidc.OidcIssuer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 acceptance (DESIGN.md paragraph 9): the auth-oidc negative matrix must
 * demonstrably distinguish a compliant reference from a deliberately broken stub.
 * The manifests assert the correct secured behaviour, so they all PASS against the
 * SECURED reference server and the negative ones FAIL against the BROKEN twin.
 */
class OidcNegativeMatrixTest {

    private static final Path AUTH_OIDC = Path.of("..", "manifests", "auth-oidc");

    @Test
    void allPassAgainstTheCompliantSecuredServer() {
        try (OidcIssuer issuer = OidcIssuer.start(0);
             RefLwsServer secured = RefLwsServer.startSecured(0, issuer)) {
            Map<String, Outcome> outcomes = run(secured, issuer);

            assertThat(outcomes.values()).as(outcomes.toString()).containsOnly(Outcome.PASSED);
            assertThat(outcomes).containsKey("auth-oidc/anonymous-request-401-challenge");
            assertThat(outcomes).hasSize(9);
        }
    }

    @Test
    void negativeTestsFailAgainstTheBrokenTwin() {
        try (OidcIssuer issuer = OidcIssuer.start(0);
             RefLwsServer broken = RefLwsServer.startBroken(0)) {
            Map<String, Outcome> outcomes = run(broken, issuer);

            // the deliberately broken server never challenges or forbids: the one positive
            // test still passes, but every negative test fails — the distinction the brief wants.
            assertThat(outcomes.get("auth-oidc/valid-token-access")).isEqualTo(Outcome.PASSED);
            Map<String, Outcome> negatives = new LinkedHashMap<>(outcomes);
            negatives.remove("auth-oidc/valid-token-access");
            assertThat(negatives).as("every negative test must fail against the broken twin")
                    .hasSize(8)
                    .allSatisfy((id, outcome) -> assertThat(outcome).as(id).isEqualTo(Outcome.FAILED));
        }
    }

    /** Provisions a run against {@code storage}, minting every identity's token from {@code issuer}. */
    private static Map<String, Outcome> run(RefLwsServer storage, OidcIssuer issuer) {
        AccessTokens tokens = new AccessTokens(issuer, storage.baseUri().toString());
        Map<String, String> props = new LinkedHashMap<>();
        props.put("provisioner", "alice");
        props.put("token.alice", tokens.valid("alice"));
        props.put("token.bob", tokens.valid("bob"));
        props.put("token.alice-expired", tokens.expired("alice"));
        props.put("token.alice-wrong-audience", tokens.wrongAudience("alice"));
        props.put("token.alice-wrong-issuer", tokens.wrongIssuer("alice"));
        props.put("token.alice-bad-signature", tokens.badSignature("alice"));
        props.put("token.alice-unknown-key", tokens.unknownKey("alice"));
        props.put("token.alice-alg-none", tokens.algNone("alice"));

        Target target = new Target("secured-ref", storage.baseUri(), "env", props,
                java.util.Set.of("authentication"));

        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        try (RunContext ctx = ProvisioningAdapters.forTarget(target)
                .provision(target, UUID.randomUUID().toString().substring(0, 8))) {
            for (Manifest manifest : ManifestLoader.loadDirectory(AUTH_OIDC)) {
                TestResult result = Executor.execute(manifest, ctx);
                outcomes.put(manifest.id(), result.outcome());
            }
        }
        return outcomes;
    }
}
