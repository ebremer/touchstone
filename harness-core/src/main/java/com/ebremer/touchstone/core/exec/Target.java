package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * A pre-registered system under test. Targets come exclusively from the registry
 * file (DESIGN.md paragraph 7.1 — the SSRF/abuse boundary): every front end accepts
 * only target ids, never URLs.
 */
public record Target(
        String id,
        URI baseUrl,
        String adapter,
        Map<String, String> properties,
        Set<String> capabilities) {
}
