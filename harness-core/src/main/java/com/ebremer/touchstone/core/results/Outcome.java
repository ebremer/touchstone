package com.ebremer.touchstone.core.results;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Outcome of one test: the outcomes of EXECUTION.md section 9, which are EARL's.
 *
 * <p>Run records written before the YAML-LD engine used {@code ERROR} and {@code SKIPPED};
 * they still load, as {@link #CANT_TELL} and {@link #INAPPLICABLE}.
 */
public enum Outcome {
    /** Every step passed. */
    PASSED("passed"),
    /** A non-precondition expectation failed: a conformance finding. */
    FAILED("failed"),
    /**
     * The harness could not decide: a transport error, a timeout, a setup failure, or a
     * variable that could not be resolved and is not an optional feature.
     */
    @JsonAlias("ERROR")
    CANT_TELL("cantTell"),
    /** A capability, identity, service or precondition the test needs is absent. */
    @JsonAlias("SKIPPED")
    INAPPLICABLE("inapplicable"),
    /** Not run: deselected. */
    UNTESTED("untested");

    private final String earl;

    Outcome(String earl) {
        this.earl = earl;
    }

    /** The EARL outcome's local name ({@code earl:cantTell}). */
    public String earl() {
        return earl;
    }
}
