package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.catalog.RequirementRefs;
import com.ebremer.touchstone.core.exec.Harness;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.exec.TargetRegistry;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
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
        description = "Execute conformance tests against a pre-registered target and emit reports.")
final class RunCommand implements Callable<Integer> {

    @Option(
            names = {"-t", "--target"},
            required = true,
            description = "Target id from the registry — never a URL (DESIGN.md paragraph 7.1).")
    private String targetId;

    @Option(
            names = {"-m", "--module"},
            defaultValue = "core",
            description = "Test module to run (default: ${DEFAULT-VALUE}).")
    private String module;

    @Option(
            names = "--manifests",
            defaultValue = "manifests",
            description = "Manifests directory (default: ${DEFAULT-VALUE}).")
    private Path manifestsDir;

    @Option(
            names = "--targets",
            defaultValue = "targets.yaml",
            description = "Target registry file (default: ${DEFAULT-VALUE}).")
    private Path targetsFile;

    @Option(
            names = {"-c", "--catalog"},
            defaultValue = "catalog",
            description = "Requirements catalog directory, used by the HTML report (default: ${DEFAULT-VALUE}).")
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
            return 2;
        }
        TargetRegistry registry = TargetRegistry.load(targetsFile);
        Target target = registry.find(targetId).orElse(null);
        if (target == null) {
            err.println("unknown target '" + targetId + "' (registered: " + registry.ids() + ")");
            return 2;
        }
        Path moduleDir = manifestsDir.resolve(module);
        if (!Files.isDirectory(moduleDir)) {
            err.println("no manifests for module '" + module + "' under " + manifestsDir);
            return 2;
        }
        List<Manifest> manifests = ManifestLoader.loadDirectory(moduleDir);
        if (manifests.isEmpty()) {
            err.println("module '" + module + "' contains no manifests");
            return 2;
        }

        // The catalog is loaded before the run, not after it, because the requirement IRIs the
        // manifests declare are what the EARL report and the conformance verdict are built from.
        // One that resolves to nothing would otherwise be discovered only by a reader of the
        // report, and by then the report already claims something untrue.
        List<Requirement> catalog = Files.isDirectory(catalogDir)
                ? CatalogRepository.load(catalogDir)
                : List.of();
        List<RequirementRefs.Dangling> dangling = RequirementRefs.unresolved(manifests, catalog);
        if (!dangling.isEmpty()) {
            err.println(RequirementRefs.describe(dangling));
            err.println("Fix the manifest, or add the requirement to " + catalogDir + ".");
            return 2;
        }

        RunResult run = Harness.run(target, manifests, Harness.ProgressListener.NONE);

        for (TestResult result : run.results()) {
            out.printf("[%-6s] %s (%d ms)%n", result.outcome(), result.manifestId(), result.durationMillis());
            if (result.outcome() != Outcome.PASSED) {
                out.println(Results.describe(result).indent(4).stripTrailing());
            }
        }
        out.printf("%n%d passed, %d failed, %d errors, %d skipped  (target %s, run %s)%n",
                run.count(Outcome.PASSED), run.count(Outcome.FAILED),
                run.count(Outcome.ERROR), run.count(Outcome.SKIPPED), targetId, run.runId());

        Path runDir = Reports.writeAll(run, catalog, reportDir);
        out.println("reports: " + runDir
                + " (run.json, report.json, report.md, report.html, report.pdf, earl.ttl, junit.xml)");

        return run.conformant() ? 0 : 1;
    }
}
