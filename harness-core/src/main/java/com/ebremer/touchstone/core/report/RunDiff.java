package com.ebremer.touchstone.core.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;

/**
 * Run-to-run comparison (DESIGN.md paragraph 5.5) — regressions and fixes are the
 * first-class product; both CI gating and the MCP diff_runs tool build on this.
 */
public record RunDiff(
        String beforeRunId,
        String afterRunId,
        List<Transition> regressions,
        List<Transition> fixes,
        List<Transition> otherChanges,
        List<String> added,
        List<String> removed,
        long unchanged) {

    public record Transition(String manifestId, Outcome before, Outcome after) {
    }

    public static RunDiff compare(RunResult before, RunResult after) {
        Map<String, Outcome> old = index(before);
        Map<String, Outcome> now = index(after);

        List<Transition> regressions = new ArrayList<>();
        List<Transition> fixes = new ArrayList<>();
        List<Transition> otherChanges = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        long unchanged = 0;

        for (Map.Entry<String, Outcome> entry : now.entrySet()) {
            Outcome b = old.get(entry.getKey());
            Outcome a = entry.getValue();
            if (b == null) {
                added.add(entry.getKey());
            } else if (b == a) {
                unchanged++;
            } else if (b == Outcome.PASSED && (a == Outcome.FAILED || a == Outcome.ERROR)) {
                regressions.add(new Transition(entry.getKey(), b, a));
            } else if ((b == Outcome.FAILED || b == Outcome.ERROR) && a == Outcome.PASSED) {
                fixes.add(new Transition(entry.getKey(), b, a));
            } else {
                otherChanges.add(new Transition(entry.getKey(), b, a));
            }
        }
        for (String id : old.keySet()) {
            if (!now.containsKey(id)) {
                removed.add(id);
            }
        }
        return new RunDiff(before.runId(), after.runId(),
                List.copyOf(regressions), List.copyOf(fixes), List.copyOf(otherChanges),
                List.copyOf(added), List.copyOf(removed), unchanged);
    }

    public boolean hasRegressions() {
        return !regressions.isEmpty();
    }

    private static Map<String, Outcome> index(RunResult run) {
        Map<String, Outcome> map = new LinkedHashMap<>();
        for (TestResult r : run.results()) {
            map.put(r.manifestId(), r.outcome());
        }
        return map;
    }
}
