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
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.InvalidDefinitionsException;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "coverage",
        mixinStandardHelpOptions = true,
        exitCodeOnExecutionException = TouchstoneCli.HARNESS_ERROR,
        description = "Requirements-by-tests coverage matrix per spec module and level.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:the matrix was printed",
                "2:the catalog or the definitions could not be read"})
final class CoverageCommand implements Callable<Integer> {

    @Option(
            names = {"-c", "--catalog"},
            defaultValue = "catalog",
            description = "Requirements catalog directory (default: ${DEFAULT-VALUE}).")
    private Path catalogDir;

    @Option(
            names = "--definitions",
            defaultValue = "definitions",
            description = "The definitions directory; the requirement IRIs its tests cite count as covered"
                    + " (default: ${DEFAULT-VALUE}).")
    private Path definitionsDir;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (!Files.isDirectory(catalogDir)) {
            spec.commandLine().getErr().println("catalog directory not found: " + catalogDir);
            return TouchstoneCli.HARNESS_ERROR;
        }
        List<Requirement> requirements = CatalogRepository.load(catalogDir);

        Set<String> covered = new HashSet<>();
        int testCount = 0;
        if (Files.isDirectory(definitionsDir)) {
            List<TestDefinition> tests;
            try {
                // Loaded without the catalog check: coverage is a report, not a gate, so a test
                // citing an IRI the catalog lacks is said below rather than refused; `run` is
                // where it refuses.
                tests = DefinitionLoader.load(definitionsDir, null).tests();
            } catch (InvalidDefinitionsException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return TouchstoneCli.HARNESS_ERROR;
            }
            testCount = tests.size();
            tests.forEach(t -> covered.addAll(t.requirements()));
            List<RequirementRefs.Dangling> dangling = RequirementRefs.unresolved(tests, requirements);
            if (!dangling.isEmpty()) {
                spec.commandLine().getErr().println("warning: " + RequirementRefs.describe(dangling));
            }
        }
        CoverageReport report = CoverageReport.compute(requirements, covered);

        PrintWriter out = spec.commandLine().getOut();
        out.printf("Requirements coverage: %d of %d covered by %d test(s)%n%n",
                report.totalCovered(), report.totalRequirements(), testCount);
        out.printf("%-16s %-8s %s%n", "module", "level", "covered/total");
        for (CoverageReport.Row row : report.rows()) {
            out.printf("%-16s %-8s %d/%d%n", row.specModule(), row.level(), row.covered(), row.total());
        }
        return 0;
    }
}
