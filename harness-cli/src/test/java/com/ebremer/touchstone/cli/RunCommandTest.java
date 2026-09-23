package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.ebremer.touchstone.fixtures.oidc.OidcIssuer;
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
                    .contains("24 passed, 0 failed, 0 errors, 0 skipped")
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
            // The bundle is six renderings plus the raw record; a format that stops being
            // written should fail here rather than be noticed by its absence months later.
            assertThat(runDir.resolve("report.json")).exists();
            assertThat(runDir.resolve("report.md")).exists();
            assertThat(runDir.resolve("report.pdf")).exists();
            assertThat(runDir.getFileName().toString())
                    .as("run directory is stamped <timestamp>-<runId>, with no character a filesystem rejects")
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{6}Z-.+");

            // EARL parses and holds one assertion per test
            org.apache.jena.rdf.model.Model earl = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            org.apache.jena.riot.RDFDataMgr.read(earl, runDir.resolve("earl.ttl").toUri().toString());
            assertThat(earl.listResourcesWithProperty(
                    org.apache.jena.vocabulary.RDF.type,
                    earl.createResource("http://www.w3.org/ns/earl#Assertion")).toList()).hasSize(24);

            // HTML matrix links tests -> requirements -> spec sections
            String html = Files.readString(runDir.resolve("report.html"));
            assertThat(html)
                    .contains("https://www.w3.org/TR/lws10-core/#")
                    .contains("id=\"t-core-container-containment-after-post\"")
                    .contains("href=\"#r-create-post-201-location-links\"")
                    .contains("No MUST-level failures");

            String junit = Files.readString(runDir.resolve("junit.xml"));
            assertThat(junit).contains("tests=\"24\"").contains("failures=\"0\"");
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

            // --report-dir is given rather than defaulted. Without it the bundle went to `runs`
            // relative to the working directory — harness-cli/ — so every test run left one
            // behind for good, and 31 had accumulated before anyone looked.
            Path reports = tmp.resolve("runs");
            int exit = cmd.execute("run",
                    "--target", "ref",
                    "--targets", targets.toString(),
                    "--manifests", manifests.toString(),
                    "--module", "core",
                    "--report-dir", reports.toString());

            assertThat(exit).isEqualTo(1);
            assertThat(out.toString())
                    .contains("0 passed, 1 failed")
                    .contains("expected: [418]")
                    .contains("actual:   200");
            // A run that fails still owes its evidence: the bundle is how anyone finds out what
            // the server actually said. Nothing else covers the reporting path for a failed run.
            try (var dirs = Files.list(reports)) {
                Path runDir = dirs.findFirst().orElseThrow();
                assertThat(runDir.resolve("run.json")).exists();
                assertThat(runDir.resolve("earl.ttl")).exists();
                assertThat(runDir.resolve("report.md")).exists();
            }
        }
    }

    @Test
    void aRequirementIriTheCatalogDoesNotHoldRefusesTheRunAsAConfigurationError() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path targets = targetsFile(server);
            Path manifests = tmp.resolve("manifests");
            Files.createDirectories(manifests.resolve("core"));
            Files.writeString(manifests.resolve("core").resolve("dangling-requirement.yaml"), """
                    schemaVersion: 1
                    id: core/dangling-requirement
                    title: declares a requirement that is not in the catalog
                    requirements: [https://example.org/touchstone/req/lws10-core/no-such-requirement]
                    steps:
                      - request:
                          method: GET
                          target: "${test.container}"
                          headers: { Accept: application/lws+json }
                        expect:
                          status: 200
                    """);
            StringWriter out = new StringWriter();
            CommandLine cmd = new CommandLine(new TouchstoneCli());
            cmd.setOut(new PrintWriter(out));
            cmd.setErr(new PrintWriter(out));

            int exit = cmd.execute("run",
                    "--target", "ref",
                    "--targets", targets.toString(),
                    "--manifests", manifests.toString(),
                    "--module", "core",
                    "--catalog", "../catalog",
                    "--report-dir", tmp.resolve("runs").toString());

            // 2, not 1: the target was never asked anything, so this is the harness being
            // misconfigured, not the server being non-conformant.
            assertThat(exit).isEqualTo(2);
            assertThat(out.toString())
                    .contains("core/dangling-requirement -> "
                            + "https://example.org/touchstone/req/lws10-core/no-such-requirement");
            assertThat(tmp.resolve("runs")).doesNotExist();
        }
    }

    // Exit code 1 is a verdict: the server was tested and did not conform. Each case below
    // stops before any test runs, so none of them may exit 1. Otherwise CI would blame the
    // server for a broken workflow (D-0046, D-0048). They say why on one line, not in a stack
    // trace.

    @Test
    void aManifestTheSchemaRejectsIsAConfigurationError() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path manifests = tmp.resolve("manifests");
            Files.createDirectories(manifests.resolve("core"));
            Files.writeString(manifests.resolve("core").resolve("misspelt.yaml"), """
                    schemaVersion: 1
                    id: core/misspelt
                    title: a misspelt key the schema must reject
                    requirements: [https://example.org/touchstone/req/lws10-core/head-parity-with-get]
                    steps:
                      - request: { method: GET, target: "${test.container}" }
                        expekt: { status: 200 }
                    """);
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server), manifests);

            assertThat(exit).isEqualTo(2);
            assertThat(out.toString()).contains("violates schema").contains("expekt");
            assertNoStackTraceAndNoReports(out);
        }
    }

    @Test
    void anUnreachableTargetIsAConfigurationError() throws Exception {
        // The address of a server that has stopped: nothing listens there any more. Read it
        // before closing, since a stopped connector reports no port.
        URI gone;
        try (RefLwsServer stopped = RefLwsServer.start(0)) {
            gone = stopped.baseUri();
        }
        Path targets = targetsFile(gone);
        StringWriter out = new StringWriter();

        int exit = run(out, targets, Path.of("../manifests"));

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString())
                .contains("cannot run against target 'ref'")
                .contains("cannot create container");
        assertNoStackTraceAndNoReports(out);
    }

    @Test
    void aTargetThatRefusesTheRunRootIsAConfigurationError() throws Exception {
        // The commonest real case: a protected storage, and no identity configured to write to it.
        try (OidcIssuer issuer = OidcIssuer.start(0);
             RefLwsServer server = RefLwsServer.startSecured(0, issuer)) {
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server), Path.of("../manifests"));

            assertThat(exit).isEqualTo(2);
            assertThat(out.toString())
                    .contains("cannot run against target 'ref'")
                    .contains("returned 401 (expected 201)");
            assertNoStackTraceAndNoReports(out);
        }
    }

    private int run(StringWriter out, Path targets, Path manifests) {
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(out));
        return cmd.execute("run",
                "--target", "ref",
                "--targets", targets.toString(),
                "--manifests", manifests.toString(),
                "--module", "core",
                "--catalog", "../catalog",
                "--report-dir", tmp.resolve("runs").toString());
    }

    private void assertNoStackTraceAndNoReports(StringWriter out) {
        // A stack frame prints as a tab and "at ", whatever ANSI styling picocli wraps it in.
        assertThat(out.toString()).as("a stack trace").doesNotContain("\tat ");
        assertThat(tmp.resolve("runs")).doesNotExist();
    }

    private Path targetsFile(RefLwsServer server) throws Exception {
        return targetsFile(server.baseUri());
    }

    private Path targetsFile(URI baseUrl) throws Exception {
        Path targets = tmp.resolve("targets.yaml");
        Files.writeString(targets, """
                targets:
                  ref:
                    baseUrl: %s
                    adapter: env
                """.formatted(baseUrl));
        return targets;
    }
}
