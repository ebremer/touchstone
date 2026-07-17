package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.RunResult;

/**
 * Writes the full report bundle for one run — {@code run.json}, {@code earl.ttl},
 * {@code junit.xml}, {@code report.html} — under {@code runsDir/<runId>/} (DESIGN.md
 * paragraph 5.5). Shared by the CLI and the MCP layer.
 */
public final class Reports {

    private Reports() {
    }

    public static Path writeAll(RunResult run, List<Requirement> catalog, Path runsDir) {
        Path runDir = runsDir.resolve(run.runId());
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create run directory " + runDir, e);
        }
        RunRecords.save(run, runDir.resolve("run.json"));
        EarlReport.write(run, runDir.resolve("earl.ttl"));
        JUnitXmlReport.write(run, runDir.resolve("junit.xml"));
        HtmlReport.write(run, catalog, runDir.resolve("report.html"));
        return runDir;
    }
}
