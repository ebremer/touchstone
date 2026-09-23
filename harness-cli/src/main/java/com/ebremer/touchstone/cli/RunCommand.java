package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.InvalidDefinitionsException;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.engine.Engine;
import com.ebremer.touchstone.core.exec.ProvisioningException;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.exec.TargetRegistry;
import com.ebremer.touchstone.core.report.Reports;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        exitCodeOnExecutionException = TouchstoneCli.HARNESS_ERROR,
        description = "Run the YAML-LD test definitions against a pre-registered target and emit reports.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:conformant: no MUST test failed or ended cantTell (SHOULD and MAY failures are advisory)",
                "1:not conformant: a MUST test failed or ended cantTell",
                "2:no verdict: the harness is misconfigured, or could not run against the target"})
final class RunCommand implements Callable<Integer> {

    @Option(
            names = {"-t", "--target"},
            required = true,
            description = "Target id from the registry, never a URL (DESIGN.md section 7.1).")
    private String targetId;

    @Option(
            names = {"-m", "--module"},
            defaultValue = "all",
            description = "What to run: all, a module (core, auth), a manifest (core/containers, auth/oidc),"
                    + " or one test (core/containers#getContainer, or its name). Default: ${DEFAULT-VALUE}.")
    private String selector;

    @Option(
            names = "--definitions",
            defaultValue = "definitions",
            description = "The definitions directory, holding lws10/ and schema/ (default: ${DEFAULT-VALUE}).")
    private Path definitionsDir;

    @Option(
            names = "--targets",
            defaultValue = "targets.yaml",
            description = "Target registry file (default: ${DEFAULT-VALUE}).")
    private Path targetsFile;

    @Option(
            names = {"-c", "--catalog"},
            defaultValue = "catalog",
            description = "Requirements catalog directory (default: ${DEFAULT-VALUE}).")
    private Path catalogDir;

    @Option(
            names = "--report-dir",
            defaultValue = "runs",
            description = "Directory receiving runs/<stamp>-<runId>/{run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml} (default: ${DEFAULT-VALUE}).")
    private Path reportDir;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        if (!Files.isRegularFile(targetsFile)) {
            err.println("target registry not found: " + targetsFile);
            return TouchstoneCli.HARNESS_ERROR;
        }
        TargetRegistry registry = TargetRegistry.load(targetsFile);
        Target target = registry.find(targetId).orElse(null);
        if (target == null) {
            err.println("unknown target '" + targetId + "' (registered: " + registry.ids() + ")");
            return TouchstoneCli.HARNESS_ERROR;
        }

        // The catalog is loaded before the definitions, because the requirement IRIs they cite
        // are what the EARL report and the coverage matrix are built from. A test citing one
        // that resolves to nothing is refused by the lint, before the target is asked anything
        // (D-0039).
        List<Requirement> catalog = Files.isDirectory(catalogDir) ? CatalogRepository.load(catalogDir) : List.of();
        Set<String> catalogIris = catalog.isEmpty() ? null
                : catalog.stream().map(Requirement::iri).collect(Collectors.toSet());
        Definitions definitions;
        try {
            definitions = DefinitionLoader.load(definitionsDir, catalogIris);
        } catch (InvalidDefinitionsException e) {
            // Invalid definitions mean no test runs and the server is never asked anything: the
            // harness is misconfigured (D-0048). The message names the file and the defect.
            err.println(e.getMessage());
            return TouchstoneCli.HARNESS_ERROR;
        }
        List<TestDefinition> selected = definitions.select(selector);
        if (selected.isEmpty()) {
            err.println("no test matches '" + selector + "'; try all, a module (core, auth), a manifest ("
                    + String.join(", ", definitions.manifestPaths()) + ") or a test id");
            return TouchstoneCli.HARNESS_ERROR;
        }

        RunResult run;
        try {
            run = Engine.run(target, definitions, selected, Engine.ProgressListener.NONE);
        } catch (ProvisioningException e) {
            // The run never started: the target is unreachable, or it refused to create the run
            // root, typically with 401 because no credential for alice is configured. No test
            // ran, so there is no verdict to report (D-0048).
            err.println("cannot run against target '" + targetId + "': " + TouchstoneCli.describe(e));
            return TouchstoneCli.HARNESS_ERROR;
        }

        for (TestResult result : run.results()) {
            out.printf("[%-12s] %-6s %s (%d ms)%n", result.outcome().earl(), result.level(), result.testId(),
                    result.durationMillis());
            if (result.outcome() != Outcome.PASSED) {
                out.println(Results.describe(result).indent(4).stripTrailing());
            }
        }
        out.printf("%n%d passed, %d failed, %d cantTell, %d inapplicable  (target %s, run %s)%n",
                run.count(Outcome.PASSED), run.count(Outcome.FAILED),
                run.count(Outcome.CANT_TELL), run.count(Outcome.INAPPLICABLE), targetId, run.runId());
        long advisory = run.results().stream()
                .filter(r -> !r.decidesConformance() && r.outcome() == Outcome.FAILED).count();
        if (run.conformant()) {
            out.println("conformant: no MUST test failed or ended cantTell"
                    + (advisory == 0 ? "" : " (" + advisory + " SHOULD or MAY test(s) failed, which is advisory)"));
        } else {
            out.println("NOT conformant: " + run.mustFailures().size() + " MUST test(s) failed or ended cantTell");
        }

        Path runDir = Reports.writeAll(run, catalog, reportDir);
        out.println("reports: " + runDir
                + " (run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml)");

        return run.conformant() ? 0 : 1;
    }
}
