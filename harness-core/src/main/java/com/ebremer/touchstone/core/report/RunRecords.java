package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ebremer.touchstone.core.results.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistence of run results as runs/&lt;runId&gt;/run.json — the machine-readable
 * record that {@link RunDiff}, CI, and the MCP layer consume.
 */
public final class RunRecords {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RunRecords() {
    }

    public static void save(RunResult run, Path file) {
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), run);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write run record " + file, e);
        }
    }

    /** Loads a run record from a run.json file, or from a run directory containing one. */
    public static RunResult load(Path path) {
        Path file = Files.isDirectory(path) ? path.resolve("run.json") : path;
        try {
            return JSON.readValue(file.toFile(), RunResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read run record " + file, e);
        }
    }
}
