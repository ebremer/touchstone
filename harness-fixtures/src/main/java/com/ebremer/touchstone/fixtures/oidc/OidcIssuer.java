package com.ebremer.touchstone.fixtures.oidc;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * Ephemeral OpenID / authorization-server fixture (DESIGN.md paragraph 4): embedded Jetty
 * publishing OIDC + LWS discovery documents and a JWKS, over a real jwks_uri a storage
 * server fetches from. The harness owns the signing keys so it can mint deliberately
 * broken tokens ({@link AccessTokens}) — the whole point of a library-level fake rather
 * than Keycloak (paragraph 5.4). Supports {@link #rotateKeys()} to retire the current
 * signing key mid-session.
 */
public final class OidcIssuer implements AutoCloseable {

    private final Server server;
    private final ServerConnector connector;
    private final List<RSAKey> keys = new CopyOnWriteArrayList<>();
    private volatile RSAKey current;

    private OidcIssuer() {
        this.current = generateKey("k1");
        this.keys.add(current);
        this.server = new Server();
        this.connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new DiscoveryHandler());
    }

    public static OidcIssuer start(int port) {
        OidcIssuer issuer = new OidcIssuer();
        issuer.connector.setPort(port);
        try {
            issuer.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start OIDC issuer fixture", e);
        }
        return issuer;
    }

    public String issuer() {
        return baseUri().toString().replaceAll("/$", "");
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + connector.getLocalPort() + "/");
    }

    public URI jwksUri() {
        return baseUri().resolve("jwks");
    }

    /** The current signing key — used by {@link AccessTokens} to mint valid tokens. */
    public RSAKey currentKey() {
        return current;
    }

    /** Public JWK set as served at {@link #jwksUri()} (only currently-trusted keys). */
    public JWKSet publicJwks() {
        return new JWKSet(keys.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k.toPublicJWK()).toList());
    }

    /**
     * Retires the current signing key (drops it from the published JWKS) and installs a
     * fresh one. A token minted before rotation no longer validates — the "identity-doc
     * key rotated mid-session" negative case (DESIGN.md paragraph 5.4).
     */
    public RSAKey rotateKeys() {
        RSAKey next = generateKey("k" + (keys.size() + 1));
        keys.clear();
        keys.add(next);
        current = next;
        return next;
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate signing key", e);
        }
    }

    private final class DiscoveryHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            String path = request.getHttpURI().getCanonicalPath();
            String body = switch (path) {
                case "/jwks" -> publicJwks().toString();
                case "/.well-known/openid-configuration", "/.well-known/lws-configuration" -> metadata();
                default -> null;
            };
            if (body == null) {
                response.setStatus(404);
                callback.succeeded();
                return true;
            }
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
            response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
            return true;
        }

        private String metadata() {
            String iss = issuer();
            return "{"
                    + "\"issuer\":\"" + iss + "\","
                    + "\"jwks_uri\":\"" + jwksUri() + "\","
                    + "\"subject_token_types_supported\":[\"urn:ietf:params:oauth:token-type:id_token\"],"
                    + "\"grant_types_supported\":[\"urn:ietf:params:oauth:grant-type:token-exchange\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"]"
                    + "}";
        }
    }
}
