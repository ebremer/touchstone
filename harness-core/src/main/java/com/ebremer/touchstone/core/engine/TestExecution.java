package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ebremer.touchstone.core.definitions.StepDefinition;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one test (EXECUTION.md section 4.2): capabilities, its own container, prerequisites,
 * steps in order, then cleanup whatever happened. The first failing expectation ends the test;
 * in a precondition step it makes the test inapplicable rather than failed.
 */
final class TestExecution {

    private static final Logger LOG = LoggerFactory.getLogger(TestExecution.class);
    private static final List<String> ACTIONS = List.of("read", "modify", "create", "delete");

    /** A location to DELETE at test end, and who created it. */
    private record Cleanup(URI uri, String identity) {
    }

    private final RunSession run;
    private final TestDefinition test;
    private final Scope scope;
    private final List<StepResult> steps = new ArrayList<>();
    private final Deque<Cleanup> cleanups = new ArrayDeque<>();
    private URI container;
    private Outcome outcome = Outcome.PASSED;
    private String reason;

    TestExecution(RunSession run, TestDefinition test) {
        this.run = run;
        this.test = test;
        this.scope = new Scope(run, test);
    }

    TestResult execute() {
        long start = System.nanoTime();
        Set<String> missing = run.missingCapabilities(test);
        if (!missing.isEmpty()) {
            return result(Outcome.INAPPLICABLE, "the target does not declare " + String.join(", ", missing), start);
        }
        try {
            RunSession.Created created = run.createContainer(run.runRoot(), test.name(), scope);
            if (created.uri() == null) {
                steps.add(new StepResult("create the test container", created.trace(), List.of(), created.failure()));
                return result(Outcome.CANT_TELL, "cannot create the test container: " + created.failure(), start);
            }
            container = created.uri();
            scope.bind("test.container", container.toString());
            if (test.prereqs() != null && !prerequisites()) {
                return result(outcome, reason, start);
            }
            for (StepDefinition step : test.steps()) {
                if (!step(step)) {
                    break;
                }
            }
        } catch (Unresolvable e) {
            outcome = e.outcome();
            reason = e.getMessage();
        } catch (RuntimeException e) {
            outcome = Outcome.CANT_TELL;
            reason = "the engine failed: " + e;
            LOG.warn("test {} ended with an engine error", test.id(), e);
        } finally {
            cleanup();
        }
        return result(outcome, reason, start);
    }

    private TestResult result(Outcome o, String why, long start) {
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new TestResult(test.id(), test.label(), test.level(), test.requirements(), o, List.copyOf(steps),
                millis, o == Outcome.PASSED || o == Outcome.FAILED ? null : why);
    }

    // ------------------------------------------------------------------ prerequisites (4.3)

    /** Realises {@code prereqs.hierarchy} as alice; false when the test ends here. */
    private boolean prerequisites() {
        for (JsonNode entry : test.prereqs().path("hierarchy")) {
            boolean isContainer = entry.has("container");
            String variable = entry.path(isContainer ? "container" : "dataResource").asText();
            String parent = entry.has("in") ? scope.bound(entry.get("in").asText()) : container.toString();
            if (entry.path("absent").asBoolean(false)) {
                scope.bind(variable, parent + "touchstone-absent-" + UUID.randomUUID() + (isContainer ? "/" : ""));
                continue;
            }
            URI uri = create(entry, variable, isContainer, URI.create(parent));
            if (uri == null) {
                return false;
            }
            scope.bind(variable, uri.toString());
            if (entry.has("authorization") && !grant(entry.get("authorization"), variable, uri)) {
                return false;
            }
        }
        return true;
    }

    private URI create(JsonNode entry, String variable, boolean isContainer, URI parent) {
        String label = "prerequisite: create " + variable;
        if (isContainer) {
            RunSession.Created created = run.createContainer(parent, variable, scope);
            if (created.uri() == null) {
                steps.add(new StepResult(label, created.trace(), List.of(), created.failure()));
                end(Outcome.CANT_TELL, label + ": " + created.failure());
            }
            return created.uri();
        }
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Http.header("Content-Type", entry.path("contentType").asText()));
        if (entry.has("linkHeaders")) {
            List<String[]> links = new ArrayList<>();
            for (JsonNode l : entry.get("linkHeaders")) {
                String type = l.path("mediaType").isMissingNode() ? null : l.path("mediaType").asText();
                links.add(new String[] {Templates.expand(l.path("href").asText(), scope), l.path("rel").asText(), type});
            }
            headers.add(Http.header("Link", LinkValues.serialize(links)));
        }
        scope.authorization("alice").forEach((k, v) -> headers.add(Http.header(k, v)));
        byte[] body;
        String text;
        if (entry.has("bodyURL")) {
            body = Requests.fixture(test.directory(), entry.get("bodyURL").asText());
            text = new String(body, StandardCharsets.UTF_8);
        } else if (entry.has("bodyJSON")) {
            text = Templates.expandJson(entry.get("bodyJSON"), scope).toString();
            body = text.getBytes(StandardCharsets.UTF_8);
        } else {
            text = Templates.expand(entry.path("body").asText(), scope);
            body = text.getBytes(StandardCharsets.UTF_8);
        }
        Req req = new Req("POST", parent, List.copyOf(headers), body, text);
        Resp resp;
        try {
            resp = run.send(req);
        } catch (IOException e) {
            steps.add(new StepResult(label, Http.trace(req, null), List.of(), "transport error: " + e));
            end(Outcome.CANT_TELL, label + ": POST " + parent + " failed: " + e);
            return null;
        }
        String location = resp.first("Location");
        if (resp.status() != 201 || location == null) {
            String why = "POST " + parent + " as alice answered " + resp.status()
                    + (location == null ? " without a Location" : "") + ", not 201";
            steps.add(new StepResult(label, Http.trace(req, resp), List.of(), why));
            end(Outcome.CANT_TELL, label + ": " + why);
            return null;
        }
        steps.add(new StepResult(label, Http.trace(req, resp), List.of(), null));
        return parent.resolve(location);
    }

    /**
     * Grants the entry's access through the storage's access grant service, one grant per
     * assignee (4.3, item 4), or through the provisioning adapter when there is no service.
     */
    private boolean grant(JsonNode authorization, String variable, URI resource) {
        Map<String, List<String>> byAssignee = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            for (JsonNode who : authorization.path(action)) {
                byAssignee.computeIfAbsent(who.asText(), k -> new ArrayList<>()).add(action);
            }
        }
        String service;
        try {
            service = scope.resolve("service.AccessGrantService").asText();
        } catch (Unresolvable e) {
            if (e.outcome() != Outcome.INAPPLICABLE) {
                throw e;
            }
            service = null;
        }
        for (Map.Entry<String, List<String>> g : byAssignee.entrySet()) {
            String assignee = g.getKey().equals("anonymous") ? RunSession.FOAF_AGENT
                    : run.credentials().webid(g.getKey(), scope);
            String label = "prerequisite: grant " + g.getValue() + " on " + variable + " to " + g.getKey();
            if (service == null) {
                if (!run.adapter().grant(run.target(), resource, g.getValue(), assignee)) {
                    end(Outcome.INAPPLICABLE, label + ": the storage advertises no AccessGrantService and the"
                            + " provisioning adapter '" + run.adapter().id() + "' cannot grant access");
                    return false;
                }
                continue;
            }
            ObjectNode body = Templates.JSON.createObjectNode();
            body.putArray("@context").add("https://www.w3.org/ns/lws/v1");
            body.putArray("type").add("AccessGrant");
            body.put("storage", scope.resolve("storage").asText());
            ObjectNode policy = body.putArray("access").addObject();
            policy.putArray("type").add("AccessPolicy");
            ArrayNode actions = policy.putArray("action");
            g.getValue().forEach(actions::add);
            policy.put("assignee", assignee);
            ObjectNode target = policy.putObject("target");
            target.put("type", "StorageResource");
            target.putArray("value").add(resource.toString());
            String text = body.toString();
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            headers.add(Http.header("Content-Type", "application/lws+json"));
            scope.authorization("alice").forEach((k, v) -> headers.add(Http.header(k, v)));
            Req req = new Req("POST", URI.create(service), List.copyOf(headers), text.getBytes(StandardCharsets.UTF_8), text);
            Resp resp;
            try {
                resp = run.send(req);
            } catch (IOException e) {
                steps.add(new StepResult(label, Http.trace(req, null), List.of(), "transport error: " + e));
                end(Outcome.CANT_TELL, label + ": POST " + service + " failed: " + e);
                return false;
            }
            String location = resp.first("Location");
            if (resp.status() != 201 || location == null) {
                String why = "the access grant service answered " + resp.status()
                        + (location == null ? " without a Location" : "") + ", not 201";
                steps.add(new StepResult(label, Http.trace(req, resp), List.of(), why));
                // A refusal that waiting did not overcome says nothing about grants (section 4.5).
                end(RateLimits.isRefusal(resp.status()) ? Outcome.CANT_TELL : Outcome.INAPPLICABLE, label + ": " + why);
                return false;
            }
            steps.add(new StepResult(label, Http.trace(req, resp), List.of(), null));
            cleanups.push(new Cleanup(URI.create(service).resolve(location), "alice"));
        }
        return true;
    }

    private void end(Outcome o, String why) {
        outcome = o;
        reason = why;
    }

    // ------------------------------------------------------------------ steps (4.2, item 4)

    /**
     * Runs one step; false when the test ends with it. A polled step (section 4.4) is re-sent
     * every {@code pollEvery} seconds until its expectations hold or {@code pollWithin} seconds
     * have passed since the first attempt; only the attempt that is judged leaves captures and
     * cleanup registrations behind.
     *
     * <p>A 429 or 503 the step's {@code statusCode} does not name is waited out as its
     * {@code Retry-After} asks (section 4.5), and each attempt sent again is kept in the record.
     * One that waiting does not overcome is judged as it came if the expectation accepts it (a
     * {@code "4xx"} step and a 429); otherwise a polled step tries again at its next attempt, and
     * any other step ends the test cantTell, since the server has not yet answered the request.
     */
    private boolean step(StepDefinition step) {
        String identity = step.identity() != null ? step.identity() : test.identity() != null ? test.identity() : "alice";
        long deadline = System.nanoTime() + step.pollWithin() * 1_000_000_000L;
        int attempt = 0;
        while (true) {
            attempt++;
            String label = attempt == 1 ? step.label() : step.label() + " (attempt " + attempt + ")";
            Map<String, String> before = scope.snapshot();
            Req req;
            try {
                req = Requests.build(step.request(), identity, scope, test.directory(), null);
            } catch (Unresolvable e) {
                steps.add(new StepResult(label, null, List.of(), "cannot build the request: " + e.getMessage()));
                end(e.outcome(), "step '" + step.label() + "': " + e.getMessage());
                return false;
            }
            JsonNode statusCode = step.response().path("statusCode");
            RateLimits.Result sent;
            try {
                sent = run.sendStep(req, statusCode);
            } catch (IOException e) {
                steps.add(new StepResult(label, Http.trace(req, null), List.of(), "transport error: " + e));
                end(Outcome.CANT_TELL, "step '" + step.label() + "': " + e);
                return false;
            }
            Resp resp = sent.response();
            if (sent.limited() && !MessageChecks.statusMatches(statusCode, resp.status())) {
                if (step.polls() && System.nanoTime() < deadline) {
                    // Like an attempt whose expectations do not hold yet: the next one may be answered.
                    scope.restore(before);
                    if (!pause(step)) {
                        return false;
                    }
                    continue;
                }
                record(label, sent);
                String why = "the server " + sent.unresolved();
                steps.add(new StepResult(label, Http.trace(req, resp), List.of(), why));
                end(Outcome.CANT_TELL, "step '" + step.label() + "': " + why
                        + "; a request the server has not answered is no evidence either way (EXECUTION.md section 4.5)");
                return false;
            }
            Paging.Read paged = null;
            if (step.readsPages()) {
                try {
                    paged = Paging.read(run, scope.authorization(identity), step.request().path("accept").asText(null),
                            req, resp, step.pages());
                } catch (IOException e) {
                    record(label, sent);
                    steps.add(new StepResult(label, Http.trace(req, resp), List.of(), "transport error reading the next page: " + e));
                    end(Outcome.CANT_TELL, "step '" + step.label() + "': reading the next page failed: " + e);
                    return false;
                }
                if (paged.failure() != null || paged.cantTell() != null) {
                    if (step.polls() && System.nanoTime() < deadline) {
                        // A page that is not one yet is an attempt that does not hold yet (section 4.4).
                        scope.restore(before);
                        if (!pause(step)) {
                            return false;
                        }
                        continue;
                    }
                    record(label, sent);
                    AssertionResult failure = paged.failure();
                    // With no page after the first, the first carries what went wrong (a self-link).
                    boolean alone = paged.pages().isEmpty();
                    steps.add(new StepResult(label, Http.trace(req, resp),
                            alone && failure != null ? List.of(failure) : List.of(), alone ? paged.cantTell() : null));
                    pages(label, paged);
                    if (failure == null) {
                        end(Outcome.CANT_TELL, "step '" + step.label() + "': " + paged.cantTell());
                    } else if (step.precondition()) {
                        end(Outcome.INAPPLICABLE, "precondition '" + step.label() + "' does not hold: "
                                + failure.description() + " expected " + failure.expected() + ", got " + failure.actual());
                    } else {
                        end(Outcome.FAILED, null);
                    }
                    return false;
                }
            }
            Evaluator.Evaluation evaluation;
            try {
                evaluation = Evaluator.evaluate(step.response(), req, resp, scope, test.directory(),
                        accept -> Requests.build(step.request(), identity, scope, test.directory(), accept),
                        paged == null ? null : paged.merged());
            } catch (Unresolvable e) {
                record(label, sent);
                steps.add(new StepResult(label, Http.trace(req, resp), List.of(), e.getMessage()));
                end(e.outcome(), "step '" + step.label() + "': " + e.getMessage());
                return false;
            }
            if (!evaluation.passed() && step.polls() && System.nanoTime() < deadline) {
                // Not yet: undo what this attempt bound and try again (section 4.4, item 3).
                scope.restore(before);
                if (!pause(step)) {
                    return false;
                }
                continue;
            }
            evaluation.cleanup().forEach(uri -> cleanups.push(new Cleanup(uri, identity)));
            record(label, sent);
            steps.add(new StepResult(label, Http.trace(req, resp), evaluation.results(), null));
            if (paged != null) {
                pages(label, paged);
            }
            if (evaluation.passed()) {
                return true;
            }
            AssertionResult failed = evaluation.results().getLast();
            if (step.precondition()) {
                end(Outcome.INAPPLICABLE, "precondition '" + step.label() + "' does not hold: " + failed.description()
                        + " expected " + failed.expected() + ", got " + failed.actual());
            } else {
                end(Outcome.FAILED, null);
            }
            return false;
        }
    }

    /** Waits between the attempts of a polled step; false when interrupted, which ends the test. */
    private boolean pause(StepDefinition step) {
        try {
            Thread.sleep(step.pollEvery() * 1000L);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            end(Outcome.CANT_TELL, "step '" + step.label() + "': interrupted while polling");
            return false;
        }
    }

    /**
     * Keeps the pages after the first that a paged step read (section 4.6), each as an exchange of
     * its own; the last carries the failed check or the reason reading stopped, if any.
     */
    private void pages(String label, Paging.Read paged) {
        List<Paging.Page> pages = paged.pages();
        for (int i = 0; i < pages.size(); i++) {
            Paging.Page p = pages.get(i);
            boolean last = i == pages.size() - 1;
            List<AssertionResult> checks = last && paged.failure() != null ? List.of(paged.failure()) : List.of();
            steps.add(new StepResult(label + " (page " + (i + 2) + ")", Http.trace(p.req(), p.resp()), checks,
                    last ? paged.cantTell() : null));
        }
    }

    /** Keeps the attempts a 429 or 503 had sent again, before the attempt that was judged (section 4.5). */
    private void record(String label, RateLimits.Result sent) {
        for (RateLimits.Refusal r : sent.retried()) {
            steps.add(new StepResult(label + " (" + r.note() + ")", Http.trace(r.req(), r.resp()), List.of(), null));
        }
    }

    // ------------------------------------------------------------------ cleanup (section 10)

    private void cleanup() {
        while (!cleanups.isEmpty()) {
            Cleanup c = cleanups.pop();
            try {
                List<Map.Entry<String, String>> headers = new ArrayList<>();
                scope.authorization(c.identity()).forEach((k, v) -> headers.add(Http.header(k, v)));
                int status = run.send(new Req("DELETE", c.uri(), List.copyOf(headers), null, null)).status();
                if (status / 100 != 2 && status != 404 && status != 410) {
                    LOG.warn("test {}: {} left on the target: DELETE answered {}", test.id(), c.uri(), status);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("test {}: {} left on the target: {}", test.id(), c.uri(), e.getMessage());
            }
        }
        if (container != null) {
            run.deleteContainer(container, scope);
        }
        scope.closeInbox();
    }
}
