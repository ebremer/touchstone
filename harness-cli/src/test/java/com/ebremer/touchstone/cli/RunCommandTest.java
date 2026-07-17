package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 acceptance in test form: touchstone run executes real tests with correct pass/fail. */
class RunCommandTest {

    @TempDir
    Path tmp;

    @Test
    void coreSuitePassesAgainstTheReferenceServer() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path targets = targetsFile(server);
            StringWriter out = new StringWriter();
            CommandLine cmd = new CommandLine(new TouchstoneCli());
            cmd.setOut(new PrintWriter(out));
            cmd.setErr(new PrintWriter(out));

            int exit = cmd.execute("run",
                    "--target", "ref",
                    "--targets", targets.toString(),
                    "--manifests", "../manifests",
                    "--module", "core");

            assertThat(out.toString())
                    .contains("12 passed, 0 failed, 0 errors, 0 skipped")
                    .doesNotContain("[FAILED]")
                    .doesNotContain("[ERROR ]");
            assertThat(exit).isZero();
        }
    }

    @Test
    void aFailingExpectationYieldsExitCodeOneAndDetails() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path targets = targetsFile(server);
            Path manifests = tmp.resolve("manifests");
            Files.createDirectories(manifests.resolve("core"));
            Files.writeString(manifests.resolve("core").resolve("deliberate-fail.yaml"), """
                    schemaVersion: 1
                    id: core/deliberate-fail
                    title: expects a status the server will not return
                    requirements: [https://example.org/touchstone/req/lws10-core/head-parity-with-get]
                    steps:
                      - request:
                          method: GET
                          target: "${test.container}"
                          headers: { Accept: application/lws+json }
                        expect:
                          status: 418
                    """);
            StringWriter out = new StringWriter();
            CommandLine cmd = new CommandLine(new TouchstoneCli());
            cmd.setOut(new PrintWriter(out));
            cmd.setErr(new PrintWriter(out));

            int exit = cmd.execute("run",
                    "--target", "ref",
                    "--targets", targets.toString(),
                    "--manifests", manifests.toString(),
                    "--module", "core");

            assertThat(exit).isEqualTo(1);
            assertThat(out.toString())
                    .contains("0 passed, 1 failed")
                    .contains("expected: [418]")
                    .contains("actual:   200");
        }
    }

    private Path targetsFile(RefLwsServer server) throws Exception {
        Path targets = tmp.resolve("targets.yaml");
        Files.writeString(targets, """
                targets:
                  ref:
                    baseUrl: %s
                    adapter: env
                """.formatted(server.baseUri()));
        return targets;
    }
}
