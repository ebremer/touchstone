package com.ebremer.touchstone.fixtures.op;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * A small OpenID Provider for a client-testing session (CLIENT-TESTING.md section 4.2): OpenID
 * Connect Discovery, a JWKS, an authorization endpoint with a sign-in form, and a token endpoint,
 * for the authorization code flow with PKCE. Its ID Tokens are LWS authentication credentials as
 * lws10-authn-openid section 4 defines them: signed with ES256, the subject's WebID in
 * {@code sub}, the client in {@code azp}, and {@code aud} naming the client and the authorization
 * server.
 *
 * <p>Clients are public, registered in advance with the redirect URIs they use (section 12.3:
 * registration on the session page). A redirect URI must match a registered one exactly, except
 * that a loopback one ({@code http://127.0.0.1} or {@code http://[::1]}) may use any port (RFC
 * 8252 section 7.3). PKCE with S256 is required, as RFC 9700 section 2.1.1 recommends.
 *
 * <p>It is mounted under a path of another server, its issuer being that path, and keeps
 * everything in memory with bounds on everything: users, clients, sign-ins in progress and codes.
 */
public final class RefOpenIdProvider {

    /** A registered client: its identifier and the redirect URIs it may use. */
    public record Client(String clientId, List<String> redirectUris) {
    }

    /** Clients one provider holds, and redirect URIs one client may register. */
    public static final int MAX_CLIENTS = 10;
    public static final int MAX_REDIRECT_URIS = 5;
    private static final int MAX_PENDING = 100;
    private static final Duration SIGN_IN_TIME = Duration.ofMinutes(10);
    private static final Duration CODE_TIME = Duration.ofMinutes(1);
    private static final Duration ID_TOKEN_TIME = Duration.ofMinutes(5);
    private static final Set<String> UNSAFE_SCHEMES = Set.of("javascript", "data", "vbscript", "file", "about", "blob");
    private static final String STYLE = "body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:16px;background:#f6f7f9;"
            + "color:#1b1f24}main{max-width:28rem;margin:2rem auto;background:#fff;border:1px solid #d0d7de;"
            + "border-radius:8px;padding:16px 24px}label{display:block;margin:12px 0}select,input{display:block;"
            + "width:100%;box-sizing:border-box;font:inherit;padding:6px}button{font:inherit;padding:6px 16px;"
            + "margin-top:8px}.note{color:#57606a;font-size:14px}.error{color:#b42318}code{word-break:break-all}"
            + "@media (prefers-color-scheme:dark){body{background:#0d1117;color:#e6edf3}main{background:#161b22;"
            + "border-color:#30363d}.note{color:#8b949e}.error{color:#ff7b72}}";
    private static final String CSP = "default-src 'none'; style-src 'sha256-" + sha256Base64(STYLE)
            + "'; base-uri 'none'; frame-ancestors 'none'";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A user who can sign in. */
    private record User(String subject, String password) {
    }

    /** An authorization request waiting for the user to sign in. */
    private record Pending(Client client, String redirectUri, String state, String nonce, String challenge,
                           Instant expires) {
    }

    /** What an authorization code stands for, until it is redeemed once. */
    private record Grant(Client client, String redirectUri, String nonce, String challenge, String subject,
                         Instant authTime, Instant expires) {
    }

    private final String issuer;
    private final String mount;
    private final ECKey key;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Grant> codes = new ConcurrentHashMap<>();
    private final Set<String> idTokens = ConcurrentHashMap.newKeySet();
    private final Endpoints endpoints = new Endpoints();
    private volatile List<String> audiences = List.of();

    private RefOpenIdProvider(URI issuer) {
        this.issuer = issuer.toString().replaceAll("/$", "");
        this.mount = URI.create(this.issuer).getRawPath();
        try {
            this.key = new ECKeyGenerator(Curve.P_256).keyID("op-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate the provider's signing key", e);
        }
    }

    /** A provider served by another server's handler, with {@code issuer}, a URL with a path, as its issuer. */
    public static RefOpenIdProvider mounted(URI issuer) {
        if (issuer.getRawPath() == null || issuer.getRawPath().replaceAll("/$", "").isEmpty()) {
            throw new IllegalArgumentException("a mounted issuer has a path: " + issuer);
        }
        return new RefOpenIdProvider(issuer);
    }

    public Handler handler() {
        return endpoints;
    }

    public String issuer() {
        return issuer;
    }

    /** Where OpenID Connect Discovery section 4 puts the configuration: the issuer, then the well-known path. */
    public String discoveryUri() {
        return issuer + "/.well-known/openid-configuration";
    }

    public String jwksUri() {
        return issuer + "/jwks";
    }

    public String authorizationEndpoint() {
        return issuer + "/authorize";
    }

    public String tokenEndpoint() {
        return issuer + "/token";
    }

    /** Lets {@code username} sign in with {@code password}, as {@code subject}. */
    public void addUser(String username, String subject, String password) {
        users.put(username, new User(subject, password));
    }

    /**
     * Audiences every ID Token names besides the client, such as the authorization server a
     * client exchanges it at (lws10-authn-openid section 4: aud "SHOULD include the client
     * identifier and any additional target audience such as an authorization server").
     */
    public void alsoAudience(String... audience) {
        this.audiences = List.of(audience);
    }

    /**
     * Registers a client, or replaces the redirect URIs of one already registered.
     *
     * @throws IllegalArgumentException with a reason a developer can act on
     */
    public Client register(String clientId, List<String> redirectUris) {
        if (clientId == null || clientId.isEmpty() || clientId.length() > 512 || !absoluteUri(clientId)) {
            throw new IllegalArgumentException("the client identifier is an absolute URI, as lws10-core section 4 says it"
                    + " should be, of at most 512 characters");
        }
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new IllegalArgumentException("a client registers 1 to " + MAX_REDIRECT_URIS + " redirect URIs");
        }
        for (String uri : redirectUris) {
            String reason = redirectProblem(uri);
            if (reason != null) {
                throw new IllegalArgumentException("redirect URI " + uri + ": " + reason);
            }
        }
        synchronized (clients) {
            if (!clients.containsKey(clientId) && clients.size() >= MAX_CLIENTS) {
                throw new IllegalArgumentException("a session registers at most " + MAX_CLIENTS + " clients");
            }
            Client c = new Client(clientId, List.copyOf(redirectUris));
            clients.put(clientId, c);
            return c;
        }
    }

    /** The registered clients. */
    public List<Client> clients() {
        List<Client> out = new ArrayList<>(clients.values());
        out.sort(java.util.Comparator.comparing(Client::clientId));
        return out;
    }

    /** Whether this provider issued {@code token} as an ID Token. */
    public boolean issued(String token) {
        return token != null && idTokens.contains(token);
    }

    /** The OpenID Connect Discovery document. */
    public String discovery() {
        ObjectNode d = JSON.createObjectNode();
        d.put("issuer", issuer);
        d.put("authorization_endpoint", authorizationEndpoint());
        d.put("token_endpoint", tokenEndpoint());
        d.put("jwks_uri", jwksUri());
        d.putArray("response_types_supported").add("code");
        d.putArray("response_modes_supported").add("query");
        d.putArray("grant_types_supported").add("authorization_code");
        d.putArray("subject_types_supported").add("public");
        d.putArray("id_token_signing_alg_values_supported").add("ES256");
        d.putArray("scopes_supported").add("openid").add("webid");
        d.putArray("token_endpoint_auth_methods_supported").add("none");
        d.putArray("code_challenge_methods_supported").add("S256");
        d.putArray("claims_supported").add("sub").add("iss").add("aud").add("azp").add("exp").add("iat")
                .add("auth_time").add("nonce");
        d.put("authorization_response_iss_parameter_supported", true);
        return d.toString();
    }

    /** The public signing key, as served at {@link #jwksUri()}. */
    public String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    // ---- registration checks ----

    private static boolean absoluteUri(String s) {
        try {
            URI u = new URI(s);
            return u.isAbsolute() && s.chars().noneMatch(c -> c <= ' ' || c == 0x7f);
        } catch (Exception e) {
            return false;
        }
    }

    /** Why {@code uri} cannot be a redirect URI, or null when it can (RFC 6749 section 3.1.2). */
    private static String redirectProblem(String uri) {
        if (uri == null || uri.length() > 2048 || !absoluteUri(uri)) {
            return "not an absolute URI of at most 2048 characters";
        }
        URI u = URI.create(uri);
        if (u.getRawFragment() != null) {
            return "has a fragment, which RFC 6749 section 3.1.2 forbids";
        }
        if (UNSAFE_SCHEMES.contains(u.getScheme().toLowerCase(Locale.ROOT))) {
            return "its scheme cannot receive a redirect";
        }
        return null;
    }

    /** Whether {@code given} is one of the client's redirect URIs: exactly, or a loopback one on any port. */
    private static boolean redirectMatches(Client client, String given) {
        if (given == null) {
            return false;
        }
        for (String registered : client.redirectUris()) {
            if (registered.equals(given)) {
                return true;
            }
            try {
                URI r = URI.create(registered);
                URI g = URI.create(given);
                boolean loopback = "http".equals(r.getScheme()) && "http".equals(g.getScheme())
                        && ("127.0.0.1".equals(r.getHost()) || "[::1]".equals(r.getHost()))
                        && r.getHost().equals(g.getHost());
                if (loopback && java.util.Objects.equals(r.getRawPath(), g.getRawPath())
                        && java.util.Objects.equals(r.getRawQuery(), g.getRawQuery()) && g.getRawFragment() == null) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // not a URI: no match
            }
        }
        return false;
    }

    // ---- the endpoints ----

    private final class Endpoints extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getCanonicalPath();
            if (path == null || !path.startsWith(mount + "/")) {
                write(response, callback, 404, null, null);
                return true;
            }
            path = path.substring(mount.length());
            String method = request.getMethod();
            switch (path) {
                case "/.well-known/openid-configuration", "/jwks" -> {
                    if (!method.equals("GET") && !method.equals("HEAD")) {
                        response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD");
                        write(response, callback, 405, null, null);
                    } else {
                        write(response, callback, 200, "application/json",
                                path.equals("/jwks") ? jwks() : discovery());
                    }
                }
                case "/authorize" -> {
                    switch (method) {
                        case "GET" -> authorize(request, response, callback, query(request));
                        case "POST" -> signIn(request, response, callback, form(request));
                        default -> {
                            response.getHeaders().put(HttpHeader.ALLOW, "GET, POST");
                            write(response, callback, 405, null, null);
                        }
                    }
                }
                case "/token" -> {
                    if (!method.equals("POST")) {
                        response.getHeaders().put(HttpHeader.ALLOW, "POST");
                        write(response, callback, 405, null, null);
                    } else {
                        token(response, callback, form(request));
                    }
                }
                default -> write(response, callback, 404, null, null);
            }
            return true;
        }

        /** OpenID Connect Core section 3.1.2.1: check the request, then ask the user to sign in. */
        private void authorize(Request request, Response response, Callback callback, Map<String, String> p) {
            Client client = p.get("client_id") == null ? null : clients.get(p.get("client_id"));
            if (client == null) {
                page(response, callback, 400, "Unknown client", "<p class=\"error\">No client "
                        + code(p.get("client_id")) + " is registered with this session's OpenID Provider.</p>"
                        + "<p class=\"note\">Register it, with its redirect URI, on the session page.</p>");
                return;
            }
            String redirect = p.get("redirect_uri");
            if (!redirectMatches(client, redirect)) {
                page(response, callback, 400, "Unknown redirect URI", "<p class=\"error\">The redirect URI "
                        + code(redirect) + " is not one registered for " + code(client.clientId()) + ".</p>"
                        + "<p class=\"note\">Register it on the session page; it must match exactly.</p>");
                return;
            }
            String state = p.get("state");
            if (!"code".equals(p.get("response_type"))) {
                redirect(response, callback, redirect, error("unsupported_response_type",
                        "this provider supports the authorization code flow only", state));
                return;
            }
            if (p.get("scope") == null || !List.of(p.get("scope").split(" ")).contains("openid")) {
                redirect(response, callback, redirect, error("invalid_scope", "the scope must include openid", state));
                return;
            }
            if (p.get("code_challenge") == null || !"S256".equals(p.get("code_challenge_method"))) {
                redirect(response, callback, redirect, error("invalid_request",
                        "PKCE with code_challenge_method S256 is required", state));
                return;
            }
            prune();
            if (pending.size() >= MAX_PENDING) {
                page(response, callback, 503, "Busy", "<p class=\"error\">Too many sign-ins are in progress; try again"
                        + " in a few minutes.</p>");
                return;
            }
            String handle = randomToken();
            pending.put(handle, new Pending(client, redirect, state, p.get("nonce"), p.get("code_challenge"),
                    Instant.now().plus(SIGN_IN_TIME)));
            signInForm(response, callback, 200, handle, client, null);
        }

        /** The sign-in form's answer: on success, back to the client with a code. */
        private void signIn(Request request, Response response, Callback callback, Map<String, String> f) {
            String handle = f.get("request");
            Pending p = handle == null ? null : pending.get(handle);
            if (p == null || p.expires().isBefore(Instant.now())) {
                page(response, callback, 400, "Sign-in expired", "<p class=\"error\">This sign-in has expired or was"
                        + " already used.</p><p class=\"note\">Start again from your client.</p>");
                return;
            }
            User user = f.get("username") == null ? null : users.get(f.get("username"));
            if (user == null || f.get("password") == null || !MessageDigest.isEqual(
                    user.password().getBytes(StandardCharsets.UTF_8), f.get("password").getBytes(StandardCharsets.UTF_8))) {
                signInForm(response, callback, 200, handle, p.client(), "Wrong username or password.");
                return;
            }
            pending.remove(handle);
            prune();
            if (codes.size() >= MAX_PENDING) {
                redirect(response, callback, p.redirectUri(), error("temporarily_unavailable", "too many codes", p.state()));
                return;
            }
            String code = randomToken();
            Instant now = Instant.now();
            codes.put(code, new Grant(p.client(), p.redirectUri(), p.nonce(), p.challenge(), user.subject(), now,
                    now.plus(CODE_TIME)));
            Map<String, String> params = new LinkedHashMap<>();
            params.put("code", code);
            if (p.state() != null) {
                params.put("state", p.state());
            }
            // RFC 9207: the issuer in the response, so a client can tell which provider answered.
            params.put("iss", issuer);
            redirect(response, callback, p.redirectUri(), params);
        }

        /** RFC 6749 section 4.1.3, with PKCE (RFC 7636 section 4.6). */
        private void token(Response response, Callback callback, Map<String, String> f) {
            if (!"authorization_code".equals(f.get("grant_type"))) {
                tokenError(response, callback, "unsupported_grant_type", "only authorization_code is supported");
                return;
            }
            String code = f.get("code");
            if (code == null || f.get("redirect_uri") == null || f.get("client_id") == null
                    || f.get("code_verifier") == null) {
                tokenError(response, callback, "invalid_request", "code, redirect_uri, client_id and code_verifier are required");
                return;
            }
            Grant g = codes.remove(code);
            if (g == null || g.expires().isBefore(Instant.now())) {
                tokenError(response, callback, "invalid_grant", "the code is unknown, expired or already used");
                return;
            }
            if (!g.client().clientId().equals(f.get("client_id")) || !g.redirectUri().equals(f.get("redirect_uri"))) {
                tokenError(response, callback, "invalid_grant", "the code was issued to another client or redirect URI");
                return;
            }
            if (!g.challenge().equals(s256(f.get("code_verifier")))) {
                tokenError(response, callback, "invalid_grant", "the code_verifier does not match the code_challenge");
                return;
            }
            String idToken = idToken(g);
            idTokens.add(idToken);
            ObjectNode body = JSON.createObjectNode();
            // OAuth asks for an access token; this one is good for nothing but this provider, which has no
            // userinfo endpoint. The ID Token is what an LWS client exchanges.
            body.put("access_token", randomToken());
            body.put("token_type", "Bearer");
            body.put("expires_in", ID_TOKEN_TIME.toSeconds());
            body.put("scope", "openid");
            body.put("id_token", idToken);
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            response.getHeaders().put(HttpHeader.PRAGMA, "no-cache");
            write(response, callback, 200, "application/json", body.toString());
        }

        private String idToken(Grant g) {
            Instant now = Instant.now();
            List<String> aud = new ArrayList<>();
            aud.add(g.client().clientId());
            aud.addAll(audiences);
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(g.subject())
                    .audience(aud)
                    .claim("azp", g.client().clientId())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ID_TOKEN_TIME)))
                    .claim("auth_time", g.authTime().getEpochSecond())
                    .jwtID(randomToken());
            if (g.nonce() != null) {
                claims.claim("nonce", g.nonce());
            }
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT).build(), claims.build());
                jwt.sign(new ECDSASigner(key));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException("cannot sign an ID Token", e);
            }
        }

        private void tokenError(Response response, Callback callback, String error, String description) {
            ObjectNode body = JSON.createObjectNode();
            body.put("error", error);
            body.put("error_description", description);
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            write(response, callback, 400, "application/json", body.toString());
        }

        private Map<String, String> error(String error, String description, String state) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("error", error);
            params.put("error_description", description);
            if (state != null) {
                params.put("state", state);
            }
            params.put("iss", issuer);
            return params;
        }

        /** 303 to {@code redirectUri} with {@code params} added to its query (RFC 9700 section 4.12). */
        private void redirect(Response response, Callback callback, String redirectUri, Map<String, String> params) {
            StringBuilder target = new StringBuilder(redirectUri);
            char sep = URI.create(redirectUri).getRawQuery() == null ? '?' : '&';
            for (Map.Entry<String, String> e : params.entrySet()) {
                target.append(sep).append(e.getKey()).append('=')
                        .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                sep = '&';
            }
            response.getHeaders().put(HttpHeader.LOCATION, target.toString());
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            write(response, callback, 303, null, null);
        }

        private void signInForm(Response response, Callback callback, int status, String handle, Client client,
                                String problem) {
            StringBuilder names = new StringBuilder();
            users.keySet().stream().sorted().forEach(n -> names.append("<option>").append(escape(n)).append("</option>"));
            page(response, callback, status, "Sign in",
                    "<p>" + code(client.clientId()) + " asks you to sign in to this Touchstone session's OpenID Provider.</p>"
                    + (problem == null ? "" : "<p class=\"error\">" + escape(problem) + "</p>")
                    + "<form method=\"post\" action=\"authorize\">"
                    + "<input type=\"hidden\" name=\"request\" value=\"" + escape(handle) + "\">"
                    + "<label>Username<select name=\"username\">" + names + "</select></label>"
                    + "<label>Password<input type=\"password\" name=\"password\" autocomplete=\"current-password\""
                    + " required></label><button>Sign in</button></form>"
                    + "<p class=\"note\">The usernames and passwords are on the session page.</p>");
        }

        private void page(Response response, Callback callback, int status, String title, String main) {
            String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                    + "<title>" + escape(title) + " · Touchstone session</title><style>" + STYLE + "</style></head>"
                    + "<body><main><h1>" + escape(title) + "</h1>" + main + "</main></body></html>";
            response.getHeaders().put("Content-Security-Policy", CSP);
            response.getHeaders().put("X-Content-Type-Options", "nosniff");
            response.getHeaders().put("Referrer-Policy", "no-referrer");
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            write(response, callback, status, "text/html; charset=utf-8", html);
        }

        private void write(Response response, Callback callback, int status, String type, String body) {
            response.setStatus(status);
            if (body == null) {
                callback.succeeded();
                return;
            }
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, type);
            response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
        }
    }

    /** Drops sign-ins and codes that have expired. */
    private void prune() {
        Instant now = Instant.now();
        pending.values().removeIf(p -> p.expires().isBefore(now));
        codes.values().removeIf(g -> g.expires().isBefore(now));
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Base64(String text) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(Request request) {
        return parse(request.getHttpURI().getQuery());
    }

    private static Map<String, String> form(Request request) throws java.io.IOException {
        String type = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return Map.of();
        }
        try (InputStream in = Content.Source.asInputStream(request)) {
            return parse(new String(in.readNBytes(64 << 10), StandardCharsets.UTF_8));
        }
    }

    /** The first value of each parameter of an application/x-www-form-urlencoded string. */
    private static Map<String, String> parse(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.putIfAbsent(name, value);
            } catch (IllegalArgumentException e) {
                // a malformed escape: the parameter is ignored
            }
        }
        return out;
    }

    private static String code(String text) {
        return "<code>" + escape(text == null ? "(none)" : text) + "</code>";
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
