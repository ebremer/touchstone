package com.ebremer.touchstone.core.manifest;

/** A manifest failed schema validation or is otherwise unusable. */
public class InvalidManifestException extends RuntimeException {

    public InvalidManifestException(String message) {
        super(message);
    }
}
