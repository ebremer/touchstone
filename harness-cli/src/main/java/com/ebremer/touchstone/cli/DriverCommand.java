package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.ebremer.touchstone.driver.AdapterChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "driver",
        mixinStandardHelpOptions = true,
        subcommands = DriverCommand.Check.class,
        description = "Tools for adapters of touchstone-driver/1 (driver/PROTOCOL.md).")
final class DriverCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(
            name = "check",
            mixinStandardHelpOptions = true,
            exitCodeOnExecutionException = TouchstoneCli.HARNESS_ERROR,
            description = {"Check that an adapter speaks touchstone-driver/1 (driver/PROTOCOL.md section 6): its hello, "
                    + "configure, an answer to every operation it lists, unsupported for every one it does not, "
                    + "invalidArguments for a line that is not a JSON object, and a clean exit after shutdown.",
                    "A passing check says the adapter speaks the protocol; whether the client is conformant is what a "
                    + "drive says. Give the adapter's command line after --."},
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:every check passed",
                    "1:a check failed",
                    "2:the adapter could not be started"})
    static final class Check implements Callable<Integer> {

        @Parameters(arity = "1..*", paramLabel = "COMMAND", description = "The adapter's command line, after --.")
        private List<String> command;

        @Option(names = "--operation-timeout", defaultValue = "60", description = "Seconds one operation may take (default: ${DEFAULT-VALUE}).")
        private int operationTimeout;

        @Option(names = "--log", defaultValue = "adapter-check.log", description = "Where the adapter's stderr goes (default: ${DEFAULT-VALUE}).")
        private Path log;

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            AdapterChecker.Result result = AdapterChecker.check(command, null, Map.of(), log,
                    Duration.ofSeconds(operationTimeout));
            PrintWriter out = spec.commandLine().getOut();
            long passed = result.checks().stream().filter(AdapterChecker.Check::passed).count();
            for (AdapterChecker.Check c : result.checks()) {
                out.printf("%-4s %s: %s%n", c.passed() ? "ok" : "FAIL", c.name(), c.detail());
            }
            out.printf("%d of %d checks passed%n", passed, result.checks().size());
            if (result.hello() == null) {
                return result.checks().stream().anyMatch(c -> c.name().equals("start")) ? TouchstoneCli.HARNESS_ERROR : 1;
            }
            return result.passed() ? 0 : 1;
        }
    }
}
