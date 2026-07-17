package com.ebremer.touchstone.mcp.run;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.exec.Harness;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.report.Reports;
import com.ebremer.touchstone.core.report.RunRecords;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;
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
    private final ExecutorService executor;
    private final ConcurrentMap<String, RunJob> jobs = new ConcurrentHashMap<>();

    public RunStore(TouchstoneProperties props, List<Requirement> catalog, ExecutorService executor) {
        this.props = props;
        this.catalog = catalog;
        this.executor = executor;
    }

    /** Submits an async run and returns its id immediately. */
    public String startAsync(Target target, String module, List<Manifest> manifests, ProgressSink sink) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String startedAt = Instant.now().toString();
        RunJob job = new RunJob(runId, target.id(), module, startedAt, manifests.size());
        jobs.put(runId, job);

        executor.submit(() -> {
            try {
                RunResult result = Harness.run(target, manifests, runId, startedAt, (id, outcome, completed, total) -> {
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
        return loadPersisted(runId).map(result -> RunJob.completed(result, moduleOf(result)));
    }

    private Optional<RunResult> loadPersisted(String runId) {
        var runDir = props.runs().resolve(runId);
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

    private static String moduleOf(RunResult result) {
        return result.results().isEmpty() ? "?" : result.results().getFirst().manifestId().split("/", 2)[0];
    }
}
