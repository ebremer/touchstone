package com.ebremer.touchstone.core.assertions;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Evaluation context for one step's assertions.
 *
 * @param vars              template variables (run.root, test.container, bindings)
 * @param manifestDir       base directory for relative file references (fixtures, shapes)
 * @param refetchWithAccept re-issues the step's request with a different Accept header —
 *                          supplied by the executor for conneg-equivalence assertions;
 *                          may be null in unit-test contexts
 */
public record EvalEnv(
        Map<String, String> vars,
        Path manifestDir,
        Function<String, ResponseData> refetchWithAccept) {
}
