package com.ebremer.touchstone.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import com.ebremer.touchstone.fixtures.ReferenceScenario;
import com.ebremer.touchstone.fixtures.as.RefAuthorizationServer;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/** `touchstone run` over the YAML-LD definitions, end to end: selection, verdict, exit code, reports. */
class RunCommandTest {

    @TempDir
    Path tmp;

    @Test
    void everyDefinitionRunsAgainstTheReferenceDeploymentAndEmitsAllReports() throws Exception {
        try (ReferenceScenario scenario = ReferenceScenario.start(ReferenceScenario.Kind.SECURED)) {
            Path reports = tmp.resolve("runs");
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(scenario), Path.of("../definitions"), "all");

            assertThat(out.toString())
                    .contains("100 passed, 0 failed, 0 cantTell, 1 inapplicable")
                    .contains("conformant: no MUST test failed or ended cantTell")
                    .contains("[passed      ] MUST   core/containers#getContainer")
                    .contains("[inapplicable] MUST   core/notifications#notification-service-advertised");
            assertThat(exit).isZero();

            Path runDir;
            try (var dirs = Files.list(reports)) {
                runDir = dirs.findFirst().orElseThrow();
            }
            // The bundle is six renderings plus the raw record; a format that stops being
            // written should fail here rather than be noticed by its absence months later.
            for (String file : new String[] {"run.json", "earl.ttl", "junit.xml", "report.html", "report.json",
                    "report.md", "report.pdf"}) {
                assertThat(runDir.resolve(file)).exists();
            }
            assertThat(runDir.getFileName().toString())
                    .as("run directory is stamped <timestamp>-<runId>, with no character a filesystem rejects")
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{6}Z-.+");

            // EARL parses, holds one assertion per test, and names each test by its IRI
            org.apache.jena.rdf.model.Model earl = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            org.apache.jena.riot.RDFDataMgr.read(earl, runDir.resolve("earl.ttl").toUri().toString());
            assertThat(earl.listResourcesWithProperty(
                    org.apache.jena.vocabulary.RDF.type,
                    earl.createResource("http://www.w3.org/ns/earl#Assertion")).toList()).hasSize(101);
            assertThat(earl.containsResource(earl.createResource(
                    "https://example.org/touchstone/test/lws10/core/containers#getContainer"))).isTrue();

            // HTML matrix links tests -> requirements -> spec sections
            String html = Files.readString(runDir.resolve("report.html"));
            assertThat(html)
                    .contains("https://www.w3.org/TR/")
                    .contains("id=\"t-core-containers-getContainer\"")
                    .contains("CONFORMANT &mdash; no MUST test failed");

            String junit = Files.readString(runDir.resolve("junit.xml"));
            assertThat(junit).contains("tests=\"101\"").contains("failures=\"0\"")
                    .contains("classname=\"core/containers\" name=\"getContainer\"");
        }
    }

    @Test
    void aFailedMustTestYieldsExitCodeOneAndDetails() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path definitions = copyDefinitions();
            edit(definitions.resolve("lws10/core/containers.yamlld"),
                    "      statusCode: 200\n      contentType: application/lws+json\n      otherHeaders:\n"
                            + "        - headerName: ETag",
                    "      statusCode: 418\n      contentType: application/lws+json\n      otherHeaders:\n"
                            + "        - headerName: ETag");
            StringWriter out = new StringWriter();
            Path reports = tmp.resolve("runs");

            int exit = run(out, targetsFile(server.baseUri(), Map.of()), definitions, "getContainer");

            assertThat(exit).isEqualTo(1);
            assertThat(out.toString())
                    .contains("0 passed, 1 failed")
                    .contains("NOT conformant: 1 MUST test(s)")
                    .contains("expected: 418")
                    .contains("actual:   200");
            // A run that fails still owes its evidence: the bundle is how anyone finds out what
            // the server actually said.
            try (var dirs = Files.list(reports)) {
                Path runDir = dirs.findFirst().orElseThrow();
                assertThat(runDir.resolve("run.json")).exists();
                assertThat(runDir.resolve("earl.ttl")).exists();
                assertThat(runDir.resolve("report.md")).exists();
            }
        }
    }

    @Test
    void aFailedShouldTestIsAdvisoryAndExitsZero() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path definitions = copyDefinitions();
            edit(definitions.resolve("lws10/core/containers.yamlld"),
                    "headerName: Vary", "headerName: X-Touchstone-Never-Sent");
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server.baseUri(), Map.of()), definitions, "container-conneg-vary");

            assertThat(out.toString())
                    .contains("0 passed, 1 failed")
                    .contains("[failed      ] SHOULD core/containers#container-conneg-vary")
                    .contains("conformant: no MUST test failed or ended cantTell (1 SHOULD or MAY test(s) failed");
            assertThat(exit).as("SHOULD failures are advisory (EXECUTION.md section 9)").isZero();
        }
    }

    @Test
    void aRequirementIriTheCatalogDoesNotHoldRefusesTheRunAsAConfigurationError() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path definitions = copyDefinitions();
            edit(definitions.resolve("lws10/core/containers.yamlld"),
                    "https://example.org/touchstone/req/lws10-core/read-container-etag-type-links",
                    "https://example.org/touchstone/req/lws10-core/no-such-requirement");
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server.baseUri(), Map.of()), definitions, "all");

            // 2, not 1: the target was never asked anything, so this is the harness being
            // misconfigured, not the server being non-conformant.
            assertThat(exit).isEqualTo(2);
            assertThat(out.toString()).contains("requirement https://example.org/touchstone/req/lws10-core/"
                    + "no-such-requirement is not in the catalog");
            assertNoStackTraceAndNoReports(out);
        }
    }

    // Exit code 1 is a verdict: the server was tested and did not conform. Each case below
    // stops before any test runs, so none of them may exit 1. Otherwise CI would blame the
    // server for a broken workflow (D-0046, D-0048). They say why on one line, not in a stack
    // trace.

    @Test
    void aDefinitionTheSchemaRejectsIsAConfigurationError() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            Path definitions = copyDefinitions();
            edit(definitions.resolve("lws10/core/discovery.yamlld"), "    level: MUST", "    levle: MUST");
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server.baseUri(), Map.of()), definitions, "all");

            assertThat(exit).isEqualTo(2);
            assertThat(out.toString()).contains("violates the definitions schema").contains("levle");
            assertNoStackTraceAndNoReports(out);
        }
    }

    @Test
    void aSelectorThatMatchesNothingIsAConfigurationError() throws Exception {
        try (RefLwsServer server = RefLwsServer.start(0)) {
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server.baseUri(), Map.of()), Path.of("../definitions"), "core/nothing");

            assertThat(exit).isEqualTo(2);
            assertThat(out.toString()).contains("no test matches 'core/nothing'").contains("core/containers");
            assertNoStackTraceAndNoReports(out);
        }
    }

    @Test
    void aRegistryThatDoesNotParseIsAConfigurationError() throws Exception {
        Path targets = tmp.resolve("targets.yaml");
        Files.writeString(targets, "targets:\n  ref:\n    baseUrl: http://localhost:4711/\n'stray'\n    adapter: env\n");
        StringWriter out = new StringWriter();

        int exit = run(out, targets, Path.of("../definitions"), "core");

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).contains("cannot read the target registry");
        assertNoStackTraceAndNoReports(out);
    }

    @Test
    void anUnreachableTargetIsAConfigurationError() throws Exception {
        // The address of a server that has stopped: nothing listens there any more. Read it
        // before closing, since a stopped connector reports no port.
        URI gone;
        try (RefLwsServer stopped = RefLwsServer.start(0)) {
            gone = stopped.baseUri();
        }
        StringWriter out = new StringWriter();

        int exit = run(out, targetsFile(gone, Map.of()), Path.of("../definitions"), "core");

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString())
                .contains("cannot run against target 'ref'")
                .contains("cannot create the run root");
        assertNoStackTraceAndNoReports(out);
    }

    @Test
    void aTargetThatRefusesTheRunRootIsAConfigurationError() throws Exception {
        // The commonest real case: a protected storage, and no credential configured for alice.
        try (RefAuthorizationServer as = RefAuthorizationServer.start(0);
             RefLwsServer server = RefLwsServer.startSecured(0, as, "https://alice.touchstone.test/")) {
            StringWriter out = new StringWriter();

            int exit = run(out, targetsFile(server.baseUri(), Map.of()), Path.of("../definitions"), "core");

            assertThat(exit).isEqualTo(2);
            assertThat(out.toString())
                    .contains("cannot run against target 'ref'")
                    .contains("as alice answered 401");
            assertNoStackTraceAndNoReports(out);
        }
    }

    private int run(StringWriter out, Path targets, Path definitions, String selector) {
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(out));
        return cmd.execute("run",
                "--target", "ref",
                "--targets", targets.toString(),
                "--definitions", definitions.toString(),
                "--module", selector,
                "--catalog", "../catalog",
                "--report-dir", tmp.resolve("runs").toString());
    }

    private void assertNoStackTraceAndNoReports(StringWriter out) {
        // A stack frame prints as a tab and "at ", whatever ANSI styling picocli wraps it in.
        assertThat(out.toString()).as("a stack trace").doesNotContain("\tat ");
        assertThat(tmp.resolve("runs")).doesNotExist();
    }

    private Path targetsFile(ReferenceScenario scenario) throws IOException {
        return targetsFile(scenario.storageBaseUri(), scenario.properties(),
                String.join(", ", scenario.capabilities()));
    }

    private Path targetsFile(URI baseUrl, Map<String, String> properties) throws IOException {
        return targetsFile(baseUrl, properties, "");
    }

    private Path targetsFile(URI baseUrl, Map<String, String> properties, String capabilities) throws IOException {
        StringBuilder yaml = new StringBuilder("targets:\n  ref:\n    baseUrl: " + baseUrl + "\n    adapter: env\n"
                + "    capabilities: [" + capabilities + "]\n    properties:\n");
        properties.forEach((k, v) -> yaml.append("      ").append(k).append(": '")
                .append(v.replace("'", "''")).append("'\n"));
        if (properties.isEmpty()) {
            yaml.append("      {}\n");
        }
        Path targets = tmp.resolve("targets.yaml");
        Files.writeString(targets, yaml.toString().replace("    properties:\n      {}\n", "    properties: {}\n"));
        return targets;
    }

    /** A private copy of the definitions, for a test that breaks one on purpose. */
    private Path copyDefinitions() throws IOException {
        Path source = Path.of("..", "definitions");
        Path target = tmp.resolve("definitions");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        return target;
    }

    private static void edit(Path file, String from, String to) throws IOException {
        String s = Files.readString(file);
        int i = s.indexOf(from);
        assertThat(i).as("text to edit in " + file).isNotNegative();
        Files.writeString(file, s.substring(0, i) + to + s.substring(i + from.length()));
    }
}
