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
    void coreSuitePassesAgainstTheReferenceServerAndEmitsAllReports() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path targets = targetsFile(server);
            Path reports = tmp.resolve("runs");
            StringWriter out = new StringWriter();
            CommandLine cmd = new CommandLine(new TouchstoneCli());
            cmd.setOut(new PrintWriter(out));
            cmd.setErr(new PrintWriter(out));

            int exit = cmd.execute("run",
                    "--target", "ref",
                    "--targets", targets.toString(),
                    "--manifests", "../manifests",
                    "--module", "core",
                    "--catalog", "../catalog",
                    "--report-dir", reports.toString());

            assertThat(out.toString())
                    .contains("12 passed, 0 failed, 0 errors, 0 skipped")
                    .doesNotContain("[FAILED]")
                    .doesNotContain("[ERROR ]");
            assertThat(exit).isZero();

            // phase 3 acceptance: one run emits all three formats plus the machine record
            Path runDir;
            try (var dirs = Files.list(reports)) {
                runDir = dirs.findFirst().orElseThrow();
            }
            assertThat(runDir.resolve("run.json")).exists();
            assertThat(runDir.resolve("earl.ttl")).exists();
            assertThat(runDir.resolve("junit.xml")).exists();
            assertThat(runDir.resolve("report.html")).exists();

            // EARL parses and holds one assertion per test
            org.apache.jena.rdf.model.Model earl = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            org.apache.jena.riot.RDFDataMgr.read(earl, runDir.resolve("earl.ttl").toUri().toString());
            assertThat(earl.listResourcesWithProperty(
                    org.apache.jena.vocabulary.RDF.type,
                    earl.createResource("http://www.w3.org/ns/earl#Assertion")).toList()).hasSize(12);

            // HTML matrix links tests -> requirements -> spec sections
            String html = Files.readString(runDir.resolve("report.html"));
            assertThat(html)
                    .contains("https://www.w3.org/TR/lws10-core/#")
                    .contains("id=\"t-core-container-containment-after-post\"")
                    .contains("href=\"#r-create-post-201-location-links\"")
                    .contains("No MUST-level failures");

            String junit = Files.readString(runDir.resolve("junit.xml"));
            assertThat(junit).contains("tests=\"12\"").contains("failures=\"0\"");
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
