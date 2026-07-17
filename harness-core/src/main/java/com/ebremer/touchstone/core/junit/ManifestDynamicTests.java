package com.ebremer.touchstone.core.junit;

import java.nio.file.Path;
import java.util.stream.Stream;

import com.ebremer.touchstone.core.exec.Executor;
import com.ebremer.touchstone.core.exec.RunContext;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;

/**
 * The JUnit 5 {@code @TestFactory} loader (DESIGN.md paragraph 4): one
 * {@link DynamicTest} per manifest, so IDEs, surefire parallelism, and JUnit XML
 * all come for free. Usage:
 *
 * <pre>
 * &#64;TestFactory
 * Stream&lt;DynamicTest&gt; coreSuite() {
 *     return ManifestDynamicTests.forDirectory(Path.of("manifests"), "core", ctx);
 * }
 * </pre>
 */
public final class ManifestDynamicTests {

    private ManifestDynamicTests() {
    }

    public static Stream<DynamicTest> forDirectory(Path manifestsRoot, String module, RunContext ctx) {
        return ManifestLoader.loadDirectory(manifestsRoot.resolve(module)).stream()
                .map(manifest -> DynamicTest.dynamicTest(manifest.id(), () -> {
                    TestResult result = Executor.execute(manifest, ctx);
                    if (result.outcome() == Outcome.SKIPPED) {
                        Assumptions.abort(result.skipReason());
                    }
                    if (result.outcome() != Outcome.PASSED) {
                        throw new AssertionError(Results.describe(result));
                    }
                }));
    }
}
