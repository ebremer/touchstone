package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Names and finds the directory holding one run's report bundle.
 *
 * <p>A directory is {@code <xsd-dateTime>-<runId>}, e.g.
 * {@code 2026-08-21T19:32:47Z-b8910c47}. The run id alone sorted arbitrarily — it is a hash —
 * so {@code ls} over a season of runs told you nothing about when any of them happened, and
 * finding the latest meant opening them. The stamp is the run's own {@code startedAt}
 * truncated to the second and rendered as an XSD {@code dateTime} in UTC, so lexical order
 * and chronological order are the same thing.
 *
 * <p>The id stays in the name, and stays last, because it is what everything else addresses a
 * run by: {@link #locate} finds a directory by id without knowing when it ran.
 *
 * <p><strong>Colons.</strong> An XSD {@code dateTime} contains them and Windows filenames
 * cannot. This is deliberate, not an oversight — the format was asked for, and it is what the
 * spec calls a dateTime. On a Windows host the JVM will refuse to create the directory rather
 * than write it somewhere unexpected; {@link #stamp} is the one place to change if that day
 * comes (dropping the colons yields {@code 2026-08-21T193247Z}, still ISO 8601 basic format
 * and still correctly ordered).
 */
public final class RunDirs {

    private static final DateTimeFormatter XSD_DATETIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private RunDirs() {
    }

    /** The directory name for a run: its start stamp, then its id. */
    public static String dirName(String startedAt, String runId) {
        String stamp = stamp(startedAt);
        return stamp == null ? runId : stamp + "-" + runId;
    }

    /**
     * {@code startedAt} as an XSD {@code dateTime} to the second, or null if it cannot be read
     * as an instant. Unparseable is not fatal: a run that produced results is still worth
     * writing, so the caller falls back to the bare id rather than losing the bundle.
     */
    static String stamp(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) {
            return null;
        }
        try {
            return XSD_DATETIME.format(Instant.parse(startedAt).truncatedTo(ChronoUnit.SECONDS));
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
