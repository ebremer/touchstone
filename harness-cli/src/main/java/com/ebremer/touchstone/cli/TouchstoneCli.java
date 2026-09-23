package com.ebremer.touchstone.cli;

import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.Touchstone;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "touchstone",
        mixinStandardHelpOptions = true,
        versionProvider = TouchstoneCli.VersionProvider.class,
        subcommands = {RunCommand.class, CoverageCommand.class, DiffCommand.class},
        exitCodeOnExecutionException = TouchstoneCli.HARNESS_ERROR,
        description = "Conformance test harness for the W3C Linked Web Storage (LWS) protocol family.")
public final class TouchstoneCli implements Callable<Integer> {

    /**
     * The exit code for "the harness produced no verdict". Causes include:
     * <ul>
     *   <li>a missing registry, or an unknown target;</li>
     *   <li>a selector that matches no test, or definitions that are invalid or cite an uncatalogued requirement;</li>
     *   <li>a target that could not be provisioned;</li>
     *   <li>a run record that cannot be read;</li>
     *   <li>any exception nothing anticipated.</li>
     * </ul>
     *
     * <p>Exit code 1 is reserved for a verdict: a non-conformant run, or a diff with
     * regressions. The two used to share a code, so CI told a server implementer their server
     * had failed when the workflow was broken (D-0046, D-0048). Every command therefore
     * declares this as its {@code exitCodeOnExecutionException}; picocli's default for an
     * escaped exception is 1.
     */
    static final int HARNESS_ERROR = 2;

    @Override
    public Integer call() {
        // Subcommands (run, coverage, diff) arrive with later phases; until then, show usage.
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new TouchstoneCli()).execute(args));
    }

    /**
     * Why the harness could not run: the message, then each cause on its own line. The causes
     * often carry the useful part. A refused connection, for example, is a
     * {@code ConnectException} beneath the provisioning failure. The HTTP client wraps that
     * exception in another of the same kind, so a cause that repeats the one above it is
     * skipped.
     */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        String previous = null;
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            String line = cause.toString();
            if (!line.equals(previous)) {
                sb.append(System.lineSeparator()).append("  caused by: ").append(line);
            }
            previous = line;
        }
        return sb.toString();
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {Touchstone.NAME + " " + Touchstone.version()};
        }
    }
}
