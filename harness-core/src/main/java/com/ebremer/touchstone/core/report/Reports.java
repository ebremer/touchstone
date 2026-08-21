package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.RunResult;

/**
 * Writes the full report bundle for one run — {@code run.json}, {@code report.json},
 * {@code earl.ttl}, {@code junit.xml}, {@code report.html}, {@code report.md}, {@code report.pdf} — under
 * {@code runsDir/<stamp>-<runId>/} (DESIGN.md paragraph 5.5). Shared by the CLI and the MCP
 * layer.
 *
 * <p>{@code run.json} is the evidence: every step and every redacted exchange. The four
 * {@code report.*} files are the finding, and all four are rendered from one model, so they
 * cannot disagree about whether the run conformed. See {@link RunDirs} for the directory name
 * and {@link RunDirs#locate} for finding a bundle by run id afterwards.
 */
public final class Reports {

    private Reports() {
    }

    public static Path writeAll(RunResult run, List<Requirement> catalog, Path runsDir) {
        Path runDir = runsDir.resolve(RunDirs.dirName(run.startedAt(), run.runId()));
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create run directory " + runDir, e);
        }
        RunRecords.save(run, runDir.resolve("run.json"));
        JsonReport.write(run, catalog, runDir.resolve("report.json"));
        EarlReport.write(run, runDir.resolve("earl.ttl"));
        JUnitXmlReport.write(run, runDir.resolve("junit.xml"));
        HtmlReport.write(run, catalog, runDir.resolve("report.html"));
        MarkdownReport.write(run, catalog, runDir.resolve("report.md"));
        PdfReport.write(run, catalog, runDir.resolve("report.pdf"));
        return runDir;
    }
}
