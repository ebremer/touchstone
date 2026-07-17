package com.ebremer.touchstone.core.results;

/** Outcome of one test (or of a whole step sequence). */
public enum Outcome {
    /** Every step ran and every assertion held. */
    PASSED,
    /** A declared assertion did not hold — a conformance finding. */
    FAILED,
    /** The harness could not complete the test (transport error, unresolved variable, ...). */
    ERROR,
    /** The target does not declare a capability the test requires. */
    SKIPPED
}
