package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * A pre-registered system under test. Targets come exclusively from the registry
 * file (DESIGN.md paragraph 7.1 — the SSRF/abuse boundary): every front end accepts
 * only target ids, never URLs.
 *
 * <p>A target is registered by its base URL, the container the run root is created in, or by
 * its storage, whose description names the storage root (EXECUTION.md section 4.1, D-0099).
 * Until the engine has read that description, a target registered by its storage has no base
 * URL.
 */
public record Target(
        String id,
        URI baseUrl,
        String adapter,
        Map<String, String> properties,
        Set<String> capabilities,
        URI storage) {

    public Target(String id, URI baseUrl, String adapter, Map<String, String> properties, Set<String> capabilities) {
        this(id, baseUrl, adapter, properties, capabilities, null);
    }

    /** The same target with the base URL the engine found: the storage root its description names. */
    public Target withBaseUrl(URI found) {
        return new Target(id, found, adapter, properties, capabilities, storage);
    }
}
