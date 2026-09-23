package com.ebremer.touchstone.fixtures.lws;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ebremer.touchstone.fixtures.as.RefAuthorizationServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * In-memory reference LWS server: the target of the conformance self-test loop (DECISIONS.md
 * D-0015). It implements the WD-20260921 behaviour the definitions test:
 * <ul>
 *   <li>containers with {@code items}/{@code totalItems} listings, each member's {@code format},
 *       {@code size} and {@code modified}, and lws+json/ld+json/json conneg with
 *       {@code Vary: Accept};</li>
 *   <li>a storage description served as {@code application/lws+cid} at the storage URI,
 *       advertised by {@code rel="https://www.w3.org/ns/lws#storage"} on every response,
 *       including a 401, and naming the root, access grant and access request services;</li>
 *   <li>strong ETags with 304 and 412, containment-consistent create and delete, 409 on
 *       deleting a non-empty container unless {@code Depth: infinity}, JSON Merge Patch on data
 *       resources, single byte ranges;</li>
 *   <li>a linkset per resource, stored, with its own ETag, patchable with merge patch and
 *       refusing PUT with 405, removed with its resource;</li>
 *   <li>access grants and access requests (section 11) as LWS containers, and authorization
 *       by ownership or grant: {@code foaf:Agent} is the public.</li>
 * </ul>
 *
 * <p>Container representations name their context by IRI, as the draft's example and every
 * real server do (D-0026/D-0040). Notifications are not implemented (D-0041), so no
 * notification service is advertised: a reference that advertised a service it does not
 * provide would be lying to the tests that check it.
 *
 * <p>Three auth modes (D-0017): {@link AuthMode#OPEN} (no authentication), {@link
 * AuthMode#SECURED} (validates Bearer tokens against the reference authorization server; 401
 * with a conforming challenge for a missing or invalid token, 403 for a valid agent without
 * access; the storage owner and each resource's creator have full access, anyone else what a
 * grant gives) and {@link AuthMode#BROKEN} (auth theater: never challenges or forbids).
 */
public final class RefLwsServer implements AutoCloseable {

    private static final String LWS_NS = "https://www.w3.org/ns/lws#";
    private static final String LWS_CONTEXT = "https://www.w3.org/ns/lws/v1";
    private static final String CID_CONTEXT = "https://www.w3.org/ns/cid/v1";
    private static final String FOAF_AGENT = "http://xmlns.com/foaf/0.1/Agent";
    private static final String LWS_CID = "application/lws+cid";
    private static final String LWS_JSON = "application/lws+json";
    private static final String LINKSET_JSON = "application/linkset+json";
    private static final String MERGE_PATCH = "application/merge-patch+json";
    /** The storage URI, which here is also the storage root container. */
    private static final String STORAGE_PATH = "/";
    /** The access grant and access request services: containers outside the storage root's listing. */
    private static final String GRANTS = "/_grants/";
    private static final String REQUESTS = "/_requests/";
    private static final Set<String> ACTIONS = Set.of("read", "modify", "create", "delete");

    private final AuthMode authMode;
    private final Server server;
    private final ServerConnector connector;
    private final ConcurrentMap<String, Node> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Record> grants = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Record> requests = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String grantsEtag = newEtag();
    private volatile String requestsEtag = newEtag();
    private volatile TokenValidator validator;
    private volatile String asUri;
    private volatile String storageOwner;

    private static final class Node {
        final boolean container;
        volatile byte[] bytes;
        volatile String contentType;
        volatile String etag = newEtag();
        volatile String owner;
        volatile Instant modified = now();
        volatile ObjectNode linkset;
        volatile String linksetEtag = newEtag();
        final Set<String> children = ConcurrentHashMap.newKeySet();

        Node(boolean container) {
            this.container = container;
        }
    }

    /** A stored access grant or access request: its document, its policies, who made it. */
    private record Record(String id, ObjectNode document, List<Policy> policies, String author, String etag) {
    }

    /** One AccessPolicy: actions, an assignee, and the resources it covers. */
    private record Policy(Set<String> actions, String assignee, Set<String> targets) {
    }

    private RefLwsServer(AuthMode authMode) {
        this.authMode = authMode;
        this.server = new Server();
        this.connector = new ServerConnector(server);
        // A run opens many connections at once; the OS default backlog refused some of them.
        connector.setAcceptQueueSize(256);
        server.addConnector(connector);
        server.setHandler(new LwsHandler());
        store.put(STORAGE_PATH, new Node(true));
    }

    /** Open mode: no authentication. */
    public static RefLwsServer start(int port) {
        return startIn(AuthMode.OPEN, port);
    }

    /**
     * Secured mode: Bearer tokens are validated against {@code as}, and {@code owner} (an agent
     * IRI) controls the storage. The realm is this server's base URI, which a valid token's
     * {@code aud} must name, alone; the server registers itself with {@code as} as a resource.
     */
    public static RefLwsServer startSecured(int port, RefAuthorizationServer as, String owner) {
        RefLwsServer server = startIn(AuthMode.SECURED, port);
        String realm = server.realm();
        try {
            server.validator = new TokenValidator(as.issuer(), as.jwksUri().toURL(), realm);
        } catch (Exception e) {
            server.close();
            throw new IllegalStateException("cannot build token validator", e);
        }
        server.asUri = as.issuer();
        server.storageOwner = owner;
        server.store.get(STORAGE_PATH).owner = owner;
        as.addResource(realm);
        return server;
    }

    /** Broken mode: the deliberately non-compliant twin (validates nothing, forbids nothing). */
    public static RefLwsServer startBroken(int port) {
        return startIn(AuthMode.BROKEN, port);
    }

    private static RefLwsServer startIn(AuthMode mode, int port) {
        RefLwsServer instance = new RefLwsServer(mode);
        instance.connector.setPort(port);
        try {
            instance.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start reference LWS server", e);
        }
        return instance;
    }

    public AuthMode authMode() {
        return authMode;
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + connector.getLocalPort() + "/");
    }

    /** The realm access tokens must be issued for: the storage's base URI. */
    public String realm() {
        return baseUri().toString();
    }

    /**
     * What a run left behind: every stored resource but the root, and every access grant and
     * access request. The self-test loop checks it is empty once a run has cleaned up.
     */
    public List<String> residue() {
        List<String> out = new ArrayList<>();
        store.keySet().stream().filter(p -> !p.equals(STORAGE_PATH)).sorted().forEach(out::add);
        grants.keySet().forEach(id -> out.add(GRANTS + id));
        requests.keySet().forEach(id -> out.add(REQUESTS + id));
        return out;
    }

    public void join() throws InterruptedException {
        server.join();
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newEtag() {
        return '"' + UUID.randomUUID().toString() + '"';
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private final class LwsHandler extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getCanonicalPath();
            String method = request.getMethod();

            String subject = null;
            if (authMode == AuthMode.SECURED) {
                String bearer = bearerToken(request);
                if (bearer != null) {
                    try {
                        subject = validator.validate(bearer);
                    } catch (TokenValidator.InvalidTokenException e) {
                        challenge(request, response, callback, "invalid_token");
                        return true;
                    }
                }
            }

            if (path.startsWith(GRANTS) || path.startsWith(REQUESTS)) {
                new Registry(path.startsWith(GRANTS)).handle(request, response, callback, path, method, subject);
                return true;
            }

            String resourcePath = linksetSubject(path);
            boolean linkset = resourcePath != null;
            String target = linkset ? resourcePath : path;
            if (authMode == AuthMode.SECURED) {
                String action = switch (method) {
                    case "GET", "HEAD", "OPTIONS" -> "read";
                    case "POST" -> linkset ? "modify" : "create";
                    case "DELETE" -> linkset ? "modify" : "delete";
                    default -> "modify";
                };
                Node node = store.get(target);
                if (node == null ? subject == null : !allowed(action, node, absolute(request, target), subject)) {
                    if (subject == null) {
                        challenge(request, response, callback, null);
                    } else {
                        status(response, callback, 403);
                    }
                    return true;
                }
            }

            if (linkset) {
                linksetResource(request, response, callback, resourcePath, method);
                return true;
            }
            switch (method) {
                case "GET" -> read(request, response, callback, path, true);
                case "HEAD" -> read(request, response, callback, path, false);
                case "POST" -> create(request, response, callback, path, subject);
                case "PUT" -> update(request, response, callback, path);
                case "PATCH" -> patch(request, response, callback, path);
                case "DELETE" -> delete(request, response, callback, path);
                default -> methodNotAllowed(response, callback, "GET, HEAD, POST, PUT, PATCH, DELETE");
            }
            return true;
        }

        // ---- auth ----

        private String bearerToken(Request request) {
            String header = request.getHeaders().get("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return null;
            }
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }

        /**
         * The storage owner and the resource's creator may do anything with it; anyone else
         * what a grant gives them. A grant names the resource itself (whether a grant on a
         * container reaches its members is an open question, definitions/README.md).
         */
        private boolean allowed(String action, Node node, String uri, String subject) {
            if (subject != null && (subject.equals(storageOwner) || subject.equals(node.owner))) {
                return true;
            }
            for (Record grant : grants.values()) {
                for (Policy p : grant.policies()) {
                    boolean assignee = p.assignee().equals(FOAF_AGENT) || p.assignee().equals(subject);
                    if (assignee && p.actions().contains(action) && p.targets().contains(uri)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 401 with a Bearer challenge naming the authorization server and realm, and the storage link. */
        private void challenge(Request request, Response response, Callback callback, String error) {
            StringBuilder c = new StringBuilder("Bearer");
            if (asUri != null) {
                c.append(" as_uri=\"").append(asUri).append('"');
                c.append(", realm=\"").append(validator.realm()).append('"');
            }
            if (error != null) {
                c.append(", error=\"").append(error).append('"');
            }
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, c.toString());
            // The storage link on a 401 is a SHOULD (section 9.2 notes): it is how a client that
            // was refused still finds the storage description, and with it the services.
            response.getHeaders().add("Link", storageLink(request));
            status(response, callback, 401);
        }

        private String storageLink(Request request) {
            return "<" + absolute(request, STORAGE_PATH) + ">; rel=\"" + LWS_NS + "storage\"";
        }

        // ---- read ----

        private void read(Request request, Response response, Callback callback, String path, boolean withBody) {
            Node node = store.get(path);
            if (node == null) {
                status(response, callback, 404);
                return;
            }
            String ifNoneMatch = request.getHeaders().get("If-None-Match");
            if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(node.etag))) {
                response.getHeaders().put(HttpHeader.ETAG, node.etag);
                addResourceLinks(request, response, path, node);
                status(response, callback, 304);
                return;
            }
            byte[] body;
            String contentType;
            if (STORAGE_PATH.equals(path)) {
                // "Requests for the storage URI MUST return a document that conforms to the
                // storage description resource data model with a media type of
                // application/lws+cid, unless content negotiation requires a different format."
                contentType = negotiateStorage(request.getHeaders().get("Accept"));
                if (contentType == null) {
                    status(response, callback, 406);
                    return;
                }
                response.getHeaders().put(HttpHeader.VARY, "Accept");
                body = LWS_CID.equals(contentType) ? storageDescription(request) : listing(request, path, node);
            } else if (node.container) {
                contentType = negotiate(request.getHeaders().get("Accept"));
                if (contentType == null) {
                    status(response, callback, 406);
                    return;
                }
                // "Because the Content-Type of a container response depends on the request's
                // Accept header, these responses SHOULD include a Vary: Accept header."
                response.getHeaders().put(HttpHeader.VARY, "Accept");
                body = listing(request, path, node);
            } else {
                contentType = node.contentType;
                body = node.bytes;
            }
            if (!node.container) {
                // "Servers MUST support range requests per [RFC7233] for partial retrieval."
                response.getHeaders().put("Accept-Ranges", "bytes");
                String range = request.getHeaders().get("Range");
                if (range != null) {
                    long[] r = parseRange(range, body.length);
                    if (r == null) {
                        response.getHeaders().put("Content-Range", "bytes */" + body.length);
                        addResourceLinks(request, response, path, node);
                        status(response, callback, 416);
                        return;
                    }
                    int from = (int) r[0];
                    int to = (int) r[1];
                    byte[] slice = new byte[to - from + 1];
                    System.arraycopy(body, from, slice, 0, slice.length);
                    response.setStatus(206);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
                    response.getHeaders().put(HttpHeader.ETAG, node.etag);
                    response.getHeaders().put("Content-Range", "bytes " + from + "-" + to + "/" + body.length);
                    addResourceLinks(request, response, path, node);
                    send(response, callback, slice, withBody);
                    return;
                }
            }
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            addResourceLinks(request, response, path, node);
            send(response, callback, body, withBody);
        }

        private void send(Response response, Callback callback, byte[] body, boolean withBody) {
            if (withBody) {
                response.write(true, ByteBuffer.wrap(body), callback);
            } else {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
                callback.succeeded();
            }
        }

        /**
         * One byte range against an entity of {@code length} bytes, as {@code first,last}
         * inclusive, or null when it cannot be satisfied (RFC 9110 section 14.1.1). Only the
         * single-range forms are handled, which is what "minimally support" asks for; a
         * multipart range is left unsatisfied rather than answered wrongly.
         */
        private static long[] parseRange(String header, int length) {
            if (header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0 || length == 0) {
                return null;
            }
            String spec = header.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String lo = spec.substring(0, dash).trim();
            String hi = spec.substring(dash + 1).trim();
            try {
                if (lo.isEmpty()) {
                    long n = Long.parseLong(hi);
                    if (n <= 0) {
                        return null;
                    }
                    return new long[] {Math.max(0, length - n), length - 1};
                }
                long from = Long.parseLong(lo);
                if (from >= length) {
                    return null;
                }
                long to = hi.isEmpty() ? length - 1 : Math.min(Long.parseLong(hi), length - 1);
                return to < from ? null : new long[] {from, to};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // ---- linksets ----

        /** The path of the resource whose linkset {@code path} is, or null when it is not a linkset. */
        private String linksetSubject(String path) {
            if (!path.endsWith(".meta")) {
                return null;
            }
            String stem = path.substring(0, path.length() - ".meta".length());
            if (store.containsKey(stem)) {
                return stem;
            }
            if (store.containsKey(stem + "/")) {
                return stem + "/";
            }
            // The resource is gone, and its linkset with it: the path answers 404 as a linkset.
            return stem.isEmpty() ? null : stem;
        }

        /**
         * A resource's linkset (RFC 9264): GET and HEAD, and PATCH with JSON Merge Patch, which
         * the Allow and Accept-Patch headers advertise. PUT is not supported, so it is 405 with
         * the methods that are.
         */
        private void linksetResource(Request request, Response response, Callback callback, String path, String method)
                throws Exception {
            Node node = store.get(path);
            if (node == null) {
                status(response, callback, 404);
                return;
            }
            response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD, PATCH");
            response.getHeaders().put("Accept-Patch", MERGE_PATCH);
            switch (method) {
                case "GET", "HEAD" -> {
                    byte[] body = mapper.writeValueAsBytes(linksetDocument(request, path, node));
                    response.setStatus(200);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, LINKSET_JSON);
                    response.getHeaders().put(HttpHeader.ETAG, node.linksetEtag);
                    send(response, callback, body, method.equals("GET"));
                }
                case "PATCH" -> {
                    String contentType = request.getHeaders().get("Content-Type");
                    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(MERGE_PATCH)) {
                        status(response, callback, 415);
                        return;
                    }
                    String ifMatch = request.getHeaders().get("If-Match");
                    if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.linksetEtag)) {
                        status(response, callback, 412);
                        return;
                    }
                    JsonNode patch;
                    try (InputStream in = Content.Source.asInputStream(request)) {
                        patch = mapper.readTree(in.readAllBytes());
                    } catch (Exception e) {
                        status(response, callback, 400);
                        return;
                    }
                    JsonNode merged = mergePatch(linksetDocument(request, path, node), patch);
                    if (!merged.isObject() || !merged.path("linkset").isArray()) {
                        status(response, callback, 422);
                        return;
                    }
                    node.linkset = (ObjectNode) merged;
                    node.linksetEtag = newEtag();
                    response.getHeaders().put(HttpHeader.ETAG, node.linksetEtag);
                    status(response, callback, 204);
                }
                default -> methodNotAllowed(response, callback, "GET, HEAD, PATCH");
            }
        }

        private ObjectNode linksetDocument(Request request, String path, Node node) {
            if (node.linkset != null) {
                return node.linkset.deepCopy();
            }
            ObjectNode doc = mapper.createObjectNode();
            doc.putArray("linkset").addObject().put("anchor", absolute(request, path));
            return doc;
        }

        // ---- create ----

        private void create(Request request, Response response, Callback callback, String path, String subject)
                throws Exception {
            Node parent = store.get(path);
            if (parent == null) {
                status(response, callback, 404);
                return;
            }
            if (!parent.container) {
                methodNotAllowed(response, callback, "GET, HEAD, PUT, PATCH, DELETE");
                return;
            }
            boolean isContainer = request.getHeaders().getValuesList("Link").stream()
                    .anyMatch(v -> v.contains(LWS_NS + "Container") && v.contains("rel=\"type\""));
            String slug = sanitize(request.getHeaders().get("Slug"));
            String childPath;
            Node child = new Node(isContainer);
            child.owner = subject;
            if (!isContainer) {
                try (InputStream in = Content.Source.asInputStream(request)) {
                    child.bytes = in.readAllBytes();
                }
                String contentType = request.getHeaders().get("Content-Type");
                child.contentType = contentType != null ? contentType : "application/octet-stream";
            }
            synchronized (store) {
                childPath = path + unique(path, slug, isContainer) + (isContainer ? "/" : "");
                store.put(childPath, child);
            }
            parent.children.add(childPath);
            parent.etag = newEtag();
            parent.modified = now();

            response.setStatus(201);
            response.getHeaders().put(HttpHeader.LOCATION, absolute(request, childPath));
            response.getHeaders().put(HttpHeader.ETAG, child.etag);
            response.getHeaders().add("Link", "<" + absolute(request, path) + ">; rel=\"up\"");
            response.getHeaders().add("Link", "<" + LWS_NS + (isContainer ? "Container" : "DataResource")
                    + ">; rel=\"type\"");
            response.getHeaders().add("Link", "<" + absolute(request, linksetOf(childPath))
                    + ">; rel=\"linkset\"; type=\"" + LINKSET_JSON + "\"");
            callback.succeeded();
        }

        // ---- update ----

        /**
         * A conditional PUT is honoured; an unconditional one is accepted, because the 21
         * September 2026 draft dropped the MUST that answered it with 428 ("Clients SHOULD use
         * conditional requests").
         */
        private void update(Request request, Response response, Callback callback, String path) throws Exception {
            Node node = store.get(path);
            if (node == null) {
                status(response, callback, 404);
                return;
            }
            if (node.container) {
                methodNotAllowed(response, callback, "GET, HEAD, POST, DELETE");
                return;
            }
            String ifMatch = request.getHeaders().get("If-Match");
            if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(response, callback, 412);
                return;
            }
            try (InputStream in = Content.Source.asInputStream(request)) {
                node.bytes = in.readAllBytes();
            }
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType != null) {
                node.contentType = contentType;
            }
            touch(path, node);
            response.setStatus(204);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            callback.succeeded();
        }

        /** A changed member changes its own validator and time, and its container's. */
        private void touch(String path, Node node) {
            node.etag = newEtag();
            node.modified = now();
            Node parent = store.get(parentOf(path));
            if (parent != null) {
                parent.etag = newEtag();
            }
        }

        // ---- patch ----

        /**
         * JSON Merge Patch (RFC 7386), the baseline every server has to understand. Containers
         * are not patchable: their representation is derived from containment, not stored.
         * {@code If-Match} is honoured when sent and not demanded.
         */
        private void patch(Request request, Response response, Callback callback, String path) throws Exception {
            Node node = store.get(path);
            if (node == null) {
                status(response, callback, 404);
                return;
            }
            if (node.container) {
                methodNotAllowed(response, callback, "GET, HEAD, POST, DELETE");
                return;
            }
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(MERGE_PATCH)) {
                response.getHeaders().put("Accept-Patch", MERGE_PATCH);
                status(response, callback, 415);
                return;
            }
            String ifMatch = request.getHeaders().get("If-Match");
            if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(response, callback, 412);
                return;
            }
            byte[] body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readAllBytes();
            }
            JsonNode target;
            JsonNode patch;
            try {
                target = node.bytes == null || node.bytes.length == 0
                        ? mapper.createObjectNode() : mapper.readTree(node.bytes);
                patch = mapper.readTree(body);
            } catch (Exception e) {
                // The stored representation is not JSON, or the patch itself is malformed.
                status(response, callback, 415);
                return;
            }
            node.bytes = mapper.writeValueAsBytes(mergePatch(target, patch));
            node.contentType = "application/json";
            touch(path, node);
            response.setStatus(204);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            callback.succeeded();
        }

        /**
         * RFC 7386 section 2: a non-object patch replaces the target outright; otherwise each
         * member is merged recursively, and a null member REMOVES the name.
         */
        private JsonNode mergePatch(JsonNode target, JsonNode patch) {
            if (!patch.isObject()) {
                return patch;
            }
            ObjectNode merged = target != null && target.isObject()
                    ? (ObjectNode) target.deepCopy() : mapper.createObjectNode();
            patch.properties().forEach(entry -> {
                if (entry.getValue().isNull()) {
                    merged.remove(entry.getKey());
                } else {
                    merged.set(entry.getKey(), mergePatch(merged.get(entry.getKey()), entry.getValue()));
                }
            });
            return merged;
        }

        // ---- delete ----

        private void delete(Request request, Response response, Callback callback, String path) {
            Node node = store.get(path);
            if (node == null) {
                status(response, callback, 404);
                return;
            }
            if (path.equals(STORAGE_PATH)) {
                methodNotAllowed(response, callback, "GET, HEAD, POST");
                return;
            }
            String ifMatch = request.getHeaders().get("If-Match");
            if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(response, callback, 412);
                return;
            }
            if (node.container && !node.children.isEmpty()) {
                String depth = request.getHeaders().get("Depth");
                if (!"infinity".equalsIgnoreCase(depth)) {
                    status(response, callback, 409);
                    return;
                }
            }
            removeRecursively(path);
            Node parent = store.get(parentOf(path));
            if (parent != null) {
                parent.children.remove(path);
                parent.etag = newEtag();
                parent.modified = now();
            }
            status(response, callback, 204);
        }

        private void removeRecursively(String path) {
            Node node = store.remove(path);
            if (node != null && node.container) {
                for (String child : List.copyOf(node.children)) {
                    removeRecursively(child);
                }
            }
        }

        // ---- representations ----

        private byte[] listing(Request request, String path, Node node) {
            ObjectNode root = mapper.createObjectNode();
            // The context is sent by IRI, as the draft's own example does and as a real server
            // does; harness-core resolves it from its bundled copy, offline (D-0026).
            root.put("@context", LWS_CONTEXT);
            root.put("id", absolute(request, path));
            root.put("type", "Container");
            List<String> children = new ArrayList<>(node.children);
            children.sort(String::compareTo);
            ArrayNode items = mapper.createArrayNode();
            for (String childPath : children) {
                Node child = store.get(childPath);
                if (child == null) {
                    continue;
                }
                ObjectNode item = items.addObject();
                item.put("id", absolute(request, childPath));
                item.put("type", child.container ? "Container" : "DataResource");
                if (!child.container) {
                    // "format: The media type of the resource ... MUST be present for
                    // DataResources"; size and modified SHOULD be.
                    item.put("format", child.contentType);
                    item.put("size", child.bytes == null ? 0 : child.bytes.length);
                }
                item.put("modified", child.modified.toString());
            }
            root.put("totalItems", items.size());
            root.set("items", items);
            return bytes(root);
        }

        /**
         * The storage description: a Controlled Identifier document extended with the LWS
         * vocabulary, naming the storage by its canonical URI and its services.
         */
        private byte[] storageDescription(Request request) {
            String storage = absolute(request, STORAGE_PATH);
            ObjectNode root = mapper.createObjectNode();
            ArrayNode context = root.putArray("@context");
            context.add(CID_CONTEXT);
            context.add(LWS_CONTEXT);
            root.put("id", storage);
            root.put("type", "Storage");
            ArrayNode services = root.putArray("service");
            service(services, storage + "#storage-root", "StorageRoot", storage);
            service(services, storage + "#access-grants", "AccessGrantService", absolute(request, GRANTS));
            service(services, storage + "#access-requests", "AccessRequestService", absolute(request, REQUESTS));
            return bytes(root);
        }

        private void service(ArrayNode services, String id, String type, String endpoint) {
            ObjectNode s = services.addObject();
            s.put("id", id);
            s.put("type", type);
            s.put("serviceEndpoint", endpoint);
        }

        private void addResourceLinks(Request request, Response response, String path, Node node) {
            if (!path.equals(STORAGE_PATH)) {
                response.getHeaders().add("Link", "<" + absolute(request, parentOf(path)) + ">; rel=\"up\"");
            }
            // "All responses to GET and HEAD requests targeting storage resources MUST include a
            // Link header whose target is the canonical URI of the storage".
            response.getHeaders().add("Link", storageLink(request));
            response.getHeaders().add("Link", "<" + LWS_NS + (node.container ? "Container" : "DataResource")
                    + ">; rel=\"type\"");
            // The creation clause names rel="linkset" beside rel="up", and a client has no other
            // way to find where a resource's metadata is edited: there is no path convention it
            // is entitled to assume.
            response.getHeaders().add("Link", "<" + absolute(request, linksetOf(path))
                    + ">; rel=\"linkset\"; type=\"" + LINKSET_JSON + "\"");
        }

        /** A resource's linkset lives beside it; the suffix is this fixture's convention, not the spec's. */
        private static String linksetOf(String path) {
            return path.endsWith("/") ? path.substring(0, path.length() - 1) + ".meta" : path + ".meta";
        }

        /**
         * Conneg at the storage URI, where {@code application/lws+cid} is the default rather
         * than one option among equals.
         */
        private String negotiateStorage(String accept) {
            if (accept == null || accept.isBlank() || accept.contains("*/*") || accept.contains(LWS_CID)) {
                return LWS_CID;
            }
            return negotiate(accept);
        }

        private String negotiate(String accept) {
            if (accept == null || accept.isBlank() || accept.contains("*/*")) {
                return LWS_JSON;
            }
            for (String candidate : List.of(LWS_JSON, "application/ld+json", "application/json")) {
                if (accept.contains(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private String absolute(Request request, String path) {
            return URI.create(request.getHttpURI().asString()).resolve(path).toString();
        }

        private String parentOf(String path) {
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
        }

        private String sanitize(String slug) {
            if (slug == null || slug.isBlank()) {
                return "resource";
            }
            String clean = slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            return clean.isBlank() ? "resource" : clean;
        }

        private String unique(String parentPath, String name, boolean container) {
            String candidate = name;
            int i = 1;
            while (store.containsKey(parentPath + candidate + (container ? "/" : ""))
                    || store.containsKey(parentPath + candidate + (container ? "" : "/"))) {
                i++;
                candidate = name + "-" + i;
            }
            return candidate;
        }

        private byte[] bytes(JsonNode node) {
            try {
                return mapper.writeValueAsBytes(node);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void status(Response response, Callback callback, int code) {
            response.setStatus(code);
            callback.succeeded();
        }

        private void methodNotAllowed(Response response, Callback callback, String allow) {
            response.setStatus(405);
            response.getHeaders().put(HttpHeader.ALLOW, allow);
            callback.succeeded();
        }

        /**
         * The access grant service or the access request service (section 11): an LWS
         * container of the grants or requests made through it. Only the storage owner may grant
         * access or read what was granted; any authenticated agent may ask for access, and may
         * read and withdraw its own requests.
         */
        private final class Registry {

            private final boolean grantsService;
            private final String base;
            private final ConcurrentMap<String, Record> records;
            private final String type;

            Registry(boolean grantsService) {
                this.grantsService = grantsService;
                this.base = grantsService ? GRANTS : REQUESTS;
                this.records = grantsService ? grants : requests;
                this.type = grantsService ? "AccessGrant" : "AccessRequest";
            }

            void handle(Request request, Response response, Callback callback, String path, String method,
                        String subject) throws Exception {
                String id = path.substring(base.length());
                boolean secured = authMode == AuthMode.SECURED;
                boolean owner = !secured || (subject != null && subject.equals(storageOwner));
                if (id.isEmpty()) {
                    switch (method) {
                        case "GET", "HEAD" -> {
                            if (!owner) {
                                deny(request, response, callback, subject);
                                return;
                            }
                            container(request, response, callback, method.equals("GET"));
                        }
                        case "POST" -> {
                            if (secured && (subject == null || (grantsService && !owner))) {
                                deny(request, response, callback, subject);
                                return;
                            }
                            create(request, response, callback, subject);
                        }
                        default -> methodNotAllowed(response, callback, "GET, HEAD, POST");
                    }
                    return;
                }
                Record record = records.get(id);
                if (record == null) {
                    if (secured && subject == null) {
                        challenge(request, response, callback, null);
                    } else {
                        status(response, callback, 404);
                    }
                    return;
                }
                boolean mine = owner || (subject != null && subject.equals(record.author()));
                if (!mine) {
                    deny(request, response, callback, subject);
                    return;
                }
                switch (method) {
                    case "GET", "HEAD" -> {
                        response.setStatus(200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, LWS_JSON);
                        response.getHeaders().put(HttpHeader.ETAG, record.etag());
                        response.getHeaders().add("Link", "<" + absolute(request, base) + ">; rel=\"up\"");
                        response.getHeaders().add("Link", storageLink(request));
                        send(response, callback, bytes(record.document()), method.equals("GET"));
                    }
                    case "DELETE" -> {
                        records.remove(id);
                        bump();
                        status(response, callback, 204);
                    }
                    default -> methodNotAllowed(response, callback, "GET, HEAD, DELETE");
                }
            }

            private void deny(Request request, Response response, Callback callback, String subject) {
                if (subject == null) {
                    challenge(request, response, callback, null);
                } else {
                    status(response, callback, 403);
                }
            }

            private void container(Request request, Response response, Callback callback, boolean withBody) {
                ObjectNode root = mapper.createObjectNode();
                root.put("@context", LWS_CONTEXT);
                root.put("id", absolute(request, base));
                root.put("type", "Container");
                ArrayNode items = root.putArray("items");
                records.keySet().stream().sorted().forEach(id -> {
                    ObjectNode item = items.addObject();
                    item.put("id", absolute(request, base + id));
                    item.put("type", "DataResource");
                    item.put("format", LWS_JSON);
                });
                root.put("totalItems", items.size());
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, LWS_JSON);
                response.getHeaders().put(HttpHeader.ETAG, grantsService ? grantsEtag : requestsEtag);
                response.getHeaders().add("Link", "<" + LWS_NS + "Container>; rel=\"type\"");
                response.getHeaders().add("Link", storageLink(request));
                send(response, callback, bytes(root), withBody);
            }

            private void create(Request request, Response response, Callback callback, String subject)
                    throws Exception {
                JsonNode body;
                try (InputStream in = Content.Source.asInputStream(request)) {
                    body = mapper.readTree(in.readAllBytes());
                } catch (Exception e) {
                    status(response, callback, 400);
                    return;
                }
                List<Policy> policies = body == null || !body.isObject() || !hasType(body.path("type"), type)
                        ? null : policies(body.path("access"));
                if (policies == null) {
                    status(response, callback, 400);
                    return;
                }
                String id = UUID.randomUUID().toString();
                ObjectNode document = ((ObjectNode) body).deepCopy();
                document.put("id", absolute(request, base + id));
                records.put(id, new Record(id, document, grantsService ? policies : List.of(), subject, newEtag()));
                bump();
                response.setStatus(201);
                response.getHeaders().put(HttpHeader.LOCATION, absolute(request, base + id));
                callback.succeeded();
            }

            private void bump() {
                if (grantsService) {
                    grantsEtag = newEtag();
                } else {
                    requestsEtag = newEtag();
                }
            }

            /** The AccessPolicy entries of {@code access}, or null when they are malformed. */
            private List<Policy> policies(JsonNode access) {
                if (!access.isArray() || access.isEmpty()) {
                    return null;
                }
                List<Policy> out = new ArrayList<>();
                for (JsonNode p : access) {
                    Set<String> actions = new LinkedHashSet<>();
                    for (JsonNode a : p.path("action").isArray() ? p.path("action") : List.of(p.path("action"))) {
                        if (!ACTIONS.contains(a.asText())) {
                            return null;
                        }
                        actions.add(a.asText());
                    }
                    String assignee = p.path("assignee").asText("");
                    JsonNode value = p.path("target").path("value");
                    Set<String> targets = new LinkedHashSet<>();
                    for (JsonNode t : value.isArray() ? value : List.of(value)) {
                        if (t.isTextual()) {
                            targets.add(t.asText());
                        }
                    }
                    if (actions.isEmpty() || assignee.isEmpty() || targets.isEmpty()) {
                        return null;
                    }
                    out.add(new Policy(Set.copyOf(actions), assignee, Set.copyOf(targets)));
                }
                return out;
            }

            private boolean hasType(JsonNode type, String wanted) {
                for (JsonNode t : type.isArray() ? type : List.of(type)) {
                    if (t.asText().equals(wanted) || t.asText().equals(LWS_NS + wanted)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }
}
