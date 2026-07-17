package com.ebremer.touchstone.fixtures;

/**
 * Ephemeral identity/fixture servers (OIDC issuer, SAML IdP, CID/did:key signer,
 * identity-document host, webhook sink) arrive in Phase 4+ (DESIGN.md paragraph 9).
 * Until then this module pins and smoke-tests the embedded Jetty stack.
 */
public final class FixtureServers {

    private FixtureServers() {
    }
}
