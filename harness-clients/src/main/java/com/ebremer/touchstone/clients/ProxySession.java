package com.ebremer.touchstone.clients;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * A session's proxy (CLIENT-TESTING.md section 10): it forwards the client's requests to a real
 * server behind the service, unchanged, and answers with the server's answers, unchanged, so that
 * the session records and judges a client talking to that server.
 *
 * <p>The server does not say what a URL is, as the session's own storage does, so the proxy
 * infers each exchange's role from what the server has handed out (OBSERVATION.md section 11):
 * the services its storage description lists, the endpoints its authorization server's metadata
 * names, the linksets and pages its Link headers point to, the resources a service created, and
 * the type an answer declares. It injects the four faults a proxy can inject alone. Rules that
 * need what only the session's own servers know are inapplicable ({@link #unavailable}).
 */
final class ProxySession {

    /** The faults a proxy can inject without the server's cooperation. */
    static final List<String> FAULTS = List.of("methodNotAllowed", "lostCreateResponse", "pageGone", "tokenExpired");

    /** The answer the proxy keeps of a server's response, in bytes; a larger one is refused with 502. */
    static final int MAX_ANSWER = 16 << 20;

    /** Annotations only the session's own servers can compute (OBSERVATION.md section 11). */
    private static final Set<String> UNKNOWN_TERMS = Set.of("credentialSource", "credential", "audienceIncludesAs",
            "identifiersAgree", "deliverySignature", "inboxShared", "containerEmpty");
    /** Roles only the session's own servers have. */
    private static final Set<String> UNKNOWN_ROLES = Set.of("delivery", "keyDocument", "decoy", "identityDocument",
            "opDiscovery", "opJwks", "opAuthorize", "opToken");
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host",
            "content-length", "content-type", "origin", "access-control-request-method",
            "access-control-request-headers", "accept-encoding", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "forwarded");
    private static final String LWS = "https://www.w3.org/ns/lws#";
    private static final String LWS_CID = "application/lws+cid";
    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;[^,<]*)*)");
    private static final Pattern REL = Pattern.compile("(?i)\\brel\\s*=\\s*\"?([^\";,]+)\"?");
    private static final Pattern REALM = Pattern.compile("(?i)\\brealm\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern AS_URI = Pattern.compile("(?i)\\bas_uri\\s*=\\s*\"([^\"]*)\"");
    private static final ObjectMapper JSON = new ObjectMapper();

    final ProxyTargets.ProxyTarget target;
    private final String wellKnown;
    private final Recorder recorder;
    private final Set<String> armed = ConcurrentHashMap.newKeySet();
    /** Fingerprints of the tokens tokenExpired refused, which stay refused. */
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    /** What the proxy learned the server's URLs are, by URL without its fragment. */
    private final Map<String, String> endpoints = new ConcurrentHashMap<>();
    private final Map<String, String> created = new ConcurrentHashMap<>();
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final Set<String> linksets = ConcurrentHashMap.newKeySet();
    private volatile String challengeRealm;
    private volatile String challengeAsUri;

    /**
     * @param wellKnown the public path prefix of the target's authorization server metadata,
     *     {@code /.well-known/lws-configuration{base path}/p/{id}}
     */
    ProxySession(ProxyTargets.ProxyTarget target, String wellKnown, Recorder recorder) {
        this.target = target;
        this.wellKnown = wellKnown;
        this.recorder = recorder;
    }

    /**
     * The rules a proxy session cannot judge (OBSERVATION.md section 11): those whose conditions
     * use a fact only the session's own servers know, or a role only they have, or whose task arms
     * a fault the proxy cannot inject.
     */
    static Set<String> unavailable(ClientRules rules) {
        Set<String> out = new LinkedHashSet<>();
        for (RuleDefinition r : rules.rules()) {
            Set<String> terms = new LinkedHashSet<>();
            Set<String> roles = new LinkedHashSet<>();
            walk(r.observe(), terms, roles);
            walk(r.expect(), terms, roles);
            terms.retainAll(UNKNOWN_TERMS);
            roles.retainAll(UNKNOWN_ROLES);
            boolean fault = r.task() != null && r.task().arm() != null && !FAULTS.contains(r.task().arm());
            if (!terms.isEmpty() || !roles.isEmpty() || fault) {
                out.add(r.name());
            }
        }
        return out;
    }

    private static void walk(JsonNode node, Set<String> terms, Set<String> roles) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                terms.add(e.getKey());
                if (e.getKey().equals("role")) {
                    if (e.getValue().isArray()) {
                        e.getValue().forEach(v -> roles.add(v.asText()));
                    } else {
                        roles.add(e.getValue().asText());
                    }
                }
                walk(e.getValue(), terms, roles);
            });
        } else if (node.isArray()) {
            node.forEach(n -> walk(n, terms, roles));
        }
    }

    boolean arm(String term) {
        if (!FAULTS.contains(term)) {
            return false;
        }
        armed.add(term);
        return true;
    }

    List<String> armed() {
        return FAULTS.stream().filter(armed::contains).toList();
    }

    /** Which of the session's servers a public URL addresses: the authorization server, or the storage. */
    String server(String url) {
        String role = known(url, null);
        return role != null && role.startsWith("as") ? "authorizationServer" : "storage";
    }

    /** Whether {@code path} is the target's authorization server metadata. */
    boolean isWellKnown(String path) {
        return path.equals(wellKnown) || path.startsWith(wellKnown + "/");
    }

    /** The backend URL a public request is forwarded to. */
    URI backendFor(String path, String query) {
        String rest;
        if (isWellKnown(path)) {
            URI b = target.backend();
            rest = b.getScheme() + "://" + b.getRawAuthority() + path;
        } else {
            String prefixPath = URI.create(target.prefix()).getRawPath();
            rest = target.backend() + path.substring(prefixPath.length());
        }
        return URI.create(query == null ? rest : rest + "?" + query);
    }

    /**
     * The handler that forwards a request to the server, or answers it with an armed fault, and
     * sets the request attributes the recorder reads: the role, the subject, and the fault.
     *
     * @param address the client's address, which the server gets in X-Forwarded-For
     * @param publicBase the service's public base, which the server gets in X-Forwarded-Host and -Proto
     */
    Handler handler(HttpClient http, String address, URI publicBase) {
        return new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String path = request.getHttpURI().getPath();
                String query = request.getHttpURI().getQuery();
                String url = publicBase.getScheme() + "://" + publicBase.getRawAuthority() + path
                        + (query == null ? "" : "?" + query);
                String method = request.getMethod();
                String accept = request.getHeaders().get(HttpHeader.ACCEPT);
                String before = known(url, accept);
                String bearer = bearer(request);
                String fingerprint = bearer == null ? null : Redaction.fingerprint(bearer);
                boolean storage = !isWellKnown(path) && !"authorizationServer".equals(server(url));

                // Faults the proxy injects without forwarding.
                boolean refusedBefore = fingerprint != null && revoked.contains(fingerprint);
                if (storage && fingerprint != null && (refusedBefore || armed.remove("tokenExpired"))) {
                    revoked.add(fingerprint);
                    role(request, before == null ? recorded(url) : before);
                    if (!refusedBefore) {
                        request.setAttribute(RefLwsServer.FAULT_ATTRIBUTE, "tokenExpired");
                    }
                    response.setStatus(401);
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, challenge());
                    response.write(true, ByteBuffer.allocate(0), callback);
                    return true;
                }
                if (method.equals("PUT") && "linkset".equals(before)
                        && allows(recorder.advertisedFor(url).get("Allow"), "PUT") && armed.remove("methodNotAllowed")) {
                    role(request, before);
                    request.setAttribute(RefLwsServer.FAULT_ATTRIBUTE, "methodNotAllowed");
                    response.setStatus(405);
                    response.getHeaders().put(HttpHeader.ALLOW, without(recorder.advertisedFor(url).get("Allow"), "PUT"));
                    response.write(true, ByteBuffer.allocate(0), callback);
                    return true;
                }
                if ((method.equals("GET") || method.equals("HEAD")) && "searchPage".equals(before)
                        && armed.remove("pageGone")) {
                    role(request, before);
                    request.setAttribute(RefLwsServer.FAULT_ATTRIBUTE, "pageGone");
                    response.setStatus(410);
                    response.write(true, ByteBuffer.allocate(0), callback);
                    return true;
                }
                boolean lose = method.equals("POST") && "container".equals(before == null ? recorded(url) : before)
                        && armed.remove("lostCreateResponse");

                byte[] body;
                try (java.io.InputStream in = Content.Source.asInputStream(request)) {
                    body = in.readAllBytes();
                }
                org.eclipse.jetty.client.Request forward = http.newRequest(backendFor(path, query)).method(method)
                        .timeout(30, TimeUnit.SECONDS);
                String type = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                if (body.length > 0 || type != null) {
                    forward.body(new BytesRequestContent(type == null ? "application/octet-stream" : type, body));
                }
                forward.headers(h -> {
                    for (HttpField f : request.getHeaders()) {
                        if (!HOP_BY_HOP.contains(f.getLowerCaseName())) {
                            h.add(f.getName(), f.getValue());
                        }
                    }
                    h.put("X-Forwarded-For", address);
                    h.put("X-Forwarded-Host", publicBase.getRawAuthority());
                    h.put("X-Forwarded-Proto", publicBase.getScheme());
                });
                new CompletableResponseListener(forward, MAX_ANSWER).send().whenComplete((answer, failure) -> {
                    try {
                        if (answer == null) {
                            role(request, before == null ? "unknown" : before);
                            response.setStatus(502);
                            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                            response.write(true, ByteBuffer.wrap(("the server behind the proxy did not answer: "
                                    + (failure == null ? "no answer" : failure.getMessage())).getBytes(StandardCharsets.UTF_8)),
                                    callback);
                            return;
                        }
                        byte[] content = answer.getContent() == null ? new byte[0] : answer.getContent();
                        String text = textual(answer.getHeaders().get(HttpHeader.CONTENT_TYPE))
                                ? new String(content, StandardCharsets.UTF_8) : null;
                        String role = infer(url, method, accept, answer.getStatus(), answer.getHeaders(), before);
                        learn(url, role, method, answer.getStatus(), answer.getHeaders(), text);
                        role(request, role);
                        if (answer.getStatus() != 401 && bearer != null) {
                            String subject = subject(bearer);
                            if (subject != null) {
                                request.setAttribute(RefLwsServer.SUBJECT_ATTRIBUTE, subject);
                            }
                        }
                        if (lose) {
                            request.setAttribute(RefLwsServer.FAULT_ATTRIBUTE, "lostCreateResponse");
                            response.setStatus(503);
                            response.write(true, ByteBuffer.allocate(0), callback);
                            return;
                        }
                        response.setStatus(answer.getStatus());
                        for (HttpField f : answer.getHeaders()) {
                            String name = f.getLowerCaseName();
                            if (!HOP_BY_HOP.contains(name) || name.equals("content-type")) {
                                if (!name.startsWith("access-control-")) {
                                    response.getHeaders().add(f.getName(), f.getValue());
                                }
                            }
                        }
                        response.write(true, ByteBuffer.wrap(method.equals("HEAD") ? new byte[0] : content), callback);
                    } catch (RuntimeException e) {
                        callback.failed(e);
                    }
                });
                return true;
            }
        };
    }

    /** The challenge for a refused token: the server's own realm and as_uri, as it last gave them. */
    private String challenge() {
        StringBuilder c = new StringBuilder("Bearer ");
        if (challengeRealm != null) {
            c.append("realm=\"").append(challengeRealm).append("\", ");
        }
        if (challengeAsUri != null) {
            c.append("as_uri=\"").append(challengeAsUri).append("\", ");
        }
        return c.append("error=\"invalid_token\"").toString();
    }

    /** The role an earlier answer gave this URL, or unknown. */
    private String recorded(String url) {
        String r = recorder.roleOf(url);
        return r == null ? "unknown" : r;
    }

    /**
     * What the proxy knows a URL to be before the server answers (OBSERVATION.md section 11): an
     * endpoint the storage description or the authorization server's metadata named, a resource a
     * service created, a linkset, a page, or the storage description; null when it knows nothing.
     */
    String known(String url, String accept) {
        String key = withoutFragment(url);
        String bare = withoutQuery(key);
        String path = URI.create(bare).getRawPath();
        if (path != null && isWellKnown(path)) {
            return "asMetadata";
        }
        String endpoint = endpoints.get(bare);
        if (endpoint != null) {
            return endpoint.equals("typeSearch") && !key.equals(bare) ? "searchPage" : endpoint;
        }
        String made = created.get(bare);
        if (made != null) {
            return made;
        }
        for (Map.Entry<String, String> e : endpoints.entrySet()) {
            String singular = switch (e.getValue()) {
                case "subscriptions" -> "subscription";
                case "accessGrants" -> "accessGrant";
                case "accessRequests" -> "accessRequest";
                default -> null;
            };
            String service = e.getKey().endsWith("/") ? e.getKey() : e.getKey() + "/";
            if (singular != null && bare.startsWith(service)) {
                return singular;
            }
        }
        if (linksets.contains(bare)) {
            return "linkset";
        }
        String page = pages.get(key);
        if (page != null) {
            return page;
        }
        if (bare.equals(target.storage().toString()) && accept != null && accept.contains(LWS_CID)) {
            return "storageDescription";
        }
        return null;
    }

    /** The role of an answered exchange: what the proxy knew, else what the answer says, else what an earlier answer said. */
    String infer(String url, String method, String accept, int status, HttpFields headers, String before) {
        if (before != null) {
            return before;
        }
        String type = essence(headers.get(HttpHeader.CONTENT_TYPE));
        if (status >= 200 && status < 300) {
            // A POST that is not to a service creates in a container. Its answer's type link, if it
            // has one, is the new resource's, not the container's.
            if (method.equals("POST")) {
                return "container";
            }
            if (LWS_CID.equals(type)) {
                return "storageDescription";
            }
            for (String link : headers.getValuesList(HttpHeader.LINK)) {
                Matcher m = LINK.matcher(link);
                while (m.find()) {
                    if (rels(m.group(2)).contains("type")) {
                        String t = m.group(1);
                        if (t.equals(LWS + "Container")) {
                            return "container";
                        }
                        if (t.equals(LWS + "DataResource")) {
                            return "dataResource";
                        }
                    }
                }
            }
            if (method.equals("PUT") && status == 201) {
                return "dataResource";
            }
        }
        if (status == 404 || status == 410) {
            return "unknown";
        }
        // The storage's root URL serves two things: its description, asked for by media type, and
        // the root container. An earlier answer for one says nothing about a request for the other.
        if (withoutQuery(withoutFragment(url)).equals(target.storage().toString())) {
            return accept != null && accept.contains(LWS_CID) && (method.equals("GET") || method.equals("HEAD"))
                    ? "storageDescription" : "container";
        }
        String earlier = recorder.roleOf(url);
        if (earlier != null && !earlier.equals("unknown")) {
            return earlier;
        }
        return "unknown";
    }

    /** Learns from an answer what the server's URLs are (OBSERVATION.md section 11). */
    void learn(String url, String role, String method, int status, HttpFields headers, String text) {
        URI base = URI.create(withoutFragment(url));
        for (String link : headers.getValuesList(HttpHeader.LINK)) {
            Matcher m = LINK.matcher(link);
            while (m.find()) {
                String to = withoutFragment(resolve(base, m.group(1)));
                for (String rel : rels(m.group(2))) {
                    switch (rel) {
                        case "linkset" -> linksets.add(withoutQuery(to));
                        case "first", "next", "prev", "previous", "last" -> pages.putIfAbsent(to,
                                Set.of("typeIndex", "typeSearch", "searchPage").contains(role) ? "searchPage" : "page");
                        default -> {
                        }
                    }
                }
            }
        }
        if (status == 401) {
            for (String c : headers.getValuesList(HttpHeader.WWW_AUTHENTICATE)) {
                Matcher r = REALM.matcher(c);
                if (r.find()) {
                    challengeRealm = r.group(1);
                }
                Matcher a = AS_URI.matcher(c);
                if (a.find()) {
                    challengeAsUri = a.group(1);
                }
            }
        }
        String location = headers.get(HttpHeader.LOCATION);
        if (status == 201 && location != null && method.equals("POST")) {
            String singular = switch (role) {
                case "subscriptions" -> "subscription";
                case "accessGrants" -> "accessGrant";
                case "accessRequests" -> "accessRequest";
                default -> null;
            };
            if (singular != null) {
                created.put(withoutQuery(withoutFragment(resolve(base, location))), singular);
            }
        }
        if (text == null || status < 200 || status >= 300) {
            return;
        }
        try {
            if (role.equals("storageDescription")) {
                JsonNode doc = JSON.readTree(text);
                for (JsonNode s : doc.path("service")) {
                    String endpoint = s.path("serviceEndpoint").asText(null);
                    String r = serviceRole(s.path("type"));
                    if (endpoint != null && r != null) {
                        endpoints.put(withoutQuery(withoutFragment(resolve(base, endpoint))), r);
                    }
                }
            } else if (role.equals("asMetadata")) {
                JsonNode doc = JSON.readTree(text);
                if (doc.path("token_endpoint").isTextual()) {
                    endpoints.put(withoutFragment(resolve(base, doc.get("token_endpoint").asText())), "asToken");
                }
                if (doc.path("jwks_uri").isTextual()) {
                    endpoints.put(withoutFragment(resolve(base, doc.get("jwks_uri").asText())), "asJwks");
                }
            }
        } catch (Exception e) {
            // an answer that does not parse teaches nothing
        }
    }

    private static String serviceRole(JsonNode type) {
        List<String> types = new ArrayList<>();
        if (type.isArray()) {
            type.forEach(t -> types.add(t.asText()));
        } else if (type.isTextual()) {
            types.add(type.asText());
        }
        for (String t : types) {
            String local = t.startsWith(LWS) ? t.substring(LWS.length()) : t;
            String role = switch (local) {
                case "AccessGrantService" -> "accessGrants";
                case "AccessRequestService" -> "accessRequests";
                case "NotificationService" -> "subscriptions";
                case "TypeIndexService" -> "typeIndex";
                case "TypeSearchService" -> "typeSearch";
                default -> null;
            };
            if (role != null) {
                return role;
            }
        }
        return null;
    }

    /** The subject of a JWT access token, read without verifying it: the server has, or it would have refused it. */
    static String subject(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return claims.path("sub").isTextual() ? claims.get("sub").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String bearer(Request request) {
        String auth = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = auth.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void role(Request request, String role) {
        request.setAttribute(RefLwsServer.ROLE_ATTRIBUTE, role);
    }

    private static Set<String> rels(String params) {
        Set<String> out = new LinkedHashSet<>();
        Matcher r = REL.matcher(params);
        if (r.find()) {
            for (String rel : r.group(1).trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                out.add(rel);
            }
        }
        return out;
    }

    private static boolean allows(String allow, String method) {
        if (allow == null) {
            return false;
        }
        for (String m : allow.split(",")) {
            if (m.trim().equals(method)) {
                return true;
            }
        }
        return false;
    }

    private static String without(String allow, String method) {
        List<String> out = new ArrayList<>();
        if (allow != null) {
            for (String m : allow.split(",")) {
                if (!m.trim().isEmpty() && !m.trim().equals(method)) {
                    out.add(m.trim());
                }
            }
        }
        return String.join(", ", out);
    }

    private static String resolve(URI base, String reference) {
        try {
            return base.resolve(reference).toString();
        } catch (IllegalArgumentException e) {
            return reference;
        }
    }

    private static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    private static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static String essence(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        int semi = mediaType.indexOf(';');
        return (semi < 0 ? mediaType : mediaType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean textual(String contentType) {
        if (contentType == null) {
            return false;
        }
        String t = contentType.toLowerCase(Locale.ROOT);
        return t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("turtle")
                || t.contains("cid");
    }
}
