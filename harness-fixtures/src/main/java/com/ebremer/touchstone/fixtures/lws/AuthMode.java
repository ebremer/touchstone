package com.ebremer.touchstone.fixtures.lws;

/**
 * Authentication posture of a {@link RefLwsServer} instance.
 *
 * <ul>
 *   <li>{@link #OPEN} — no authentication; the Phase 2/3 core happy-path suite runs here.</li>
 *   <li>{@link #SECURED} — validates Bearer access tokens against the authorization
 *       server, answers 401 + a conforming WWW-Authenticate challenge on missing/invalid
 *       credentials and 403 for a valid non-owner. The compliant target.</li>
 *   <li>{@link #BROKEN} — auth theater: advertises protection but validates nothing and
 *       never challenges or forbids. The deliberately broken twin the negative tests must
 *       distinguish from {@link #SECURED} (DESIGN.md Phase 4 acceptance).</li>
 * </ul>
 */
public enum AuthMode {
    OPEN,
    SECURED,
    BROKEN
}
