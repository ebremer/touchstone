package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Config/env-based provisioning (DESIGN.md paragraph 5.3, the CTH-style minimum):
 * the run root is created via spec-standard container POST, and identities resolve
 * to static bearer tokens from target properties ({@code token.alice}) or the
 * environment ({@code TOUCHSTONE_TOKEN_ALICE}). {@code anonymous} is built in.
 */
public final class EnvConfigProvisioningAdapter implements ProvisioningAdapter {

    @Override
    public String id() {
        return "env";
    }

    @Override
    public RunContext provision(Target target, String runId) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        CredentialResolver credentials = identity -> resolve(target, identity);
        // The provisioner owns run-scoped containers: it creates the run root before any test
        // runs and deletes it at close.
        //
        // It falls back to the target's defaultIdentity (D-0028) before anonymous, and that
        // order is the point. D-0028 taught the EXECUTOR to run undeclared-`as` steps as the
        // target's default identity so the suite could face a locked-down SUT, but provisioning
        // kept its own hardcoded anonymous fallback. The result was a target that looked
        // configured and still could not start: every step would have authenticated correctly,
        // but the run root they all live under was still requested anonymously, so the run died
        // at provisioning with 401 and not one test executed. A target that names an identity to
        // act as means it for the whole run, container included.
        //
        // `provisioner` remains a separate property because the two can legitimately differ —
        // a suite may want the run root owned by one agent and the tests driven by another.
        String provisioner = target.properties().getOrDefault("provisioner",
                target.properties().getOrDefault("defaultIdentity", "anonymous"));
        Map<String, String> provisionHeaders = credentials.headersFor(provisioner);
        URI runRoot = Containers.create(http, target.baseUrl(), "touchstone-run-" + runId, provisionHeaders);
        return new RunContext(target, runId, runRoot, credentials, http, provisionHeaders);
    }

    private static Map<String, String> resolve(Target target, String identity) {
        if ("anonymous".equals(identity)) {
            return Map.of();
        }
        String token = target.properties().get("token." + identity);
        if (token == null) {
            token = System.getenv("TOUCHSTONE_TOKEN_"
                    + identity.toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        if (token == null) {
            throw new ProvisioningException("no credential configured for identity '" + identity
                    + "' on target '" + target.id() + "' (set property token." + identity
                    + " or env TOUCHSTONE_TOKEN_" + identity.toUpperCase(Locale.ROOT).replace('-', '_') + ")");
        }
        return Map.of("Authorization", "Bearer " + token);
    }
}
