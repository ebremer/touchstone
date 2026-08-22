package com.ebremer.touchstone.fixtures.lws;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ebremer.touchstone.fixtures.oidc.OidcIssuer;
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
 * In-memory reference LWS server: the target for the conformance self-test loop
 * (DECISIONS.md D-0015). Implements the WD-20260622 happy paths — container model with
 * items/totalItems listings, lws+json/ld+json/json conneg, Link rel="up"/rel="type"
 * metadata, ETags with 304/412/428 conditional semantics, containment-consistent
 * create/delete, 409 on non-recursive delete of a non-empty container, Depth: infinity
 * recursion, JSON Merge Patch (RFC 7386) on data resources, single-range requests, and a
 * derived linkset per resource.
 *
 * <p>Three auth modes (DECISIONS.md D-0017): {@link AuthMode#OPEN} (no auth — the core
 * happy-path suite), {@link AuthMode#SECURED} (validates Bearer tokens against the OIDC
 * issuer; 401 + conforming WWW-Authenticate challenge on missing/invalid, 403 on a valid
 * non-owner; owner = the identity that created the resource), and {@link AuthMode#BROKEN}
 * (auth theater — never challenges or forbids). Range requests and linkset resources are
 * not implemented yet; they grow with the suite.
 */
public final class RefLwsServer implements AutoCloseable {

    private static final String LWS_NS = "https://www.w3.org/ns/lws#";
    private static final Set<String> CONTAINER_MEDIA_TYPES =
            Set.of("application/lws+json", "application/ld+json", "application/json");

    private final AuthMode authMode;
    private final Server server;
    private final ServerConnector connector;
    private final ConcurrentMap<String, Node> store = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile TokenValidator validator;
    private volatile String asUri;

    private static final class Node {
        final boolean container;
        volatile byte[] bytes;
        volatile String contentType;
        volatile String etag = newEtag();
        volatile String owner;
        final Set<String> children = ConcurrentHashMap.newKeySet();

        Node(boolean container) {
            this.container = container;
        }
    }

    private RefLwsServer(AuthMode authMode) {
        this.authMode = authMode;
        this.server = new Server();
        this.connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new LwsHandler());
        store.put("/", new Node(true));
    }

    /** Open mode: no authentication (the Phase 2/3 core suite). */
    public static RefLwsServer start(int port) {
        return startIn(AuthMode.OPEN, port);
    }

    /**
     * Secured mode: validate Bearer tokens against {@code issuer}. The realm is this
     * server's own base URI, so a valid token's {@code aud} must match it.
     */
    public static RefLwsServer startSecured(int port, OidcIssuer issuer) {
        RefLwsServer server = startIn(AuthMode.SECURED, port);
        String realm = server.baseUri().toString();
        try {
            server.validator = new TokenValidator(issuer.issuer(), issuer.jwksUri().toURL(), realm);
        } catch (Exception e) {
            server.close();
            throw new IllegalStateException("cannot build token validator", e);
        }
        server.asUri = issuer.issuer();
        return server;
    }

    /** Broken mode: the deliberately non-compliant twin (validates nothing). */
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

    private final class LwsHandler extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getCanonicalPath();
            String method = request.getMethod();

            String subject = null;
            if (authMode == AuthMode.SECURED) {
                String bearer = bearerToken(request);
                if (bearer == null) {
                    challenge(response, callback, null);
                    return true;
                }
                try {
                    subject = validator.validate(bearer);
                } catch (TokenValidator.InvalidTokenException e) {
                    challenge(response, callback, "invalid_token");
                    return true;
                }
                if (ownerForbids(method, path, subject)) {
                    status(response, callback, 403);
                    return true;
                }
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

        /** True when {@code subject} is not the owner of the resource the request targets. */
        private boolean ownerForbids(String method, String path, String subject) {
            // POST writes into the container at path; everything else targets the node at path.
            Node node = store.get(path);
            if (node == null) {
                return false; // let the method handler answer 404
            }
            return node.owner != null && !node.owner.equals(subject);
        }

        private void challenge(Response response, Callback callback, String error) {
            StringBuilder c = new StringBuilder("Bearer");
            if (asUri != null) {
                c.append(" as_uri=\"").append(asUri).append('"');
                c.append(", realm=\"").append(validator.realm()).append('"');
            }
            if (error != null) {
                c.append(", error=\"").append(error).append('"');
            }
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, c.toString());
            status(response, callback, 401);
        }

        // ---- read ----

        private void read(Request request, Response response, Callback callback, String path, boolean withBody) {
            Node node = store.get(path);
            if (node == null) {
                // A linkset is not a stored node; it is derived from the resource it describes.
                if (path.endsWith(".meta") && store.get(path.substring(0, path.length() - ".meta".length())) != null) {
                    linkset(response, callback, path, withBody);
                    return;
                }
                status(response, callback, 404);
                return;
            }
            String ifNoneMatch = request.getHeaders().get("If-None-Match");
            if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(node.etag))) {
                response.getHeaders().put(HttpHeader.ETAG, node.etag);
                status(response, callback, 304);
                return;
            }
            byte[] body;
            String contentType;
            if (node.container) {
                contentType = negotiate(request.getHeaders().get("Accept"));
                if (contentType == null) {
                    status(response, callback, 406);
                    return;
                }
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
                    response.getHeaders().put("Content-Range",
                            "bytes " + from + "-" + to + "/" + body.length);
                    addResourceLinks(request, response, path, node);
                    if (withBody) {
                        response.write(true, ByteBuffer.wrap(slice), callback);
                    } else {
                        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, slice.length);
                        callback.succeeded();
                    }
                    return;
                }
            }
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            addResourceLinks(request, response, path, node);
            if (withBody) {
                response.write(true, ByteBuffer.wrap(body), callback);
            } else {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
                callback.succeeded();
            }
        }

        /**
         * A minimal linkset for the resource at {@code path}, advertising the patch format it
         * accepts. Derived rather than stored, so it exists for every resource without the
         * fixture having to keep one in step with its subject.
         */
        private void linkset(Response response, Callback callback, String path, boolean withBody) {
            byte[] body = ("{\"linkset\":[{\"anchor\":\"" + path.substring(0, path.length() - ".meta".length())
                    + "\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/linkset+json");
            response.getHeaders().put("Accept-Patch", "application/merge-patch+json");
            if (withBody) {
                response.write(true, ByteBuffer.wrap(body), callback);
            } else {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
                callback.succeeded();
            }
        }

        /**
         * One byte range against an entity of {@code length} bytes, as {@code first,last}
         * inclusive, or null when it cannot be satisfied (RFC 9110 §14.1.1). Only the single-range
         * forms are handled — {@code bytes=a-b}, {@code bytes=a-} and the suffix {@code bytes=-n} —
         * which is what "minimally support" asks for; a multipart range is left unsatisfied rather
         * than answered wrongly.
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
                    // Suffix: the last n bytes, clamped to the whole entity.
                    long n = Long.parseLong(hi);
                    if (n <= 0) {
                        return null;
                    }
                    long from = Math.max(0, length - n);
                    return new long[] {from, length - 1};
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
            String name = unique(path, slug, isContainer);
            String childPath = path + name + (isContainer ? "/" : "");

            Node child = new Node(isContainer);
            child.owner = subject;
            if (!isContainer) {
                byte[] body;
                try (InputStream in = Content.Source.asInputStream(request)) {
                    body = in.readAllBytes();
                }
                child.bytes = body;
                String contentType = request.getHeaders().get("Content-Type");
                child.contentType = contentType != null ? contentType : "application/octet-stream";
            }
            store.put(childPath, child);
            parent.children.add(childPath);
            parent.etag = newEtag();

            response.setStatus(201);
            response.getHeaders().put(HttpHeader.LOCATION, absolute(request, childPath));
            response.getHeaders().put(HttpHeader.ETAG, child.etag);
            response.getHeaders().add("Link", "<" + absolute(request, path) + ">; rel=\"up\"");
            response.getHeaders().add("Link", "<" + LWS_NS + (isContainer ? "Container" : "DataResource")
                    + ">; rel=\"type\"");
            response.getHeaders().add("Link", "<" + absolute(request, linksetOf(childPath))
                    + ">; rel=\"linkset\"; type=\"application/linkset+json\"");
            callback.succeeded();
        }

        // ---- update ----

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
            if (ifMatch == null) {
                status(response, callback, 428);
                return;
            }
            if (!ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(response, callback, 412);
                return;
            }
            byte[] body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readAllBytes();
            }
            node.bytes = body;
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType != null) {
                node.contentType = contentType;
            }
            node.etag = newEtag();
            response.setStatus(204);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            callback.succeeded();
        }

        // ---- patch ----

        /**
         * JSON Merge Patch (RFC 7386), which lws10-core makes the baseline every server has to
         * understand. Containers are not patchable — their representation is derived from
         * containment, not stored.
         *
         * <p>{@code If-Match} is honoured when sent but not demanded. The spec requires the
         * conditional for PUT and for a linkset PUT/PATCH, and says nothing about it for a data
         * resource; a fixture that invented the requirement would fail a conforming server.
         */
        private void patch(Request request, Response response, Callback callback, String path)
                throws Exception {
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
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT)
                    .startsWith("application/merge-patch+json")) {
                response.getHeaders().put("Accept-Patch", "application/merge-patch+json");
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
                // Either the stored representation is not JSON, so a merge patch has nothing to
                // merge into, or the patch itself is malformed.
                status(response, callback, 415);
                return;
            }
            node.bytes = mapper.writeValueAsBytes(mergePatch(target, patch));
            node.contentType = "application/json";
            node.etag = newEtag();
            response.setStatus(204);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            callback.succeeded();
        }

        /**
         * RFC 7386 §2: a non-object patch replaces the target outright; otherwise each member is
         * merged recursively, and a null member REMOVES the name rather than setting it to null.
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
            if (path.equals("/")) {
                methodNotAllowed(response, callback, "GET, HEAD, POST");
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
            String parentPath = parentOf(path);
            Node parent = store.get(parentPath);
            if (parent != null) {
                parent.children.remove(path);
                parent.etag = newEtag();
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

        // ---- helpers ----

        private byte[] listing(Request request, String path, Node node) {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode context = root.putObject("@context");
            context.put("@vocab", LWS_NS);
            context.put("id", "@id");
            context.put("type", "@type");
            root.put("id", absolute(request, path));
            root.put("type", "Container");
            List<String> children = new ArrayList<>(node.children);
            children.sort(String::compareTo);
            root.put("totalItems", children.size());
            ArrayNode items = root.putArray("items");
            for (String childPath : children) {
                Node child = store.get(childPath);
                if (child == null) {
                    continue;
                }
                ObjectNode item = items.addObject();
                item.put("id", absolute(request, childPath));
                item.put("type", child.container ? "Container" : "DataResource");
                if (!child.container) {
                    item.put("mediaType", child.contentType);
                }
            }
            try {
                return mapper.writeValueAsBytes(root);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void addResourceLinks(Request request, Response response, String path, Node node) {
            if (!path.equals("/")) {
                response.getHeaders().add("Link", "<" + absolute(request, parentOf(path)) + ">; rel=\"up\"");
            }
            response.getHeaders().add("Link", "<" + LWS_NS + (node.container ? "Container" : "DataResource")
                    + ">; rel=\"type\"");
            // The creation clause names rel="linkset" beside rel="up", and a client has no other
            // way to find where a resource's metadata is edited — there is no path convention it
            // is entitled to assume. The target is served, rather than merely advertised: a link
            // relation pointing at a 404 is worse than none, because a client cannot tell the
            // difference between "no metadata" and "metadata it failed to reach".
            response.getHeaders().add("Link", "<" + absolute(request, linksetOf(path))
                    + ">; rel=\"linkset\"; type=\"application/linkset+json\"");
        }

        /** A resource's linkset lives beside it; the suffix is this fixture's convention, not the spec's. */
        private static String linksetOf(String path) {
            return path.endsWith("/") ? path.substring(0, path.length() - 1) + ".meta" : path + ".meta";
        }

        private String negotiate(String accept) {
            if (accept == null || accept.isBlank() || accept.contains("*/*")) {
                return "application/lws+json";
            }
            for (String candidate : List.of("application/lws+json", "application/ld+json", "application/json")) {
                if (accept.contains(candidate)) {
                    return candidate;
                }
            }
            return CONTAINER_MEDIA_TYPES.stream().anyMatch(accept::contains) ? "application/lws+json" : null;
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
            while (store.containsKey(parentPath + candidate + (container ? "/" : ""))) {
                i++;
                candidate = name + "-" + i;
            }
            return candidate;
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
    }
}
