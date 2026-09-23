package com.ebremer.touchstone.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.fixtures.as.AccessTokens;
import com.ebremer.touchstone.fixtures.as.RefAuthorizationServer;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * The reference deployment the definitions run against in the self-test loop (DESIGN.md
 * section 8): a reference storage, the authorization server it trusts, and how the harness is
 * configured to test them, as a target's properties and capabilities (definitions/EXECUTION.md
 * section 5). The harness owns the parties the target depends on, so it can make each of them
 * misbehave on purpose (DESIGN.md section 1).
 *
 * <ul>
 *   <li>{@link Kind#OPEN}: a storage that authenticates nobody; every test that requires
 *       Authentication is inapplicable against it.</li>
 *   <li>{@link Kind#SECURED}: the compliant deployment. The harness holds the authorization
 *       server's key (HarnessIssuedTokens), the target reaches the harness fixture host
 *       (ReachableFixtures), and the authorization server trusts the harness identity provider
 *       (SamlTrust).</li>
 *   <li>{@link Kind#BROKEN_AUTHORIZATION_SERVER}: the same, but the authorization server
 *       exchanges anything; the authentication suites' negative tests must fail.</li>
 *   <li>{@link Kind#BROKEN_STORAGE}: a storage that claims to authenticate but never challenges
 *       or forbids; the storage's negative tests must fail. It reveals no authorization
 *       server, so the harness is given static tokens instead.</li>
 * </ul>
 */
public final class ReferenceScenario implements AutoCloseable {

    public enum Kind { OPEN, SECURED, BROKEN_AUTHORIZATION_SERVER, BROKEN_STORAGE }

    private final RefAuthorizationServer authorizationServer;
    private final RefLwsServer storage;
    private final Map<String, String> properties;
    private final List<String> capabilities;

    private ReferenceScenario(RefAuthorizationServer authorizationServer, RefLwsServer storage,
                              Map<String, String> properties, List<String> capabilities) {
        this.authorizationServer = authorizationServer;
        this.storage = storage;
        this.properties = Map.copyOf(properties);
        this.capabilities = List.copyOf(capabilities);
    }

    public static ReferenceScenario start(Kind kind) {
        if (kind == Kind.OPEN) {
            return new ReferenceScenario(null, RefLwsServer.start(0), Map.of(), List.of());
        }
        String fixtures = "http://localhost:" + freePort() + "/";
        String alice = fixtures + "agents/alice";
        String bob = fixtures + "agents/bob";
        Map<String, String> props = new LinkedHashMap<>();
        props.put("webid.alice", alice);
        props.put("webid.bob", bob);
        props.put("fixtures.baseUrl", fixtures);

        if (kind == Kind.BROKEN_STORAGE) {
            RefAuthorizationServer minter = RefAuthorizationServer.start(0);
            RefLwsServer storage = RefLwsServer.startBroken(0);
            AccessTokens tokens = new AccessTokens(minter, storage.realm());
            // The broken storage checks no token, so their validity is beside the point; they are
            // real JWTs so the signature faults can be derived from them. They are minted expired
            // because without HarnessIssuedTokens the Expired fault is a real token held until it
            // expires (EXECUTION.md section 5.2): with a live one that is a five-minute wait.
            props.put("token.alice", tokens.expired(alice));
            props.put("token.bob", tokens.expired(bob));
            return new ReferenceScenario(minter, storage, props, List.of("Authentication", "ReachableFixtures"));
        }

        RefAuthorizationServer as = kind == Kind.SECURED
                ? RefAuthorizationServer.start(0) : RefAuthorizationServer.startBroken(0);
        RefLwsServer storage = RefLwsServer.startSecured(0, as, alice);
        RSAKey idp = rsa("idp");
        try {
            as.trustSamlIdentityProvider(fixtures + "idp", idp.toRSAPublicKey());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        props.put("as.signingKey", as.currentKey().toJSONString());
        props.put("saml.idpKey", idp.toJSONString());
        return new ReferenceScenario(as, storage, props,
                List.of("Authentication", "HarnessIssuedTokens", "ReachableFixtures", "SamlTrust"));
    }

    public URI storageBaseUri() {
        return storage.baseUri();
    }

    public RefLwsServer storage() {
        return storage;
    }

    /** The authorization server, or null for an open storage. */
    public RefAuthorizationServer authorizationServer() {
        return authorizationServer;
    }

    /** The target's properties: keys and identities the harness needs (EXECUTION.md section 5). */
    public Map<String, String> properties() {
        return properties;
    }

    /** The capabilities the target declares (vocab.yamlld, lwst:Capability). */
    public List<String> capabilities() {
        return capabilities;
    }

    @Override
    public void close() {
        try {
            storage.close();
        } finally {
            if (authorizationServer != null) {
                authorizationServer.close();
            }
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("no free port for the fixture host", e);
        }
    }

    private static RSAKey rsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate a key", e);
        }
    }
}
