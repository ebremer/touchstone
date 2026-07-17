package com.ebremer.touchstone.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageCommandTest {

    @TempDir
    Path tmp;

    @Test
    void reportsZeroCoverageOverEmptyTestSet() throws Exception {
        Files.writeString(tmp.resolve("mini.ttl"), """
                @prefix touchstone: <https://example.org/touchstone/vocab#> .
                @prefix req: <https://example.org/touchstone/req/test-module/> .

                req:alpha
                    a touchstone:Requirement ;
                    touchstone:level "MUST" ;
                    touchstone:specModule "test-module" ;
                    touchstone:summary "Alpha requirement." .

                req:gamma
                    a touchstone:Requirement ;
                    touchstone:level "SHOULD" ;
                    touchstone:specModule "test-module" ;
                    touchstone:summary "Gamma requirement." .
                """);

        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new TouchstoneCli());
        cmd.setOut(new PrintWriter(out));

        int exit = cmd.execute("coverage", "--catalog", tmp.toString());

        assertThat(exit).isZero();
        assertThat(out.toString())
                .contains("0 of 2 covered")
                .contains("test-module")
                .containsPattern("MUST\\s+0/1")
                .containsPattern("SHOULD\\s+0/1");
    }
}
