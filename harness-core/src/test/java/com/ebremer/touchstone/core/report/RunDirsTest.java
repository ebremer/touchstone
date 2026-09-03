package com.ebremer.touchstone.core.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run-bundle naming. The stamp has to be writable on every host the harness runs on:
 * it was an XSD {@code dateTime} once, and the colons meant a Windows run threw
 * {@code InvalidPathException} out of {@link Reports#writeAll} before a single artifact
 * existed — losing the whole bundle and inverting the exit code of a run that passed.
 * These tests are platform-independent by construction, so that cannot come back on any
 * one developer's machine.
 */
class RunDirsTest {

    @TempDir
    Path tmp;

    @Test
    void theStampCarriesNoCharacterAFilesystemRejects() {
        String stamp = RunDirs.stamp("2026-08-21T19:32:47Z");
        assertThat(stamp).isEqualTo("2026-08-21T193247Z");
        // The Windows-illegal set, plus the separators that break a path anywhere.
        assertThat(stamp).doesNotContainAnyWhitespaces()
                .doesNotContain(":", "<", ">", "\"", "|", "?", "*", "/", "\\");
    }

    @Test
    void aStampedNameIsAcceptedByThisHostsFilesystem() throws Exception {
        Path dir = RunDirs.resolve(tmp, "2026-08-21T19:32:47Z", "b8910c47");
        Files.createDirectories(dir);
        assertThat(dir.getFileName()).hasToString("2026-08-21T193247Z-b8910c47");
        assertThat(dir).isDirectory();
    }

    @Test
    void secondsAreTruncatedAndTheZoneIsUtc() {
        assertThat(RunDirs.stamp("2026-08-21T19:32:47.918273645Z")).isEqualTo("2026-08-21T193247Z");
        assertThat(RunDirs.stamp("2026-08-21T15:32:47-04:00")).isEqualTo("2026-08-21T193247Z");
    }

    @Test
    void lexicalOrderIsChronologicalOrder() {
        List<String> names = List.of(
                RunDirs.dirName("2026-08-21T19:32:47Z", "zzzzzzzz"),
                RunDirs.dirName("2026-01-02T03:04:05Z", "mmmmmmmm"),
                RunDirs.dirName("2026-08-21T19:32:48Z", "aaaaaaaa"));
        // The ids sort the other way, so this only passes if the stamp is what orders them.
        assertThat(names).isNotEqualTo(names.stream().sorted().toList());
        assertThat(names.stream().sorted().toList()).containsExactly(
                "2026-01-02T030405Z-mmmmmmmm",
                "2026-08-21T193247Z-zzzzzzzz",
                "2026-08-21T193248Z-aaaaaaaa");
    }

    @Test
    void anUnreadableStartTimeFallsBackToTheBareIdRatherThanLosingTheBundle() {
        assertThat(RunDirs.dirName(null, "b8910c47")).isEqualTo("b8910c47");
        assertThat(RunDirs.dirName("  ", "b8910c47")).isEqualTo("b8910c47");
        assertThat(RunDirs.dirName("not a timestamp", "b8910c47")).isEqualTo("b8910c47");
        assertThat(RunDirs.resolve(tmp, "not a timestamp", "b8910c47").getFileName())
                .hasToString("b8910c47");
    }

    @Test
    void locateFindsAStampedBundleByIdAlone() throws Exception {
        Files.createDirectories(tmp.resolve("2026-08-21T193247Z-b8910c47"));
        assertThat(RunDirs.locate(tmp, "b8910c47"))
                .contains(tmp.resolve("2026-08-21T193247Z-b8910c47"));
    }

    @Test
    void locateStillFindsBundlesWrittenBeforeRunsWereStamped() throws Exception {
        Files.createDirectories(tmp.resolve("b8910c47"));
        assertThat(RunDirs.locate(tmp, "b8910c47")).contains(tmp.resolve("b8910c47"));
    }

    @Test
    void locateIsEmptyForAnUnknownOrMissingRun() throws Exception {
        assertThat(RunDirs.locate(tmp, "nosuchid")).isEmpty();
        assertThat(RunDirs.locate(tmp.resolve("no-runs-dir"), "b8910c47")).isEmpty();
        assertThat(RunDirs.locate(tmp, null)).isEmpty();
    }

    @Test
    void writeAllProducesTheWholeBundleUnderTheStampedName() throws Exception {
        RunResult run = new RunResult("ref", "http://localhost:4711/", "b8910c47", "2026-08-21T19:32:47Z",
                List.of(ReportTestData.test("core/one", Outcome.PASSED, ReportTestData.REQ_A)));

        Path dir = Reports.writeAll(run, List.of(), tmp);

        assertThat(dir.getFileName()).hasToString("2026-08-21T193247Z-b8910c47");
        assertThat(dir.resolve("run.json")).exists();
        assertThat(dir.resolve("report.json")).exists();
        assertThat(dir.resolve("earl.ttl")).exists();
        assertThat(dir.resolve("junit.xml")).exists();
        assertThat(dir.resolve("report.html")).exists();
        assertThat(dir.resolve("report.md")).exists();
        assertThat(dir.resolve("report.pdf")).exists();
    }
}
