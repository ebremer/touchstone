package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.coverage.CoverageReport;
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

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (!Files.isDirectory(catalogDir)) {
            spec.commandLine().getErr().println("catalog directory not found: " + catalogDir);
            return 2;
        }
        List<Requirement> requirements = CatalogRepository.load(catalogDir);
        // The manifest loader arrives with Phase 2; until then no requirement is covered.
        CoverageReport report = CoverageReport.compute(requirements, Set.of());

        PrintWriter out = spec.commandLine().getOut();
        out.printf("Requirements coverage: %d of %d covered%n%n", report.totalCovered(), report.totalRequirements());
        out.printf("%-16s %-8s %s%n", "module", "level", "covered/total");
        for (CoverageReport.Row row : report.rows()) {
            out.printf("%-16s %-8s %d/%d%n", row.specModule(), row.level(), row.covered(), row.total());
        }
        return 0;
    }
}
