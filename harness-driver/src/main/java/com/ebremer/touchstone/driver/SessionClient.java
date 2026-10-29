package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The driver's side of a client session's API (CLIENT-TESTING.md section 4.4): it starts the
 * session, hands out credentials for the drives' variables, starts tasks, and reads the results.
 * The session key stays in this object: it is never logged, put in a drive's variables or given to
 * an adapter (CLIENT-TESTING.md section 14.3).
 */
public final class SessionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REQUEST_HANDLE = Pattern.compile("name=\"request\" value=\"([^\"]*)\"");
    /** The redirect URI the driver registers to sign in at the session's OpenID Provider; nothing listens there. */
    private static final String REDIRECT = "http://127.0.0.1/touchstone-driver/callback";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();
    private final URI api;
    private final String key;
    private final String id;
    private final URI storage;
    private final URI page;
    private String openidClient;

    private SessionClient(URI api, String key, String id, URI storage, URI page) {
        this.api = api;
        this.key = key;
        this.id = id;
        this.storage = storage;
        this.page = page;
    }

    /**
     * Starts a session on a client-session service.
     *
     * @param service  the service's base, such as {@code https://host/touchstone/clients}
     * @param settings the session's settings: the client under test, the areas, and that it is driven
     */
    public static SessionClient start(URI service, ObjectNode settings) throws IOException, InterruptedException {
        SessionClient bootstrap = new SessionClient(null, null, null, null, null);
        HttpResponse<String> r = bootstrap.send("POST", URI.create(service + "/sessions"), null, settings.toString(),
                "application/json");
        if (r.statusCode() != 201) {
            throw new IOException("the client-session service refused the session: " + r.statusCode() + " " + r.body());
        }
        JsonNode s = JSON.readTree(r.body());
        return new SessionClient(URI.create(s.path("api").asText()), s.path("key").asText(), s.path("id").asText(),
                URI.create(s.path("storage").asText()), URI.create(s.path("page").asText()));
    }

    public String id() {
        return id;
    }

    /** The storage URL, the only URL the client is given. */
    public URI storage() {
        return storage;
    }

    /** The session page, without its key. */
    public URI page() {
        return page;
    }

    /** The session page with its key in the fragment, which opens it: for the operator only. */
    public String pageWithKey() {
        return page + "#key=" + key;
    }

    public JsonNode describe() throws IOException, InterruptedException {
        return get("");
    }

    /** An identity's CID URL, verification method, private key and password. */
    public JsonNode credentials(String name) throws IOException, InterruptedException {
        return get("/credentials/" + name);
    }

    /** A fresh access token for an identity. */
    public String token(String name) throws IOException, InterruptedException {
        return post("/tokens/" + name, "{}").path("access_token").asText();
    }

    /** A SAML 2.0 assertion about an identity, base64url-encoded. */
    public String samlAssertion(String name) throws IOException, InterruptedException {
        return post("/assertions/" + name, "{}").path("assertion").asText();
    }

    public void startTask(String rule) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", URI.create(api + "/tasks/" + rule), key, null, null);
        if (r.statusCode() != 204) {
            throw new IOException("starting the task of " + rule + " was answered " + r.statusCode());
        }
    }

    public JsonNode results() throws IOException, InterruptedException {
        return get("/results");
    }

    /** One of the session's exports: earl or junit. */
    public String export(String format) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", URI.create(api + "/results?format=" + format), key, null, null);
        if (r.statusCode() != 200) {
            throw new IOException("the " + format + " export was answered " + r.statusCode());
        }
        return r.body();
    }

    /** Waits until the session has no notification awaiting an inbox's answer, for at most {@code max}. */
    public void settleDeliveries(Duration max) throws IOException, InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end && describe().path("deliveries").path("inFlight").asInt() > 0) {
            Thread.sleep(100);
        }
    }

    /**
     * An ID Token about an identity from the session's OpenID Provider: the driver registers a client
     * there once, and signs in as the identity with the authorization code flow and PKCE, as a
     * person would on the provider's form.
     */
    public String idToken(String name) throws IOException, InterruptedException {
        JsonNode session = describe();
        if (openidClient == null) {
            ObjectNode registration = JSON.createObjectNode();
            registration.putArray("redirect_uris").add(REDIRECT);
            openidClient = post("/clients", registration.toString()).path("client_id").asText();
        }
        JsonNode secrets = credentials(name);
        HttpResponse<String> d = send("GET", URI.create(session.path("openidProvider").path("discovery").asText()), null,
                null, null);
        JsonNode config = JSON.readTree(d.body());
        String verifier = randomToken();
        String state = randomToken();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", openidClient);
        query.put("redirect_uri", REDIRECT);
        query.put("scope", "openid webid");
        query.put("state", state);
        query.put("nonce", randomToken());
        query.put("code_challenge", base64(sha256(verifier)));
        query.put("code_challenge_method", "S256");
        URI authorize = URI.create(config.path("authorization_endpoint").asText() + "?" + form(query));
        HttpResponse<String> form = send("GET", authorize, null, null, null);
        Matcher handle = REQUEST_HANDLE.matcher(form.body());
        if (!handle.find()) {
            throw new IOException("the session's OpenID Provider showed no sign-in form: " + form.statusCode());
        }
        Map<String, String> signIn = new LinkedHashMap<>();
        signIn.put("request", handle.group(1));
        signIn.put("username", secrets.path("username").asText());
        signIn.put("password", secrets.path("password").asText());
        HttpResponse<String> back = send("POST", authorize.resolve("authorize"), null, form(signIn),
                "application/x-www-form-urlencoded");
        String location = back.headers().firstValue("Location").orElse("");
        Map<String, String> answer = location.contains("?") ? decode(location.substring(location.indexOf('?') + 1)) : Map.of();
        if (answer.get("code") == null || !state.equals(answer.get("state"))) {
            throw new IOException("signing in at the session's OpenID Provider did not come back with a code");
        }
        Map<String, String> redeem = new LinkedHashMap<>();
        redeem.put("grant_type", "authorization_code");
        redeem.put("code", answer.get("code"));
        redeem.put("redirect_uri", REDIRECT);
        redeem.put("client_id", openidClient);
        redeem.put("code_verifier", verifier);
        HttpResponse<String> tokens = send("POST", URI.create(config.path("token_endpoint").asText()), null, form(redeem),
                "application/x-www-form-urlencoded");
        String idToken = JSON.readTree(tokens.body()).path("id_token").asText("");
        if (idToken.isEmpty()) {
            throw new IOException("the session's OpenID Provider gave no ID Token: " + tokens.statusCode());
        }
        return idToken;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", URI.create(api + path), key, null, null);
        if (r.statusCode() != 200) {
            throw new IOException("the session API answered " + r.statusCode() + " to GET " + (path.isEmpty() ? "/" : path));
        }
        return JSON.readTree(r.body());
    }

    private JsonNode post(String path, String body) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", URI.create(api + path), key, body, "application/json");
        if (r.statusCode() / 100 != 2) {
            throw new IOException("the session API answered " + r.statusCode() + " to POST " + path);
        }
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> send(String method, URI url, String bearer, String body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String randomToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return base64(b);
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((k, v) -> out.append(out.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }

    private static Map<String, String> decode(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.putIfAbsent(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
