package com.ebremer.touchstone.fixtures.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ebremer.touchstone.fixtures.client.RefLwsClient.Agent;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Login;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;

/**
 * The reference client's HTTP and authentication, shared by its script ({@link RefLwsClient}) and
 * its adapter ({@link RefAdapter}): requests with an agent's token, and on a 401 the discovery of
 * the authorization server through the protected resource metadata the challenge names (RFC 9728),
 * the discovery checks of lws10-core section 5.2.3, and a token exchange (RFC 8693) with a
 * self-issued credential, an ID Token or a SAML assertion.
 *
 * <p>The authentication flaws of a broken twin live here: each makes its one mistake on the first
 * attempt only, as the twin's script always did.
 */
final class RefHttp {

    static final String LWS = "https://www.w3.org/ns/lws#";
    static final String FORM = "application/x-www-form-urlencoded";
    private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String JWT_TYPE = "urn:ietf:params:oauth:token-type:jwt";
    private static final String ID_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:id_token";
    private static final String SAML2_TYPE = "urn:ietf:params:oauth:token-type:saml2";
    private static final Pattern RESOURCE_METADATA = Pattern.compile("(?i)\\bresource_metadata\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern REQUEST_HANDLE = Pattern.compile("name=\"request\" value=\"([^\"]*)\"");
    static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;[^,<]*)*)");
    static final Pattern REL = Pattern.compile("(?i)\\brel\\s*=\\s*\"?([^\";,]+)\"?");

    /** The flaws that break a self-issued credential, or the type it is presented with. */
    private static final Set<Flaw> CREDENTIAL_FLAWS = EnumSet.of(Flaw.CID_UNSIGNED, Flaw.CID_WITHOUT_SUB,
            Flaw.CID_WITHOUT_ISS, Flaw.CID_WITHOUT_CLIENT_ID, Flaw.CID_CLIENT_ID_DIFFERS, Flaw.CID_AUDIENCE_WITHOUT_AS,
            Flaw.CID_WITHOUT_AUDIENCE, Flaw.CID_WITHOUT_EXP, Flaw.CID_WITHOUT_IAT, Flaw.CID_TYPED_AS_ID_TOKEN);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Flaw flaw;
    private final SecureRandom random = new SecureRandom();
    /** Each agent's access token now, by CID URL. */
    private final Map<String, String> tokens = new HashMap<>();
    /** ID Tokens the client was given rather than signing in for, by CID URL. */
    private final Map<String, String> idTokens = new HashMap<>();
    /** The token endpoint of each authorization server met, by issuer. */
    private final Map<String, URI> tokenEndpoints = new HashMap<>();
    /** The storage root each storage met names, by storage, or null where it names none: a client may cache it. */
    private final Map<String, Optional<URI>> storageRoots = new HashMap<>();
    /** Whether the twin has made its one mistake. */
    private boolean flawShown;
    /** The agents that have signed in at their OpenID Provider, by CID URL. */
    private final Set<String> signedIn = new HashSet<>();

    RefHttp(Flaw flaw) {
        this.flaw = flaw;
    }

    Flaw flaw() {
        return flaw;
    }

    /** An agent's access token, as the session handed it out. */
    void token(Agent as, String token) {
        tokens.put(as.cidUrl(), token);
    }

    /** Forgets an agent's access token, so that its next request is answered 401. */
    void forget(Agent as) {
        tokens.remove(as.cidUrl());
    }

    /** An ID Token the agent presents instead of signing in at its OpenID Provider. */
    void idToken(Agent as, String idToken) {
        idTokens.put(as.cidUrl(), idToken);
    }

    /** An answer, and what the client reads from it. */
    record Reply(URI url, int status, Map<String, List<String>> headers, String body) {

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }

        URI location() {
            String l = header("Location");
            if (l == null) {
                throw new IllegalStateException(url + " answered " + status + " without a Location");
            }
            return url.resolve(l);
        }

        String etag() {
            String etag = header("ETag");
            if (etag == null) {
                throw new IllegalStateException(url + " answered " + status + " without an ETag");
            }
            return etag;
        }

        boolean allows(String method) {
            return listed("Allow", method);
        }

        /** Whether a comma-separated header lists a value, compared as written. */
        boolean listed(String header, String value) {
            String list = header(header);
            if (list == null) {
                return false;
            }
            for (String m : list.split(",")) {
                if (m.trim().equals(value)) {
                    return true;
                }
            }
            return false;
        }

        /** The target of the first Link with this relation, resolved, or null. */
        URI link(String rel) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (!e.getKey().equalsIgnoreCase("Link")) {
                    continue;
                }
                for (String field : e.getValue()) {
                    Matcher m = LINK.matcher(field);
                    while (m.find()) {
                        Matcher r = REL.matcher(m.group(2));
                        if (r.find()) {
                            for (String each : r.group(1).trim().split("\\s+")) {
                                if (each.equalsIgnoreCase(rel)) {
                                    return url.resolve(m.group(1));
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    Map<String, URI> services(Reply description) throws IOException {
        Map<String, URI> out = new HashMap<>();
        for (JsonNode s : json.readTree(description.body()).path("service")) {
            JsonNode types = s.path("type");
            for (JsonNode t : types.isArray() ? types : json.createArrayNode().add(types)) {
                out.putIfAbsent(t.asText(), description.url().resolve(s.path("serviceEndpoint").asText()));
            }
        }
        return out;
    }

    /**
     * Sends a request as {@code as}, with its access token if it has one. On a 401 with a
     * challenge, it reads the protected resource metadata the challenge names, makes the
     * discovery checks of lws10-core section 5.2.3, authenticates for the storage the 401 links
     * to, and sends the request once more; a 401 that fails a check gets no token. A null
     * {@code as} sends no credential.
     */
    Reply send(String method, URI url, Agent as, String contentType, String body, String accept,
               Map<String, String> headers) throws IOException, InterruptedException {
        Reply r = raw(method, url, as == null ? null : tokens.get(as.cidUrl()), contentType, body, accept, headers);
        if (r.status() != 401 || as == null || (as.key() == null && as.login() == null && as.assertions() == null
                && !idTokens.containsKey(as.cidUrl()))) {
            return r;
        }
        String challenge = r.header("WWW-Authenticate");
        Matcher metadata = challenge == null ? null : RESOURCE_METADATA.matcher(challenge);
        URI storageLinked = r.link(LWS + "storage");
        if (metadata == null || !metadata.find() || storageLinked == null) {
            return r;
        }
        Discovery found = discover(url, r.url().resolve(metadata.group(1)), storageLinked);
        if (found == null) {
            return r;
        }
        if (!found.checked()) {
            if (flaw == Flaw.SKIPS_DISCOVERY_CHECKS && !flawShown) {
                flawShown = true;
                authenticate(as, found.issuer(), found.storage());
            }
            return r;
        }
        return authenticate(as, found.issuer(), found.storage())
                ? raw(method, url, tokens.get(as.cidUrl()), contentType, body, accept, headers) : r;
    }

    /**
     * What a 401 says about getting a token: the authorization server its protected resource
     * metadata names, the storage it links to, and whether the discovery checks passed.
     */
    private record Discovery(String issuer, String storage, boolean checked) {
    }

    /**
     * Reads the protected resource metadata at {@code metadataUrl} and checks, as lws10-core section
     * 5.2.3 requires, that its URL and the storage root of {@code storage} have the scheme and
     * authority of {@code requested}, and that its resource is {@code requested}. Null when it
     * names no authorization server, or is not on the requested URL's origin, which the client
     * then does not fetch.
     */
    private Discovery discover(URI requested, URI metadataUrl, URI storage) throws IOException, InterruptedException {
        if (!sameOrigin(metadataUrl, requested)) {
            return null;
        }
        Reply prm = send("GET", metadataUrl, null, null, null, "application/json", Map.of());
        if (prm.status() != 200) {
            return null;
        }
        JsonNode doc = json.readTree(prm.body());
        String issuer = null;
        for (JsonNode server : doc.path("authorization_servers")) {
            if (server.isTextual()) {
                issuer = server.asText();
                break;
            }
        }
        if (issuer == null) {
            return null;
        }
        boolean checked = requested.toString().equals(doc.path("resource").asText(null));
        Optional<URI> root = storageRoots.get(storage.toString());
        if (root == null) {
            Reply description = send("GET", storage, null, null, null, "application/lws+cid", Map.of());
            root = Optional.ofNullable(description.status() == 200 ? services(description).get("StorageRoot") : null);
            storageRoots.put(storage.toString(), root);
        }
        checked = checked && root.isPresent() && sameOrigin(root.get(), requested);
        return new Discovery(issuer, storage.toString(), checked);
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getRawAuthority() != null
                && a.getRawAuthority().equalsIgnoreCase(b.getRawAuthority());
    }

    // ---- authentication ----

    /**
     * Gets {@code as} a new access token for the storage {@code resource} from the authorization server
     * {@code issuer}: with a self-issued credential if it holds a key; else with an ID Token it was
     * given; else with an ID Token from its OpenID Provider the first time and a SAML assertion after
     * that, when it has both. Returns whether it got one.
     */
    private boolean authenticate(Agent as, String issuer, String resource) throws IOException, InterruptedException {
        URI endpoint = tokenEndpoint(issuer);
        Reply answer;
        if (as.key() != null) {
            if (CREDENTIAL_FLAWS.contains(flaw) && !flawShown) {
                flawShown = true;
                exchange(endpoint, selfIssued(as, issuer, flaw), flaw == Flaw.CID_TYPED_AS_ID_TOKEN ? ID_TOKEN_TYPE : JWT_TYPE,
                        resource);
            }
            answer = exchange(endpoint, selfIssued(as, issuer, Flaw.NONE), JWT_TYPE, resource);
        } else if (as.assertions() != null && (as.login() == null || signedIn.contains(as.cidUrl()))) {
            String assertion = as.assertions().next();
            if (flaw == Flaw.SAML_TYPED_AS_JWT && !flawShown) {
                flawShown = true;
                exchange(endpoint, assertion, JWT_TYPE, resource);
            }
            answer = exchange(endpoint, assertion, SAML2_TYPE, resource);
        } else {
            String idToken = idTokens.get(as.cidUrl());
            if (idToken == null) {
                signedIn.add(as.cidUrl());
                idToken = signIn(as);
            }
            if (flaw == Flaw.ID_TOKEN_TYPED_AS_JWT && !flawShown) {
                flawShown = true;
                exchange(endpoint, idToken, JWT_TYPE, resource);
            }
            answer = exchange(endpoint, idToken, ID_TOKEN_TYPE, resource);
        }
        if (answer.status() != 200) {
            return false;
        }
        tokens.put(as.cidUrl(), json.readTree(answer.body()).path("access_token").asText());
        return true;
    }

    /** The token endpoint, from the metadata RFC 8414 section 3.1 puts at the well-known URL of the issuer. */
    private URI tokenEndpoint(String issuer) throws IOException, InterruptedException {
        URI known = tokenEndpoints.get(issuer);
        if (known != null) {
            return known;
        }
        URI i = URI.create(issuer);
        String path = i.getRawPath() == null ? "" : i.getRawPath().replaceAll("/$", "");
        Reply metadata = send("GET", i.resolve("/.well-known/lws-configuration" + path), null, null, null,
                "application/json", Map.of());
        URI endpoint = metadata.url().resolve(json.readTree(metadata.body()).path("token_endpoint").asText());
        tokenEndpoints.put(issuer, endpoint);
        return endpoint;
    }

    /** RFC 8693 token exchange, the twin's one mistake in the request made on its first. */
    private Reply exchange(URI endpoint, String credential, String type, String resource)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", TOKEN_EXCHANGE);
        form.put("resource", resource);
        form.put("subject_token", credential);
        form.put("subject_token_type", type);
        if (!flawShown && (flaw == Flaw.TOKEN_REQUEST_WITHOUT_RESOURCE || flaw == Flaw.TOKEN_REQUEST_WITHOUT_SUBJECT_TOKEN)) {
            flawShown = true;
            Map<String, String> wrong = new LinkedHashMap<>(form);
            if (flaw == Flaw.TOKEN_REQUEST_WITHOUT_RESOURCE) {
                wrong.remove("resource");
            } else {
                wrong.put("assertion", wrong.remove("subject_token"));
            }
            send("POST", endpoint, null, FORM, encode(wrong), "application/json", Map.of());
        }
        return send("POST", endpoint, null, FORM, encode(form), "application/json", Map.of());
    }

    /**
     * A self-issued credential (lws10-authn-ssi-cid section 4): the agent's URI as sub, iss and
     * client_id, the authorization server as aud, a few minutes' life, signed ES256 with the key
     * its identity document lists, which kid names. With a flaw, the credential has that defect.
     */
    private String selfIssued(Agent as, String issuer, Flaw defect) throws IOException {
        long now = Instant.now().getEpochSecond();
        ObjectNode claims = json.createObjectNode();
        claims.put("sub", as.cidUrl());
        claims.put("iss", as.cidUrl());
        claims.put("client_id", defect == Flaw.CID_CLIENT_ID_DIFFERS ? "https://client.invalid/app" : as.cidUrl());
        claims.putArray("aud").add(defect == Flaw.CID_AUDIENCE_WITHOUT_AS ? "https://as.invalid/" : issuer);
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("jti", randomToken());
        switch (defect) {
            case CID_WITHOUT_SUB -> claims.remove("sub");
            case CID_WITHOUT_ISS -> claims.remove("iss");
            case CID_WITHOUT_CLIENT_ID -> claims.remove("client_id");
            case CID_WITHOUT_AUDIENCE -> claims.remove("aud");
            case CID_WITHOUT_EXP -> claims.remove("exp");
            case CID_WITHOUT_IAT -> claims.remove("iat");
            default -> { }
        }
        try {
            ECKey key = ECKey.parse(as.key());
            if (defect == Flaw.CID_UNSIGNED) {
                ObjectNode header = json.createObjectNode().put("alg", "none").put("kid", key.getKeyID());
                return base64(json.writeValueAsBytes(header)) + "." + base64(json.writeValueAsBytes(claims)) + ".";
            }
            JWSObject jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID())
                    .type(JOSEObjectType.JWT).build(), new Payload(json.writeValueAsString(claims)));
            jws.sign(new ECDSASigner(key));
            return jws.serialize();
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException e) {
            throw new IOException("cannot sign a credential with the agent's key", e);
        }
    }

    /**
     * An ID Token from the agent's OpenID Provider, which its identity document names: the
     * authorization code flow with PKCE, signing in on the provider's form as a person would.
     */
    String signIn(Agent as) throws IOException, InterruptedException {
        Login login = as.login();
        Reply document = send("GET", URI.create(as.cidUrl()), null, null, null, "application/cid, application/json", Map.of());
        String issuer = null;
        for (JsonNode service : json.readTree(document.body()).path("service")) {
            if (service.path("type").toString().contains("OpenIdProvider")) {
                issuer = service.path("serviceEndpoint").asText().replaceAll("/$", "");
            }
        }
        if (issuer == null) {
            throw new IOException(as.cidUrl() + " names no OpenID Provider");
        }
        Reply discovery = send("GET", URI.create(issuer + "/.well-known/openid-configuration"), null, null, null,
                "application/json", Map.of());
        JsonNode config = json.readTree(discovery.body());
        String verifier = randomToken();
        String state = randomToken();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", login.clientId());
        query.put("redirect_uri", login.redirectUri().toString());
        query.put("scope", "openid webid");
        query.put("state", state);
        query.put("nonce", randomToken());
        query.put("code_challenge", s256(verifier));
        query.put("code_challenge_method", "S256");
        URI authorize = URI.create(config.path("authorization_endpoint").asText() + "?" + encode(query));
        Reply page = send("GET", authorize, null, null, null, "text/html", Map.of());
        Matcher handle = REQUEST_HANDLE.matcher(page.body());
        if (!handle.find()) {
            throw new IOException("the OpenID Provider showed no sign-in form: " + page.status());
        }
        Map<String, String> signIn = new LinkedHashMap<>();
        signIn.put("request", handle.group(1));
        signIn.put("username", login.username());
        signIn.put("password", login.password());
        Reply back = send("POST", authorize.resolve("authorize"), null, FORM, encode(signIn), "text/html", Map.of());
        String location = back.header("Location");
        Map<String, String> answer = location == null || !location.contains("?") ? Map.of()
                : decode(location.substring(location.indexOf('?') + 1));
        if (answer.get("code") == null || !state.equals(answer.get("state")) || !issuer.equals(answer.get("iss"))) {
            throw new IOException("the sign-in did not come back with a code: " + back.status() + " " + location);
        }
        Map<String, String> redeem = new LinkedHashMap<>();
        redeem.put("grant_type", "authorization_code");
        redeem.put("code", answer.get("code"));
        redeem.put("redirect_uri", login.redirectUri().toString());
        redeem.put("client_id", login.clientId());
        redeem.put("code_verifier", verifier);
        Reply tokensReply = send("POST", URI.create(config.path("token_endpoint").asText()), null, FORM, encode(redeem),
                "application/json", Map.of());
        return json.readTree(tokensReply.body()).path("id_token").asText();
    }

    private String randomToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return base64(b);
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String s256(String verifier) {
        try {
            return base64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(Map<String, String> params) {
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

    private Reply raw(String method, URI url, String token, String contentType, String body, String accept,
                      Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
            if (flaw == Flaw.TOKEN_ALSO_IN_OWN_HEADER) {
                b.header("X-Access-Token", token);
            }
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (accept != null) {
            b.header("Accept", accept);
        }
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(url, r.statusCode(), new LinkedHashMap<>(r.headers().map()), r.body());
    }
}
