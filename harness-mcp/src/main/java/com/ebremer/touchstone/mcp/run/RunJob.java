package com.ebremer.touchstone.mcp.run;

import com.ebremer.touchstone.core.results.RunResult;

/**
 * A run tracked by the {@link RunStore}: its live status and progress while executing,
 * and the {@link RunResult} once complete. Mutable fields are volatile — the job runs on
 * a virtual thread while tools read its state.
 */
public final class RunJob {

    private final String runId;
    private final String targetId;
    private final String module;
    private final String startedAt;
    private final int total;

    private volatile RunStatus status = RunStatus.RUNNING;
    private volatile int completed;
    private volatile RunResult result;
    private volatile String error;

    public RunJob(String runId, String targetId, String module, String startedAt, int total) {
        this.runId = runId;
        this.targetId = targetId;
        this.module = module;
        this.startedAt = startedAt;
        this.total = total;
    }

    /** Wraps an already-finished run loaded from disk. */
    public static RunJob completed(RunResult result, String module) {
        RunJob job = new RunJob(result.runId(), result.targetId(), module, result.startedAt(), result.results().size());
        job.completed = result.results().size();
        job.result = result;
        job.status = RunStatus.COMPLETE;
        return job;
    }

    public String runId() {
        return runId;
    }

    public String targetId() {
        return targetId;
    }

    public String module() {
        return module;
    }

    public String startedAt() {
        return startedAt;
    }

    public int total() {
        return total;
    }

    public int completed() {
        return completed;
    }

    public RunStatus status() {
        return status;
    }

    public RunResult result() {
        return result;
    }

    public String error() {
        return error;
    }

    void progress(int completed) {
        this.completed = completed;
    }

    void complete(RunResult result) {
        this.result = result;
        this.completed = result.results().size();
        this.status = RunStatus.COMPLETE;
    }

    void fail(String error) {
        this.error = error;
        this.status = RunStatus.FAILED;
    }
}
