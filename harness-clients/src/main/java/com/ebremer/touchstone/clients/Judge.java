package com.ebremer.touchstone.clients;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Judges a session's exchanges against the client rules (OBSERVATION.md sections 6 to 8), one
 * exchange at a time, in the order the log numbers them. Each rule keeps its trials, the first
 * failing one as evidence, and its open triggers. Outcomes only get worse until a reset.
 */
final class Judge {

    /** How one rule judged one exchange: passed or failed, and the term that failed. */
    record Verdict(String rule, String outcome, String term) {
    }

    /** The first failing trial of a rule. */
    record Evidence(long seq, String method, String url, String term, String expected, String actual) {
    }

    /** Open triggers a rule keeps at most; the oldest go first. */
    private static final int MAX_TRIGGERS = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class State {
        final RuleDefinition rule;
        long trials;
        long passed;
        long failed;
        long undecided;
        Evidence evidence;
        /** The target URL of each open trigger, or "" for a trigger any URL closes. */
        final ArrayDeque<String> triggers = new ArrayDeque<>();

        State(RuleDefinition rule) {
            this.rule = rule;
        }
    }

    private final ClientRules rules;
    private final Set<String> outOfScope;
    private List<State> states;

    /** @param outOfScope the areas the developer declared out of scope, whose rules are inapplicable */
    Judge(ClientRules rules, Set<String> outOfScope) {
        this.rules = rules;
        this.outOfScope = Set.copyOf(outOfScope);
        reset();
    }

    /** Starts every rule over: no trials, no evidence, no open triggers. */
    synchronized void reset() {
        List<State> fresh = new ArrayList<>();
        rules.rules().forEach(r -> fresh.add(new State(r)));
        states = fresh;
    }

    /** Judges one exchange against every rule in scope; returns the verdicts of the rules it was a trial of. */
    synchronized List<Verdict> judge(Observed x) {
        String role = x.annotations().role();
        if (role.equals("preflight") || role.equals("limited")) {
            return List.of();
        }
        List<Verdict> out = new ArrayList<>();
        for (State s : states) {
            if (outOfScope.contains(s.rule.area()) || !isTrial(s, x)) {
                continue;
            }
            s.trials++;
            Conditions.Failure f = Conditions.check(s.rule.expect(), x);
            if (f == null) {
                s.passed++;
                out.add(new Verdict(s.rule.name(), "passed", null));
            } else {
                s.failed++;
                if (s.evidence == null) {
                    s.evidence = new Evidence(x.seq(), x.method(), x.url(), f.term(), f.expected(), f.actual());
                }
                out.add(new Verdict(s.rule.name(), "failed", f.term()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Section 6: without {@code after}, a trial is an exchange {@code observe} holds of. With it,
     * the exchange must also close an open trigger, and then may open one itself.
     */
    private static boolean isTrial(State s, Observed x) {
        JsonNode observe = s.rule.observe();
        JsonNode after = observe.get("after");
        if (after == null) {
            return Conditions.check(observe, x) == null;
        }
        boolean trial = false;
        if (!s.triggers.isEmpty() && Conditions.check(observe, x) == null) {
            for (Iterator<String> it = s.triggers.iterator(); it.hasNext(); ) {
                String target = it.next();
                if (target.isEmpty() || target.equals(x.target())) {
                    it.remove();
                    trial = true;
                }
            }
        }
        if (Conditions.check(after, x) == null) {
            s.triggers.addLast(after.path("sameTarget").asBoolean(false) ? x.target() : "");
            while (s.triggers.size() > MAX_TRIGGERS) {
                s.triggers.removeFirst();
            }
        }
        return trial;
    }

    private String outcome(State s) {
        if (outOfScope.contains(s.rule.area())) {
            return "inapplicable";
        }
        return s.failed > 0 ? "failed" : s.undecided > 0 ? "cantTell" : s.passed > 0 ? "passed" : "untested";
    }

    /**
     * The session's results (section 8): each rule's outcome, trials and evidence, and the
     * verdict, which only MUST rules decide.
     */
    synchronized ObjectNode results() {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode list = JSON.createArrayNode();
        ObjectNode counts = JSON.createObjectNode();
        for (String o : List.of("passed", "failed", "cantTell", "untested", "inapplicable")) {
            counts.put(o, 0);
        }
        int mustApply = 0;
        int mustExercised = 0;
        int mustFailed = 0;
        int mustCantTell = 0;
        for (State s : states) {
            RuleDefinition r = s.rule;
            String outcome = outcome(s);
            counts.put(outcome, counts.get(outcome).asInt() + 1);
            if (r.level().equals("MUST") && !outcome.equals("inapplicable")) {
                mustApply++;
                if (!outcome.equals("untested")) {
                    mustExercised++;
                }
                mustFailed += outcome.equals("failed") ? 1 : 0;
                mustCantTell += outcome.equals("cantTell") ? 1 : 0;
            }
            ObjectNode item = list.addObject();
            item.put("rule", r.name());
            item.put("id", r.id());
            item.put("iri", r.iri());
            item.put("label", r.label());
            item.put("level", r.level());
            item.put("area", r.area());
            item.put("outcome", outcome);
            item.put("trials", s.trials);
            item.put("passed", s.passed);
            item.put("failed", s.failed);
            ArrayNode reqs = item.putArray("requirements");
            r.requirements().forEach(reqs::add);
            item.set("evidence", s.evidence == null ? null : JSON.valueToTree(s.evidence));
            item.put("guidance", r.guidance());
        }
        ObjectNode verdict = body.putObject("verdict");
        verdict.put("mustApply", mustApply);
        verdict.put("mustExercised", mustExercised);
        verdict.put("mustFailed", mustFailed);
        verdict.put("mustCantTell", mustCantTell);
        String tail = mustExercised + " MUST rule" + (mustExercised == 1 ? "" : "s") + " exercised, of "
                + mustApply + " that apply";
        verdict.put("text", mustFailed > 0 ? mustFailed + " MUST rule" + (mustFailed == 1 ? "" : "s") + " failed, in " + tail
                : mustCantTell > 0 ? "no MUST failure, but " + mustCantTell + " undecided, in " + tail
                : "no MUST failure in " + tail);
        body.set("counts", counts);
        body.set("rules", list);
        return body;
    }
}
