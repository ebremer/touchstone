package com.ebremer.touchstone.clients;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.ebremer.touchstone.core.Touchstone;
import com.ebremer.touchstone.core.report.EarlReport;
import com.ebremer.touchstone.core.report.JUnitXmlReport;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A session's results as the exports of CLIENT-TESTING.md section 3.6: JSON, EARL and JUnit XML.
 * All three come from the one results document {@link Judge#results()} computes, so they cannot
 * disagree. EARL and JUnit XML are written by harness-core's writers, which server runs use too:
 * each rule becomes a test result, and its first failing trial the step that failed.
 */
final class SessionReports {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SessionReports() {
    }

    /**
     * The session's results with what the exports need besides: the client under test, the
     * areas in scope, when the results began, and the harness that judged them.
     */
    static ObjectNode results(Session s, Instant now) {
        ObjectNode body = s.judge.results();
        body.put("session", s.id);
        body.put("recorded", s.recorder.recorded());
        body.set("clientUnderTest", clientUnderTest(s.clientUnderTest));
        ArrayNode areas = body.putArray("areas");
        s.areas().forEach(areas::add);
        body.put("since", s.resultsSince().toString());
        body.put("generated", now.toString());
        ObjectNode harness = body.putObject("harness");
        harness.put("name", Touchstone.NAME);
        harness.put("version", Touchstone.version());
        return body;
    }

    static ObjectNode clientUnderTest(Session.ClientUnderTest c) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", c.name());
        node.put("version", c.version());
        node.put("homepage", c.homepage());
        return node;
    }

    /** The EARL report, in Turtle (OBSERVATION.md section 8). */
    static String earl(Session s, ObjectNode results) {
        return EarlReport.turtle(run(s, results), subject(s), "semiAuto");
    }

    /** The JUnit XML report, for a developer's CI. */
    static String junit(Session s, ObjectNode results) {
        return JUnitXmlReport.render(run(s, results));
    }

    /**
     * What the EARL assertions are about (OBSERVATION.md section 8): the client as the developer
     * named it, identified by its homepage when it has one; otherwise the session.
     */
    static EarlReport.Subject subject(Session s) {
        Session.ClientUnderTest c = s.clientUnderTest;
        if (c.name() == null && c.homepage() == null) {
            return new EarlReport.Subject(s.base, "the client tested in session " + s.id, null, null, null);
        }
        String name = c.name() != null ? c.name() : c.homepage();
        return new EarlReport.Subject(c.homepage(), "client '" + name + "'" + (c.version() == null ? "" : " " + c.version()),
                name, c.version(), c.homepage());
    }

    /** The results as a run of harness-core, one test result per rule. */
    static RunResult run(Session s, ObjectNode results) {
        List<TestResult> tests = new ArrayList<>();
        for (JsonNode r : results.path("rules")) {
            List<String> requirements = new ArrayList<>();
            r.path("requirements").forEach(q -> requirements.add(q.asText()));
            Outcome outcome = outcome(r.path("outcome").asText());
            List<StepResult> steps = List.of();
            JsonNode e = r.get("evidence");
            if (e != null && !e.isNull()) {
                long failed = r.path("failed").asLong();
                long trials = r.path("trials").asLong();
                String step = "exchange #" + e.path("seq").asLong() + ": " + e.path("method").asText() + " "
                        + e.path("url").asText() + (e.path("status").asInt() == 0 ? ", unanswered" : " -> " + e.path("status").asInt())
                        + (failed > 1 ? "; " + failed + " of " + trials + " trials failed" : "");
                steps = List.of(new StepResult(step, null, List.of(AssertionResult.failed(e.path("term").asText(),
                        e.path("expected").asText(), e.path("actual").asText())), null));
            }
            String reason = switch (outcome) {
                case INAPPLICABLE -> r.path("inapplicableBecause").asText().equals("proxy")
                        ? "a proxy session cannot judge it: it needs what only the session's own servers know"
                        : "the " + r.path("area").asText() + " area is out of scope";
                case UNTESTED -> "no trial yet" + (r.hasNonNull("task")
                        ? "; its task: " + r.path("task").path("prompt").asText() : "");
                case CANT_TELL -> "a trial could not be decided";
                default -> null;
            };
            tests.add(new TestResult(r.path("id").asText(), r.path("label").asText(null), r.path("level").asText(null),
                    requirements, outcome, steps, 0, reason));
        }
        Session.ClientUnderTest c = s.clientUnderTest;
        return new RunResult(c.name() == null ? "client" : c.name(), c.homepage() == null ? s.base : c.homepage(), s.id,
                results.path("since").asText(s.resultsSince().toString()), tests);
    }

    private static Outcome outcome(String earl) {
        for (Outcome o : Outcome.values()) {
            if (o.earl().equals(earl)) {
                return o;
            }
        }
        throw new IllegalArgumentException("no outcome " + earl);
    }
}
