package com.ebremer.touchstone.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.ebremer.touchstone.fixtures.oidc.AccessTokens;
import com.ebremer.touchstone.fixtures.oidc.OidcIssuer;

/**
 * Dev-loop launcher for the secured reference scenario: starts the OIDC issuer and a
 * SECURED reference LWS server, then writes a ready-to-use target registry with freshly
 * minted identity tokens (valid + broken variants) so the CLI auth suite can run against
 * it:
 *
 * <pre>
 * mvn -q -pl harness-fixtures exec:java \
 *   -Dexec.mainClass=com.ebremer.touchstone.fixtures.SecuredRefScenarioMain \
 *   -Dexec.args="targets-secured.yaml"
 * touchstone run --target secured-ref --module auth-oidc --targets targets-secured.yaml
 * </pre>
 *
 * The tokens are static once written; restart to refresh. This is the bundled reference
 * scenario of DECISIONS.md D-0017 — the harness owns the AS, so it can mint broken tokens.
 */
public final class SecuredRefScenarioMain {

    private SecuredRefScenarioMain() {
    }

    public static void main(String[] args) throws Exception {
        Path targetsFile = Path.of(args.length > 0 ? args[0] : "targets-secured.yaml");

        OidcIssuer issuer = OidcIssuer.start(0);
        RefLwsServer storage = RefLwsServer.startSecured(0, issuer);
        AccessTokens tokens = new AccessTokens(issuer, storage.baseUri().toString());

        String yaml = "targets:\n"
                + "  secured-ref:\n"
                + "    baseUrl: " + storage.baseUri() + "\n"
                + "    adapter: env\n"
                + "    capabilities: [authentication]\n"
                + "    properties:\n"
                + "      provisioner: alice\n"
                + prop("token.alice", tokens.valid("alice"))
                + prop("token.bob", tokens.valid("bob"))
                + prop("token.alice-expired", tokens.expired("alice"))
                + prop("token.alice-wrong-audience", tokens.wrongAudience("alice"))
                + prop("token.alice-wrong-issuer", tokens.wrongIssuer("alice"))
                + prop("token.alice-bad-signature", tokens.badSignature("alice"))
                + prop("token.alice-unknown-key", tokens.unknownKey("alice"))
                + prop("token.alice-alg-none", tokens.algNone("alice"));
        Files.writeString(targetsFile, yaml);

        System.out.println("OIDC issuer:        " + issuer.baseUri());
        System.out.println("secured LWS server: " + storage.baseUri());
        System.out.println("target registry:    " + targetsFile.toAbsolutePath());
        System.out.println("run: touchstone run --target secured-ref --module auth-oidc --targets "
                + targetsFile);
        storage.join();
    }

    private static String prop(String key, String token) {
        return "      " + key + ": \"" + token + "\"\n";
    }
}
