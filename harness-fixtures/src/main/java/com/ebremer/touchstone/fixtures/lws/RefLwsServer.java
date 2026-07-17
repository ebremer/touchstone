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
 * recursion.
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
                case "DELETE" -> delete(request, response, callback, path);
                default -> methodNotAllowed(response, callback, "GET, HEAD, POST, PUT, DELETE");
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

        // ---- create ----

        private void create(Request request, Response response, Callback callback, String path, String subject)
                throws Exception {
            Node parent = store.get(path);
            if (parent == null) {
                status(response, callback, 404);
                return;
            }
            if (!parent.container) {
                methodNotAllowed(response, callback, "GET, HEAD, PUT, DELETE");
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
