package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.StepDefinition;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Steps that meet a 429 or a 503 (EXECUTION.md section 4.5), run by the engine against a stub
 * storage that refuses the first requests to {@code /limited} as each test scripts.
 */
class RateLimitedStepsTest {

    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    @Test
    void aStepIsSentAgainAfterTheRetryAfterAndJudgedOnTheAnswer() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 2, 429, "1"), step(false, null, "200"));

        assertThat(result.outcome()).as(result.toString()).isEqualTo(Outcome.PASSED);
        assertThat(seen).hasValue(3);
        assertThat(result.steps()).extracting(StepResult::name)
                .filteredOn(n -> n.contains("Retry-After")).hasSize(2)
                .allMatch(n -> n.contains("429 Too Many Requests"));
    }

    @Test
    void aRefusalWithoutRetryAfterIsCantTellNotFailed() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 5, 429, null), step(false, null, "200"));

        assertThat(result.outcome()).isEqualTo(Outcome.CANT_TELL);
        assertThat(result.reason()).contains("429 Too Many Requests").contains("without a Retry-After");
        assertThat(seen).hasValue(1);
    }

    @Test
    void aPreconditionThatMeetsARefusalIsCantTellNotInapplicable() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 5, 503, "120"), step(true, null, "200"));

        assertThat(result.outcome()).isEqualTo(Outcome.CANT_TELL);
        assertThat(result.reason()).contains("503 Service Unavailable").contains("longer than the 30 s");
    }

    @Test
    void aStepThatExpectsTheRefusalGetsIt() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 5, 429, "1"), step(false, null, "429"));

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(seen).hasValue(1);
    }

    @Test
    void aStepWhoseClassAdmitsTheRefusalIsJudgedOnItOnceWaitingIsOver() throws Exception {
        // ["2xx", "4xx"]: tidying up, say. Without a Retry-After there is nothing to wait for,
        // and the expectation accepts the 429 as it came.
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 5, 429, null), step(false, null, "[\"2xx\", \"4xx\"]"));

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(seen).hasValue(1);
    }

    @Test
    void aPolledStepTreatsARefusalAsNotYet() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 2, 429, null), step(false, "{\"within\": 20, \"every\": 1}", "200"));

        assertThat(result.outcome()).as(result.toString()).isEqualTo(Outcome.PASSED);
        assertThat(seen).hasValue(3);
    }

    @Test
    void aPolledStepStillRefusedWhenItsTimeIsUpIsCantTell() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        TestResult result = run(refusing(seen, 100, 503, null), step(false, "{\"within\": 2, \"every\": 1}", "200"));

        assertThat(result.outcome()).isEqualTo(Outcome.CANT_TELL);
        assertThat(result.reason()).contains("503 Service Unavailable");
        assertThat(seen.get()).isGreaterThan(1);
    }

    /** A stub whose {@code /limited} answers {@code status} the first {@code times} times, then 200. */
    private static StubTarget.Handler refusing(AtomicInteger seen, int times, int status, String retryAfter) {
        return exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/limited")) {
                return false;
            }
            if (seen.incrementAndGet() <= times) {
                StubTarget.answer(exchange, status, retryAfter == null ? Map.of() : Map.of("Retry-After", retryAfter), null);
            } else {
                StubTarget.answer(exchange, 200, Map.of("Content-Type", "text/plain"), "ok");
            }
            return true;
        };
    }

    private static StepDefinition step(boolean precondition, String poll, String statusCode) throws IOException {
        var request = Templates.JSON.readTree("{\"method\": \"GET\", \"url\": \"${target.baseUrl}limited\"}");
        var response = Templates.JSON.readTree("{\"statusCode\": " + statusCode + "}");
        var p = poll == null ? null : Templates.JSON.readTree(poll);
        return new StepDefinition("read /limited", null, precondition, request, response,
                p == null ? 0 : p.path("within").asInt(), p == null ? 0 : p.path("every").asInt());
    }

    private static TestResult run(StubTarget.Handler handler, StepDefinition step) throws IOException {
        try (StubTarget storage = StubTarget.start(handler)) {
            Target target = new Target("stub", storage.base(), "env", Map.of(), Set.of());
            TestDefinition test = new TestDefinition("engine/rate-limits#limited", "engine/rate-limits", "limited",
                    Definitions.BASE + "engine/rate-limits#limited", "ValidationTest", "a limited read", "MUST",
                    "Proposed", List.of(), List.of("Get"), List.of(), null, List.of(), null, List.of(step),
                    Path.of("..", "definitions", "lws10"));
            return Engine.runOne(target, definitions, test);
        }
    }
}
