package com.ebremer.touchstone.cli;

import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.Touchstone;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "touchstone",
        mixinStandardHelpOptions = true,
        versionProvider = TouchstoneCli.VersionProvider.class,
        description = "Conformance test harness for the W3C Linked Web Storage (LWS) protocol family.")
public final class TouchstoneCli implements Callable<Integer> {

    @Override
    public Integer call() {
        // Subcommands (run, coverage, diff) arrive with later phases; until then, show usage.
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new TouchstoneCli()).execute(args));
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {Touchstone.NAME + " " + Touchstone.version()};
        }
    }
}
