package com.ebremer.touchstone.core.exec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ebremer.touchstone.core.assertions.AssertionEngine;
import com.ebremer.touchstone.core.assertions.EvalEnv;
import com.ebremer.touchstone.core.assertions.ResponseData;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.ebremer.touchstone.core.results.HttpExchangeTrace;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;

/**
 * Executes one manifest against a provisioned run: fresh test container, ordered
 * steps, declarative assertions, variable bindings. Stateless and thread-safe —
 * tests parallelize at the manifest level (DESIGN.md paragraph 5.3), steps stay
 * sequential inside a test. Timeouts everywhere; no retries by design.
 */
public final class Executor {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private Executor() {
    }

    /**
     * Resolves the identity for one step: the step's own {@code as}, else the manifest's,
     * else the target's {@code defaultIdentity} property, else anonymous. A manifest that
     * names an identity always wins — including an explicit {@code as: anonymous}, so a
     * negative auth test keeps its meaning on a target whose default is authenticated.
     * The target-level default is what lets the spec-shaped core suite, which declares no
     * identities, run against a storage that is not world-readable (D-0028).
     */
    private static String identityFor(Manifest.Step step, Manifest manifest, RunContext ctx) {
        if (step.identity() != null) {
            return step.identity();
        }
        if (manifest.defaultIdentity() != null) {
            return manifest.defaultIdentity();
        }
        return ctx.target().properties().getOrDefault("defaultIdentity", "anonymous");
    }

    public static TestResult execute(Manifest manifest, RunContext ctx) {
        long start = System.nanoTime();
        if (!ctx.target().capabilities().containsAll(manifest.capabilities())) {
            List<String> missing = manifest.capabilities().stream()
                    .filter(c -> !ctx.target().capabilities().contains(c)).toList();
            return TestResult.skipped(manifest.id(), manifest.requirements(),
                    "target lacks capabilities " + missing);
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("run.root", ctx.runRoot().toString());
        // The storage the target was registered as. Discovery tests need a URI that is the
        // storage itself rather than a container inside it, and the registry is where a target
        // says which storage it is - the same pre-registration that makes it addressable at all
        // (DESIGN.md paragraph 7.1). It is not a substitute for what the server advertises: a
        // discovery test still asserts the advertised relation on its own.
        vars.put("target.baseUrl", ctx.target().baseUrl().toString());
        List<StepResult> steps = new ArrayList<>();
        Outcome outcome = Outcome.PASSED;
        try {
            URI testContainer = ctx.allocateTestContainer(slug(manifest.id()));
            vars.put("test.container", testContainer.toString());

            for (Manifest.Step step : manifest.steps()) {
                if (step.rawRequest() != null) {
                    steps.add(new StepResult(step.name(), null, List.of(),
                            "rawRequest execution arrives with the hostile-client work of Phase 4"));
                    outcome = Outcome.ERROR;
                    break;
                }
                StepOutcome so = runStep(manifest, step, vars, ctx);
                steps.add(so.result());
                if (so.result().error() != null) {
                    outcome = Outcome.ERROR;
                    break;
                }
                if (so.result().failed()) {
                    outcome = Outcome.FAILED;
                    break;
                }
            }
        } catch (Exception e) {
            steps.add(new StepResult("(setup)", null, List.of(), String.valueOf(e)));
            outcome = Outcome.ERROR;
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new TestResult(manifest.id(), manifest.requirements(), outcome, List.copyOf(steps), millis, null);
    }

    private record StepOutcome(StepResult result) {
    }

    private static StepOutcome runStep(Manifest manifest, Manifest.Step step,
                                       Map<String, String> vars, RunContext ctx) {
        String identity = identityFor(step, manifest, ctx);
        Duration timeout = step.timeoutMillis() != null ? Duration.ofMillis(step.timeoutMillis()) : DEFAULT_TIMEOUT;
        Path manifestDir = manifest.sourceFile().getParent();

        HttpRequest request;
        String bodyForTrace;
        try {
            Built built = build(step.request(), manifestDir, vars, ctx, identity, timeout, null);
            request = built.request();
            bodyForTrace = built.bodyText();
        } catch (Exception e) {
            return new StepOutcome(new StepResult(step.name(), null, List.of(),
                    "request build failed: " + e.getMessage()));
        }

        HttpResponse<byte[]> response;
        try {
            response = ctx.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            HttpExchangeTrace trace = HttpExchangeTrace.of(request.method(), request.uri(),
                    request.headers().map(), bodyForTrace, null, null, null);
            return new StepOutcome(new StepResult(step.name(), trace, List.of(),
                    "transport error: " + e));
        }

        ResponseData data = ResponseData.of(response);
        HttpExchangeTrace trace = HttpExchangeTrace.of(request.method(), request.uri(),
                request.headers().map(), bodyForTrace, data.status(), data.headers().map(),
                new String(data.body(), StandardCharsets.UTF_8));

        List<AssertionResult> assertions = List.of();
        if (step.expect() != null) {
            EvalEnv env = new EvalEnv(Map.copyOf(vars), manifestDir, accept -> {
                try {
                    Built refetch = build(step.request(), manifestDir, vars, ctx, identity, timeout, accept);
                    return ResponseData.of(ctx.http().send(refetch.request(), HttpResponse.BodyHandlers.ofByteArray()));
                } catch (Exception e) {
                    throw new IllegalStateException("conneg refetch failed: " + e, e);
                }
            });
            assertions = AssertionEngine.evaluate(step.expect(), data, env);
        }

        String bindError = bind(step.bind(), data, vars);
        if (bindError != null) {
            return new StepOutcome(new StepResult(step.name(), trace, assertions, bindError));
        }
        return new StepOutcome(new StepResult(step.name(), trace, assertions, null));
    }

    private record Built(HttpRequest request, String bodyText) {
    }

    private static Built build(Manifest.RequestSpec spec, Path manifestDir, Map<String, String> vars,
                               RunContext ctx, String identity, Duration timeout, String acceptOverride)
            throws IOException {
        URI target = URI.create(TemplateEngine.resolve(spec.target(), vars));
        if (!target.isAbsolute()) {
            target = ctx.runRoot().resolve(target);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout);

        byte[] body = null;
        String bodyText = null;
        if (spec.body() != null) {
            bodyText = TemplateEngine.resolve(spec.body(), vars);
            body = bodyText.getBytes(StandardCharsets.UTF_8);
        } else if (spec.bodyRef() != null) {
            body = Files.readAllBytes(manifestDir.resolve(spec.bodyRef()));
            bodyText = new String(body, StandardCharsets.UTF_8);
        }
        builder.method(spec.method(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        for (Map.Entry<String, List<String>> header : spec.headers().entrySet()) {
            if (acceptOverride != null && header.getKey().equalsIgnoreCase("Accept")) {
                continue;
            }
            for (String value : header.getValue()) {
                builder.header(header.getKey(), TemplateEngine.resolve(value, vars));
            }
        }
        if (acceptOverride != null) {
            builder.header("Accept", acceptOverride);
        }
        ctx.credentials().headersFor(identity).forEach(builder::header);
        return new Built(builder.build(), bodyText);
    }

    /** Applies bind extractors; returns an error message or null. */
    private static String bind(Map<String, String> binds, ResponseData data, Map<String, String> vars) {
        for (Map.Entry<String, String> bind : binds.entrySet()) {
            String extractor = bind.getValue();
            String value;
            if (extractor.startsWith("header:")) {
                String header = extractor.substring("header:".length());
                value = data.headers().firstValue(header).orElse(null);
                if (value == null) {
                    return "bind '" + bind.getKey() + "': response has no header " + header;
                }
                if (header.equalsIgnoreCase("Location")) {
                    value = data.uri().resolve(value).toString();
                }
            } else if (extractor.startsWith("link:")) {
                // Follow a relation rather than guessing a URI: LWS discovers a linkset through
                // rel="linkset", not through any particular path convention, so a manifest that
                // binds the relation stays portable across servers.
                String rel = extractor.substring("link:".length());
                value = LinkHeaders.target(data.headers(), rel, data.uri());
                if (value == null) {
                    return "bind '" + bind.getKey() + "': response advertises no Link with rel=\"" + rel + "\"";
                }
            } else if (extractor.equals("status")) {
                value = String.valueOf(data.status());
            } else if (extractor.equals("body")) {
                value = data.bodyText();
            } else {
                return "bind '" + bind.getKey() + "': unknown extractor " + extractor;
            }
            vars.put(bind.getKey(), value);
        }
        return null;
    }

    private static String slug(String manifestId) {
        String last = manifestId.substring(manifestId.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String clean = last.replaceAll("[^a-z0-9-]", "-");
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }
}
