package com.ebremer.touchstone.mcp.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.engine.Engine;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.report.Reports;
import com.ebremer.touchstone.core.report.RunDirs;
import com.ebremer.touchstone.core.report.RunRecords;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;
import com.ebremer.touchstone.mcp.definitions.TestDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks async runs (DESIGN.md paragraph 6: start_run returns a run_id immediately, the
 * job runs on a virtual-thread executor, progress streams out). Results are held in
 * memory and persisted under {@code runs/<id>/} so they survive and can be diffed later.
 * Single instance, node-local — the design's stated deployment (paragraph 6).
 */
public final class RunStore {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);

    /** Receives per-test progress so the tool layer can emit MCP progress notifications. */
    @FunctionalInterface
    public interface ProgressSink {
        void onProgress(int completed, int total);

        ProgressSink NONE = (completed, total) -> {
        };
    }

    private final TouchstoneProperties props;
    private final List<Requirement> catalog;
    private final TestDefinitions definitions;
    private final ExecutorService executor;
    private final ConcurrentMap<String, RunJob> jobs = new ConcurrentHashMap<>();

    public RunStore(TouchstoneProperties props, List<Requirement> catalog, TestDefinitions definitions,
                    ExecutorService executor) {
        this.props = props;
        this.catalog = catalog;
        this.definitions = definitions;
        this.executor = executor;
    }

    /** Submits an async run and returns its id immediately. */
    public String startAsync(Target target, String selector, List<TestDefinition> tests, ProgressSink sink) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String startedAt = Instant.now().toString();
        RunJob job = new RunJob(runId, target.id(), selector, startedAt, tests.size());
        jobs.put(runId, job);

        executor.submit(() -> {
            try {
                RunResult result = Engine.run(target, definitions.definitions(), tests, runId, startedAt,
                        (id, outcome, completed, total) -> {
                    job.progress(completed);
                    try {
                        sink.onProgress(completed, total);
                    } catch (RuntimeException e) {
                        log.debug("progress notification failed (client may have detached): {}", e.toString());
                    }
                });
                Reports.writeAll(result, catalog, props.runs());
                job.complete(result);
            } catch (RuntimeException e) {
                log.warn("run {} failed", runId, e);
                job.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
        return runId;
    }

    /** A tracked job, or one reconstructed from a persisted run record. */
    public Optional<RunJob> get(String runId) {
        RunJob job = jobs.get(runId);
        if (job != null) {
            return Optional.of(job);
        }
        return loadPersisted(runId).map(result -> RunJob.completed(result, selectorOf(result)));
    }

    /**
     * The directory holding a run's report bundle, whatever it is named — bundles are
     * {@code <stamp>-<runId>} now, so the id alone no longer resolves to a path. RunStore owns
     * the runs directory, so resolution lives here rather than being handed out to the tools.
     */
    public Optional<Path> reportDir(String runId) {
        return RunDirs.locate(props.runs(), runId);
    }

    private Optional<RunResult> loadPersisted(String runId) {
        // Bundles are named <stamp>-<runId> now, so the id alone no longer resolves to a path.
        var located = RunDirs.locate(props.runs(), runId);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        var runDir = located.get();
        if (!Files.isRegularFile(runDir.resolve("run.json"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(RunRecords.load(runDir));
        } catch (RuntimeException e) {
            log.warn("cannot load persisted run {}", runId, e);
            return Optional.empty();
        }
    }

    /** For a run loaded from disk: the manifest its tests share, else the module, else all. */
    private static String selectorOf(RunResult result) {
        List<String> manifests = result.results().stream()
                .map(r -> r.testId().contains("#") ? r.testId().substring(0, r.testId().indexOf('#')) : r.testId())
                .distinct().toList();
        if (manifests.size() == 1) {
            return manifests.getFirst();
        }
        List<String> modules = manifests.stream().map(m -> m.split("/", 2)[0]).distinct().toList();
        return modules.size() == 1 ? modules.getFirst() : "all";
    }
}
