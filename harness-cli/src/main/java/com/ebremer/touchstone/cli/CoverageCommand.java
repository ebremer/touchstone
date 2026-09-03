package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.catalog.RequirementRefs;
import com.ebremer.touchstone.core.coverage.CoverageReport;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "coverage",
        mixinStandardHelpOptions = true,
        description = "Requirements-by-tests coverage matrix per spec module and level.")
final class CoverageCommand implements Callable<Integer> {

    @Option(
            names = {"-c", "--catalog"},
            defaultValue = "catalog",
            description = "Requirements catalog directory (default: ${DEFAULT-VALUE}).")
    private Path catalogDir;

    @Option(
            names = "--manifests",
            defaultValue = "manifests",
            description = "Manifests directory; their requirement IRIs count as covered (default: ${DEFAULT-VALUE}).")
    private Path manifestsDir;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (!Files.isDirectory(catalogDir)) {
            spec.commandLine().getErr().println("catalog directory not found: " + catalogDir);
            return 2;
        }
        List<Requirement> requirements = CatalogRepository.load(catalogDir);

        Set<String> covered = new HashSet<>();
        int manifestCount = 0;
        if (Files.isDirectory(manifestsDir)) {
            List<Manifest> manifests = ManifestLoader.loadDirectory(manifestsDir);
            manifestCount = manifests.size();
            manifests.forEach(m -> covered.addAll(m.requirements()));
            // A requirement IRI that resolves to nothing covers nothing, so it silently
            // understates the matrix. Coverage is a report, not a gate, so this is said
            // rather than enforced — `run` is where it refuses.
            List<RequirementRefs.Dangling> dangling = RequirementRefs.unresolved(manifests, requirements);
            if (!dangling.isEmpty()) {
                spec.commandLine().getErr().println("warning: " + RequirementRefs.describe(dangling));
            }
        }
        CoverageReport report = CoverageReport.compute(requirements, covered);

        PrintWriter out = spec.commandLine().getOut();
        out.printf("Requirements coverage: %d of %d covered by %d manifest(s)%n%n",
                report.totalCovered(), report.totalRequirements(), manifestCount);
        out.printf("%-16s %-8s %s%n", "module", "level", "covered/total");
        for (CoverageReport.Row row : report.rows()) {
            out.printf("%-16s %-8s %d/%d%n", row.specModule(), row.level(), row.covered(), row.total());
        }
        return 0;
    }
}
