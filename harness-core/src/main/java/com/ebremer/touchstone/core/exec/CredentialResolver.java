package com.ebremer.touchstone.core.exec;

import java.util.Map;

/**
 * Resolves an abstract manifest identity ({@code alice}, {@code bob},
 * {@code alice-expired-token}, ...) to concrete request headers. The reserved
 * identity {@code anonymous} resolves to no headers.
 */
@FunctionalInterface
public interface CredentialResolver {

    Map<String, String> headersFor(String identity);
}
