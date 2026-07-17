package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ebremer.touchstone.core.exec.Executor;
import com.ebremer.touchstone.core.exec.ProvisioningAdapters;
import com.ebremer.touchstone.core.exec.RunContext;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.exec.TargetRegistry;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
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
        description = "Execute conformance tests against a pre-registered target.")
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

        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<TestResult> results = new ArrayList<>();
        // Parallel by default (DESIGN.md paragraph 5.3): one virtual thread per test,
        // each test isolated in its own container; steps stay sequential inside a test.
        try (RunContext ctx = ProvisioningAdapters.forTarget(target).provision(target, runId);
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TestResult>> futures = manifests.stream()
                    .map(m -> pool.submit(() -> Executor.execute(m, ctx)))
                    .toList();
            for (Future<TestResult> future : futures) {
                results.add(future.get());
            }
        }
        results.sort(Comparator.comparing(TestResult::manifestId));

        for (TestResult result : results) {
            out.printf("[%-6s] %s (%d ms)%n", result.outcome(), result.manifestId(), result.durationMillis());
            if (result.outcome() != Outcome.PASSED) {
                out.println(Results.describe(result).indent(4).stripTrailing());
            }
        }
        RunResult run = new RunResult(targetId, runId, List.copyOf(results));
        out.printf("%n%d passed, %d failed, %d errors, %d skipped  (target %s, run %s)%n",
                run.count(Outcome.PASSED), run.count(Outcome.FAILED),
                run.count(Outcome.ERROR), run.count(Outcome.SKIPPED), targetId, runId);
        return run.conformant() ? 0 : 1;
    }
}
