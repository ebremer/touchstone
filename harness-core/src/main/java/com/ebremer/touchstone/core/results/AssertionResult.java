package com.ebremer.touchstone.core.results;

/** One evaluated assertion: what was checked, what was expected, what was seen. */
public record AssertionResult(String description, boolean passed, String expected, String actual) {

    public static AssertionResult ok(String description, String expected, String actual) {
        return new AssertionResult(description, true, expected, actual);
    }

    public static AssertionResult failed(String description, String expected, String actual) {
        return new AssertionResult(description, false, expected, actual);
    }
}
