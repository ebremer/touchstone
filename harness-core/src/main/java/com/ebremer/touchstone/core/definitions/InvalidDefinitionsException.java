package com.ebremer.touchstone.core.definitions;

/**
 * The definitions cannot be run: a file is not YAML 1.2, a document breaks the schema or the
 * JSON-LD context, or the lint of EXECUTION.md section 2.5 found a defect. Nothing is sent to
 * the target, and the CLI exits 2, because there is no verdict to give (D-0039, D-0048).
 */
public final class InvalidDefinitionsException extends RuntimeException {

    public InvalidDefinitionsException(String message) {
        super(message);
    }
}
