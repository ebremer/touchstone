package com.ebremer.touchstone.core.exec;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;

/**
 * Run orchestration shared by every front end (CLI, MCP, CI): provision a run against
 * one target, execute manifests in parallel on virtual threads (DESIGN.md paragraph 5.3),
 * and collect a {@link RunResult}. Adding a new front end must not touch the executor —
 * this is the seam they share (DESIGN.md paragraph 3).
 */
public final class Harness {

    /** Notified as each test finishes, so a front end can stream progress (MCP paragraph 6). */
    @FunctionalInterface
    public interface ProgressListener {
        void onTestComplete(String manifestId, Outcome outcome, int completed, int total);

        ProgressListener NONE = (id, outcome, completed, total) -> {
        };
    }

    private Harness() {
    }

    /** Executes {@code manifests} against {@code target} in parallel, returning a fresh run. */
    public static RunResult run(Target target, List<Manifest> manifests, ProgressListener listener) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        return run(target, manifests, runId, Instant.now().toString(), listener);
    }

    public static RunResult run(Target target, List<Manifest> manifests, String runId, String startedAt,
                                ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        List<TestResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger completed = new AtomicInteger();
        int total = manifests.size();
        try (RunContext ctx = ProvisioningAdapters.forTarget(target).provision(target, runId);
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TestResult>> futures = manifests.stream()
                    .map(m -> pool.submit(() -> {
                        TestResult result = Executor.execute(m, ctx);
                        progress.onTestComplete(m.id(), result.outcome(), completed.incrementAndGet(), total);
                        return result;
                    }))
                    .toList();
            for (Future<TestResult> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException("run interrupted", e);
        } catch (ExecutionException e) {
            throw new ProvisioningException("test execution failed", e.getCause());
        }
        List<TestResult> sorted = results.stream()
                .sorted(Comparator.comparing(TestResult::manifestId))
                .toList();
        return new RunResult(target.id(), target.baseUrl().toString(), runId, startedAt, sorted);
    }

    /** Runs a single manifest synchronously (the fix-verify loop; MCP run_one). */
    public static TestResult runOne(Target target, Manifest manifest) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (RunContext ctx = ProvisioningAdapters.forTarget(target).provision(target, runId)) {
            return Executor.execute(manifest, ctx);
        }
    }
}
