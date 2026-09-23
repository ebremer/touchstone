package com.ebremer.touchstone.core.junit;

import java.util.stream.Stream;

import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;

/**
 * JUnit 5 dynamic tests over a run (DESIGN.md section 4): one {@link DynamicTest} per test the
 * run executed, so IDEs and JUnit XML show every definition. The run itself happens once, in
 * parallel, through {@link com.ebremer.touchstone.core.engine.Engine}; this only reports it.
 *
 * <pre>
 * &#64;TestFactory
 * Stream&lt;DynamicTest&gt; definitions() {
 *     return DefinitionDynamicTests.of(Engine.run(target, definitions, definitions.tests(), NONE));
 * }
 * </pre>
 *
 * A passed test passes and an inapplicable one is skipped, with its reason; anything else
 * fails with the finding.
 */
public final class DefinitionDynamicTests {

    private DefinitionDynamicTests() {
    }

    public static Stream<DynamicTest> of(RunResult run) {
        return run.results().stream().map(result -> DynamicTest.dynamicTest(result.testId(), () -> check(result)));
    }

    private static void check(TestResult result) {
        if (result.outcome() == Outcome.INAPPLICABLE) {
            Assumptions.abort(result.reason());
        }
        if (result.outcome() != Outcome.PASSED) {
            throw new AssertionError(Results.describe(result));
        }
    }
}
