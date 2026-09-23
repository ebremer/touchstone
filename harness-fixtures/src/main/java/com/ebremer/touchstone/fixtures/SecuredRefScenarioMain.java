package com.ebremer.touchstone.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Dev-loop launcher for the secured reference scenario ({@link ReferenceScenario.Kind#SECURED}):
 * starts the reference authorization server and a secured reference storage, then writes a
 * target registry that lets the CLI run every definition against them:
 *
 * <pre>
 * mvn -q -pl harness-fixtures exec:java \
 *   -Dexec.mainClass=com.ebremer.touchstone.fixtures.SecuredRefScenarioMain \
 *   -Dexec.args="targets-secured.yaml"
 * touchstone run --target secured-ref --targets targets-secured.yaml
 * </pre>
 *
 * The registry holds the authorization server's signing key and the harness identity
 * provider's key, generated for this process: throwaway keys for a local server, which is why
 * the file is git-ignored. Restart to get fresh ones.
 */
public final class SecuredRefScenarioMain {

    private SecuredRefScenarioMain() {
    }

    public static void main(String[] args) throws Exception {
        Path targetsFile = Path.of(args.length > 0 ? args[0] : "targets-secured.yaml");
        ReferenceScenario scenario = ReferenceScenario.start(ReferenceScenario.Kind.SECURED);

        StringBuilder yaml = new StringBuilder("targets:\n  secured-ref:\n")
                .append("    baseUrl: ").append(scenario.storageBaseUri()).append('\n')
                .append("    adapter: env\n")
                .append("    capabilities: [").append(String.join(", ", scenario.capabilities())).append("]\n")
                .append("    properties:\n");
        for (Map.Entry<String, String> p : scenario.properties().entrySet()) {
            yaml.append("      ").append(p.getKey()).append(": '")
                    .append(p.getValue().replace("'", "''")).append("'\n");
        }
        Files.writeString(targetsFile, yaml);

        System.out.println("authorization server: " + scenario.authorizationServer().baseUri());
        System.out.println("secured LWS server:   " + scenario.storageBaseUri());
        System.out.println("target registry:      " + targetsFile.toAbsolutePath());
        System.out.println("run: touchstone run --target secured-ref --targets " + targetsFile);
        scenario.storage().join();
    }
}
