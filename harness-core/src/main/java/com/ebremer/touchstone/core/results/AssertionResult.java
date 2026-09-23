package com.ebremer.touchstone.core.results;

/**
 * One evaluated expectation: what was checked, what was expected, what was seen. Redacted at
 * construction, as traces are (DESIGN.md section 7.2): a check on a credential records that it
 * held, not the credential ({@link Redaction#redactValue}).
 */
public record AssertionResult(String description, boolean passed, String expected, String actual) {

    public AssertionResult {
        expected = Redaction.redactValue(description, expected);
        actual = Redaction.redactValue(description, actual);
    }

    public static AssertionResult ok(String description, String expected, String actual) {
        return new AssertionResult(description, true, expected, actual);
    }

    public static AssertionResult failed(String description, String expected, String actual) {
        return new AssertionResult(description, false, expected, actual);
    }
}
