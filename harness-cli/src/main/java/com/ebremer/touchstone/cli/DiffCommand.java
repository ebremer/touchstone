package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.report.RunDiff;
import com.ebremer.touchstone.core.report.RunRecords;
import com.ebremer.touchstone.core.results.RunResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "diff",
        mixinStandardHelpOptions = true,
        exitCodeOnExecutionException = TouchstoneCli.HARNESS_ERROR,
        description = "Compare two runs: regressions, fixes, added/removed tests. Exit 1 on regressions.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:no regressions",
                "1:at least one test that passed before now fails or errors",
                "2:a run could not be read"})
final class DiffCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Baseline run: a run directory or run.json file.")
    private Path before;

    @Parameters(index = "1", description = "New run: a run directory or run.json file.")
    private Path after;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        RunResult a;
        RunResult b;
        try {
            a = RunRecords.load(before);
            b = RunRecords.load(after);
        } catch (UncheckedIOException e) {
            // Nothing was compared, so this is not a regression, and 1 would say it was (D-0048).
            spec.commandLine().getErr().println(TouchstoneCli.describe(e));
            return TouchstoneCli.HARNESS_ERROR;
        }
        RunDiff diff = RunDiff.compare(a, b);
        PrintWriter out = spec.commandLine().getOut();

        out.printf("diff %s (%s) -> %s (%s)%n", a.runId(), a.startedAt(), b.runId(), b.startedAt());
        section(out, "regressions", diff.regressions());
        section(out, "fixes", diff.fixes());
        section(out, "other outcome changes", diff.otherChanges());
        if (!diff.added().isEmpty()) {
            out.println("added tests:");
            diff.added().forEach(id -> out.println("  + " + id));
        }
        if (!diff.removed().isEmpty()) {
            out.println("removed tests:");
            diff.removed().forEach(id -> out.println("  - " + id));
        }
        out.printf("%d unchanged%n", diff.unchanged());
        return diff.hasRegressions() ? 1 : 0;
    }

    private static void section(PrintWriter out, String title, java.util.List<RunDiff.Transition> transitions) {
        if (transitions.isEmpty()) {
            out.println("no " + title);
            return;
        }
        out.println(title + ":");
        transitions.forEach(t -> out.printf("  %s: %s -> %s%n", t.testId(), t.before().earl(), t.after().earl()));
    }
}
