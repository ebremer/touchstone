package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.io.StringWriter;

import com.ebremer.touchstone.core.Touchstone;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class TouchstoneCliTest {

    @Test
    void versionPrintsProjectNameAndVersion() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setOut(new PrintWriter(out));

        int exit = cmd.execute("--version");

        assertThat(exit).isZero();
        assertThat(out.toString().trim()).isEqualTo(Touchstone.NAME + " " + Touchstone.version());
    }
}
