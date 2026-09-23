package com.ebremer.touchstone.fixtures.as;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * The reference authorization server (core WD section 5.2): RFC 8414 metadata at
 * {@code /.well-known/lws-configuration}, a JWKS, and an RFC 8693 token endpoint that
 * exchanges a did:key, CID, OpenID Connect or SAML 2.0 subject token for an RFC 9068 access
 * token to a storage it serves. Everything is validated before anything is issued
 * ({@link SubjectTokens}), and every refusal is an RFC 6749 error.
 *
 * <p>It is the authorization server the reference storage trusts, and the harness may hold its
 * signing key ({@link #currentKey()}), which is what the HarnessIssuedTokens capability means:
 * with it the harness mints otherwise-valid access tokens with one chosen defect. Supports
 * {@link #rotateKeys()} to retire the signing key mid-session.
 *
 * <p>Its broken twin ({@link #startBroken}) exchanges anything: no signature, claim, issuer or
 * resource is checked. Against it the authentication suites' negative tests must fail.
 */
public final class RefAuthorizationServer implements AutoCloseable {

    static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    private static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean broken;
    private final Server server;
    private final ServerConnector connector;
    private final List<RSAKey> keys = new CopyOnWriteArrayList<>();
    private final Set<String> resources = ConcurrentHashMap.newKeySet();
    private final Map<String, PublicKey> samlIdps = new ConcurrentHashMap<>();
    private final SubjectTokens subjectTokens = new SubjectTokens(samlIdps);
    private volatile RSAKey current;

    private RefAuthorizationServer(boolean broken) {
        this.broken = broken;
        this.current = generateKey("k1");
        this.keys.add(current);
        this.server = new Server();
        this.connector = new ServerConnector(server);
        connector.setAcceptQueueSize(256);
        server.addConnector(connector);
        server.setHandler(new Endpoints());
    }

    public static RefAuthorizationServer start(int port) {
        return start(port, false);
    }

    /** The broken twin: exchanges any subject token for any resource. */
    public static RefAuthorizationServer startBroken(int port) {
        return start(port, true);
    }

    private static RefAuthorizationServer start(int port, boolean broken) {
        RefAuthorizationServer as = new RefAuthorizationServer(broken);
        as.connector.setPort(port);
        try {
            as.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the reference authorization server", e);
        }
        return as;
    }

    /** The issuer identifier: this server's origin, with no path, so RFC 8414 needs no path insertion. */
    public String issuer() {
        return baseUri().toString().replaceAll("/$", "");
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + connector.getLocalPort() + "/");
    }

    public URI jwksUri() {
        return baseUri().resolve("jwks");
    }

    public URI tokenEndpoint() {
        return baseUri().resolve("token");
    }

    /** A storage this server issues tokens for: its realm, which access tokens carry as aud. */
    public void addResource(String realm) {
        resources.add(realm);
    }

    /** Trusts a SAML identity provider's signing key; the SAML suite leaves this to configuration. */
    public void trustSamlIdentityProvider(String entityId, PublicKey signingKey) {
        samlIdps.put(entityId, signingKey);
    }

    /** The current signing key, private part included: what the harness holds as {@code as.signingKey}. */
    public RSAKey currentKey() {
        return current;
    }

    /** Public JWK set as served at {@link #jwksUri()} (only currently-trusted keys). */
    public JWKSet publicJwks() {
        return new JWKSet(keys.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k.toPublicJWK()).toList());
    }

    /**
     * Retires the current signing key (drops it from the published JWKS) and installs a fresh
     * one. A token minted before rotation no longer validates: the "key rotated mid-session"
     * negative case (DESIGN.md section 5.4).
     */
    public RSAKey rotateKeys() {
        RSAKey next = generateKey("k" + (keys.size() + 1) + "-" + UUID.randomUUID().toString().substring(0, 4));
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

    /** An RFC 9068 access token for {@code subject} to {@code resource}, signed with the current key. */
    String mint(String subject, String clientId, String resource) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .audience(resource)
                .subject(subject)
                .claim("client_id", clientId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            RSAKey key = current;
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(AT_JWT)
                    .build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign an access token", e);
        }
    }

    private final class Endpoints extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getCanonicalPath();
            String method = request.getMethod();
            if (path.equals("/token")) {
                if (!method.equals("POST")) {
                    response.getHeaders().put(HttpHeader.ALLOW, "POST");
                    write(response, callback, 405, null);
                    return true;
                }
                token(request, response, callback);
                return true;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD");
                write(response, callback, 405, null);
                return true;
            }
            String body = switch (path) {
                case "/jwks" -> publicJwks().toString();
                case "/.well-known/lws-configuration", "/.well-known/oauth-authorization-server" -> metadata();
                default -> null;
            };
            write(response, callback, body == null ? 404 : 200, body);
            return true;
        }

        /**
         * RFC 8414 metadata. Token exchange is listed among the grant types because the RFC 8414
         * default excludes it, and the subject token and identifier types say which suites this
         * server takes (core WD section 5.2.2).
         */
        private String metadata() {
            ObjectNode m = JSON.createObjectNode();
            m.put("issuer", issuer());
            m.put("token_endpoint", tokenEndpoint().toString());
            m.put("jwks_uri", jwksUri().toString());
            m.putArray("grant_types_supported").add(TOKEN_EXCHANGE);
            m.putArray("subject_token_types_supported")
                    .add(SubjectTokens.JWT).add(SubjectTokens.ID_TOKEN).add(SubjectTokens.SAML2);
            m.putArray("subject_identifier_types_supported").add("https").add("did:key");
            m.putArray("token_endpoint_auth_methods_supported").add("none");
            return m.toString();
        }

        private void token(Request request, Response response, Callback callback) throws Exception {
            Map<String, String> form;
            try (InputStream in = Content.Source.asInputStream(request)) {
                form = parseForm(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!TOKEN_EXCHANGE.equals(form.get("grant_type"))) {
                error(response, callback, "unsupported_grant_type", "only token exchange is supported");
                return;
            }
            String subjectToken = form.get("subject_token");
            String type = form.get("subject_token_type");
            String resource = form.get("resource");
            if (subjectToken == null || subjectToken.isBlank() || type == null || type.isBlank()) {
                error(response, callback, "invalid_request", "subject_token and subject_token_type are required");
                return;
            }
            if (resource == null || resource.isBlank()) {
                error(response, callback, "invalid_request", "resource is required");
                return;
            }
            SubjectTokens.Subject subject;
            if (broken) {
                subject = subjectTokens.unverified(subjectToken, type);
            } else {
                if (!resources.contains(resource)) {
                    error(response, callback, "invalid_target", "this server issues no tokens for " + resource);
                    return;
                }
                try {
                    subject = subjectTokens.verify(subjectToken, type, issuer());
                } catch (SubjectTokens.Invalid e) {
                    // RFC 8693 section 2.2.2: an invalid or unacceptable subject_token is invalid_request.
                    error(response, callback, "invalid_request", e.getMessage());
                    return;
                }
            }
            ObjectNode body = JSON.createObjectNode();
            body.put("access_token", mint(subject.subject(), subject.clientId(), resource));
            body.put("issued_token_type", ACCESS_TOKEN_TYPE);
            body.put("token_type", "Bearer");
            body.put("expires_in", 300);
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            response.getHeaders().put(HttpHeader.PRAGMA, "no-cache");
            write(response, callback, 200, body.toString());
        }

        private void error(Response response, Callback callback, String error, String description) {
            ObjectNode body = JSON.createObjectNode();
            body.put("error", error);
            body.put("error_description", description);
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            write(response, callback, 400, body.toString());
        }

        private void write(Response response, Callback callback, int status, String body) {
            response.setStatus(status);
            if (body == null) {
                callback.succeeded();
                return;
            }
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
            response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
        }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(name, value);
        }
        return out;
    }
}
