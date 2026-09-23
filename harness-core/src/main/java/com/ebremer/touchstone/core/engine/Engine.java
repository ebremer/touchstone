package com.ebremer.touchstone.core.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.exec.ProvisioningException;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;

/**
 * The engine for the YAML-LD definitions (definitions/EXECUTION.md, format 0.2.0): every front
 * end (CLI, MCP, CI, the self-test loop) runs tests through here, so adding a front end never
 * touches execution (DESIGN.md section 3).
 *
 * <p>A run provisions its root on the target, runs the selected tests in parallel on virtual
 * threads, each in its own container, and deletes what it created. Results come back in the
 * order the tests were given, which for a selection is traversal order.
 */
public final class Engine {

    /** Notified as each test finishes, so a front end can stream progress. */
    @FunctionalInterface
    public interface ProgressListener {
        void onTestComplete(String testId, Outcome outcome, int completed, int total);

        ProgressListener NONE = (id, outcome, completed, total) -> {
        };
    }

    static final int DEFAULT_PARALLELISM = 16;

    private Engine() {
    }

    public static RunResult run(Target target, Definitions definitions, List<TestDefinition> tests,
                                ProgressListener listener) {
        return run(target, definitions, tests, UUID.randomUUID().toString().substring(0, 8),
                Instant.now().toString(), listener);
    }

    /**
     * @throws ProvisioningException when the run cannot start, typically because the run root
     *                               cannot be created: there is then no verdict at all
     */
    public static RunResult run(Target target, Definitions definitions, List<TestDefinition> tests, String runId,
                                String startedAt, ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        AtomicInteger completed = new AtomicInteger();
        int total = tests.size();
        List<TestResult> results = new ArrayList<>();
        // Tests are independent and MAY run in parallel (EXECUTION.md section 4.2), but a hundred
        // simultaneous connections is a load test, not a conformance run: a server's accept
        // queue overflows and the run reports refused connections as cantTell.
        Semaphore slots = new Semaphore(parallelism(target));
        try (RunSession session = RunSession.open(target, definitions, runId);
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TestResult>> futures = new ArrayList<>();
            for (TestDefinition test : tests) {
                futures.add(pool.submit(() -> {
                    slots.acquire();
                    TestResult result;
                    try {
                        result = new TestExecution(session, test).execute();
                    } finally {
                        slots.release();
                    }
                    progress.onTestComplete(test.id(), result.outcome(), completed.incrementAndGet(), total);
                    return result;
                }));
            }
            for (Future<TestResult> f : futures) {
                results.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException("run interrupted", e);
        } catch (ExecutionException e) {
            throw new ProvisioningException("test execution failed", e.getCause());
        }
        return new RunResult(target.id(), target.baseUrl().toString(), runId, startedAt, List.copyOf(results));
    }

    /** How many tests run at once: the target's {@code parallelism} property, 16 by default. */
    static int parallelism(Target target) {
        String value = target.properties().get("parallelism");
        try {
            return value == null ? DEFAULT_PARALLELISM : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_PARALLELISM;
        }
    }

    /** One test, synchronously, in a run of its own (the fix-verify loop; MCP {@code run_one}). */
    public static TestResult runOne(Target target, Definitions definitions, TestDefinition test) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (RunSession session = RunSession.open(target, definitions, runId)) {
            return new TestExecution(session, test).execute();
        }
    }
}
