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
        URI runRoot = Containers.create(http, target.baseUrl(), "touchstone-run-" + runId);
        return new RunContext(target, runId, runRoot, identity -> resolve(target, identity), http);
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
