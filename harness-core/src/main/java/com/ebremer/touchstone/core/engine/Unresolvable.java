package com.ebremer.touchstone.core.engine;

import com.ebremer.touchstone.core.results.Outcome;

/**
 * A variable, identity or credential the engine cannot produce. What that means for the test
 * depends on why (EXECUTION.md section 3): an absent optional feature, service, identity or
 * fixture host makes it inapplicable; anything else means the engine cannot tell.
 */
final class Unresolvable extends RuntimeException {

    private final Outcome outcome;

    private Unresolvable(Outcome outcome, String message) {
        super(message, null, false, false);
        this.outcome = outcome;
    }

    static Unresolvable inapplicable(String message) {
        return new Unresolvable(Outcome.INAPPLICABLE, message);
    }

    static Unresolvable cantTell(String message) {
        return new Unresolvable(Outcome.CANT_TELL, message);
    }

    Outcome outcome() {
        return outcome;
    }
}
