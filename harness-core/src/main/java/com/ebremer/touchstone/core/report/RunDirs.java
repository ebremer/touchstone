package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names and finds the directory holding one run's report bundle.
 *
 * <p>A directory is {@code <timestamp>-<runId>}, e.g.
 * {@code 2026-08-21T193247Z-b8910c47}. The run id alone sorted arbitrarily — it is a hash —
 * so {@code ls} over a season of runs told you nothing about when any of them happened, and
 * finding the latest meant opening them. The stamp is the run's own {@code startedAt}
 * truncated to the second and rendered in UTC, so lexical order and chronological order are
 * the same thing.
 *
 * <p>The id stays in the name, and stays last, because it is what everything else addresses a
 * run by: {@link #locate} finds a directory by id without knowing when it ran.
 *
 * <p><strong>No colons.</strong> The stamp was originally an XSD {@code dateTime}
 * ({@code 2026-08-21T19:32:47Z}), on the reasoning that a Windows host would simply refuse to
 * create such a directory. It does — and the refusal was not contained: {@code Path.resolve}
 * throws {@link java.nio.file.InvalidPathException} before {@link Reports#writeAll} has
 * created anything, so a run that passed every test lost its whole bundle (no EARL, no
 * report, no {@code run.json}) and the escaping exception turned a conformant verdict into a
 * non-zero exit. A naming preference is not worth a run's evidence, so the time is written in
 * ISO 8601 <em>basic</em> form — {@code 2026-08-21T193247Z} — which every filesystem accepts
 * and which sorts identically. {@link #resolve} additionally falls back to the bare id if any
 * future name is rejected, so this class can never again cost a run its results.
 */
public final class RunDirs {

    /**
     * ISO 8601 to the second, UTC, without the separators a filesystem may reject. The date
     * keeps its hyphens (they are legal everywhere and the stamp is meant to be read); the
     * time drops its colons. Fixed-width, so lexical order is chronological order.
     */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final Logger LOG = LoggerFactory.getLogger(RunDirs.class);

    private RunDirs() {
    }

    /** The directory name for a run: its start stamp, then its id. */
    public static String dirName(String startedAt, String runId) {
        String stamp = stamp(startedAt);
        return stamp == null ? runId : stamp + "-" + runId;
    }

    /**
     * The path to write this run's bundle to, under {@code runsDir}.
     *
     * <p>Falls back to the bare run id when the host filesystem rejects the stamped name.
     * The bundle is the run's only durable record, so a name it will not accept has to
     * degrade to one it will — never to nothing at all.
     */
    public static Path resolve(Path runsDir, String startedAt, String runId) {
        String name = dirName(startedAt, runId);
        try {
            return runsDir.resolve(name);
        } catch (InvalidPathException e) {
            LOG.warn("run {}: filesystem rejected the bundle name '{}' ({}); writing to the bare run id",
                    runId, name, e.getReason());
            return runsDir.resolve(runId);
        }
    }

    /**
     * {@code startedAt} as an ISO 8601 basic-form timestamp to the second, or null if it
     * cannot be read as an instant. Unparseable is not fatal: a run that produced results is
     * still worth writing, so the caller falls back to the bare id rather than losing the
     * bundle.
     */
    static String stamp(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) {
            return null;
        }
        try {
            return STAMP.format(Instant.parse(startedAt).truncatedTo(ChronoUnit.SECONDS));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The directory for {@code runId}, whatever it is called.
     *
     * <p>Tries the bare id first, so bundles written before runs were stamped are still found,
     * then looks for the one ending {@code -<runId>}. Nothing renames anything: an old run
     * keeps its old name and stays readable.
     *
     * @return the directory, or empty when no run by that id has been written
     */
    public static Optional<Path> locate(Path runsDir, String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        Path exact = runsDir.resolve(runId);
        if (Files.isDirectory(exact)) {
            return Optional.of(exact);
        }
        if (!Files.isDirectory(runsDir)) {
            return Optional.empty();
        }
        String suffix = "-" + runId;
        try (Stream<Path> entries = Files.list(runsDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    // Ids are unique, so this is a tie-break that should never be needed;
                    // newest wins if one ever is.
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list run directory " + runsDir, e);
        }
    }
}
