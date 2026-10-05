package com.ebremer.touchstone.clients;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client-session service (CLIENT-TESTING.md): a client developer starts a session, points
 * their client at its storage, and watches every request it sends on the session's page.
 *
 * <p>Paths under the public base path:
 * <ul>
 *   <li>{@code /}: the start page; {@code /static/...}: its scripts and styles;</li>
 *   <li>{@code POST /sessions}: starts a session (section 4.4);</li>
 *   <li>{@code /sessions/{sid}}, {@code .../exchanges}, {@code .../tokens/{name}}: the session API,
 *       which takes the session key as a Bearer token; {@code .../page}: the session page, which
 *       reads the key from its URL's fragment;</li>
 *   <li>{@code /s/{sid}/storage/...} and {@code /s/{sid}/as/...}: the session's storage and
 *       authorization server, every exchange with them recorded; the authorization server's
 *       metadata is at {@code /.well-known/lws-configuration} followed by its issuer's path.</li>
 * </ul>
 */
public final class ClientLab implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ClientLab.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STATIC = "/com/ebremer/touchstone/clients/static/";
    private static final Map<String, String> STATIC_FILES = Map.of(
            "index.html", "text/html; charset=utf-8",
            "session.html", "text/html; charset=utf-8",
            "lab.css", "text/css; charset=utf-8",
            "lab.js", "text/javascript; charset=utf-8",
            "session.js", "text/javascript; charset=utf-8");
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
            + " connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final String EXPOSED = "Location, Link, ETag, Allow, Accept-Patch, Accept-Query, Accept-Ranges,"
            + " Content-Location, Content-Range, Last-Modified, WWW-Authenticate, Vary, Retry-After";

    private final ClientLabConfig config;
    private final SessionManager sessions;
    private final Server server;
    private final ServerConnector connector;
    private final ScheduledExecutorService sweeper;

    private ClientLab(ClientLabConfig config, Clock clock) {
        this.config = config;
        this.sessions = new SessionManager(config, clock);
        this.server = new Server();
        this.connector = new ServerConnector(server);
        connector.setHost(config.bindHost());
        connector.setPort(config.port());
        connector.setAcceptQueueSize(256);
        server.addConnector(connector);
        server.setHandler(new LabHandler());
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-session-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    public static ClientLab start(ClientLabConfig config) {
        return start(config, Clock.systemUTC());
    }

    static ClientLab start(ClientLabConfig config, Clock clock) {
        ClientLab lab = new ClientLab(config, clock);
        try {
            lab.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the client-session service", e);
        }
        lab.sweeper.scheduleWithFixedDelay(() -> {
            int ended = lab.sessions.sweep();
            if (ended > 0) {
                LOG.info("{} expired session(s) ended; {} live", ended, lab.sessions.size());
            }
        }, 1, 1, TimeUnit.MINUTES);
        LOG.info("client sessions at {} (listening on {}:{})", config.publicBase(), config.bindHost(), lab.port());
        return lab;
    }

    public int port() {
        return connector.getLocalPort();
    }

    SessionManager sessions() {
        return sessions;
    }

    public void join() throws InterruptedException {
        server.join();
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class LabHandler extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getCanonicalPath();
            if (path == null) {
                plain(response, callback, 400);
                return true;
            }
            String base = config.basePath();
            String wellKnown = "/.well-known/lws-configuration" + base + "/s/";
            if (path.startsWith(wellKnown)) {
                String rest = path.substring(wellKnown.length());
                int slash = rest.indexOf('/');
                Session s = slash < 0 ? null : sessions.get(rest.substring(0, slash));
                if (s == null || !rest.substring(slash).equals("/as")) {
                    plain(response, callback, 404);
                    return true;
                }
                protocol(s, s.as.handler(), "asMetadata", request, response, callback);
                return true;
            }
            if (path.equals(base)) {
                response.setStatus(301);
                response.getHeaders().put(HttpHeader.LOCATION, base + "/");
                callback.succeeded();
                return true;
            }
            if (!path.startsWith(base + "/")) {
                plain(response, callback, 404);
                return true;
            }
            String rest = path.substring(base.length());
            if (rest.equals("/")) {
                staticFile(request, response, callback, "index.html");
            } else if (rest.startsWith("/static/")) {
                staticFile(request, response, callback, rest.substring("/static/".length()));
            } else if (rest.equals("/sessions")) {
                startSession(request, response, callback);
            } else if (rest.startsWith("/sessions/")) {
                api(request, response, callback, rest.substring("/sessions/".length()));
            } else if (rest.startsWith("/s/")) {
                String inSession = rest.substring("/s/".length());
                int slash = inSession.indexOf('/');
                Session s = slash < 0 ? null : sessions.get(inSession.substring(0, slash));
                if (s == null) {
                    plain(response, callback, 404);
                    return true;
                }
                String sub = inSession.substring(slash);
                if (sub.startsWith("/storage/")) {
                    protocol(s, s.storage.handler(), null, request, response, callback);
                } else if (sub.startsWith("/as/")) {
                    String role = switch (sub) {
                        case "/as/token" -> "asToken";
                        case "/as/jwks" -> "asJwks";
                        default -> "unknown";
                    };
                    protocol(s, s.as.handler(), role, request, response, callback);
                } else {
                    protocol(s, NOTHING, "unknown", request, response, callback);
                }
            } else {
                plain(response, callback, 404);
            }
            return true;
        }

        // ---- the protocol space: recorded ----

        /**
         * Serves a request to the session's storage or authorization server, and records it:
         * preflights answered here, the session's bounds enforced, CORS headers added for a
         * browser client, and the exchange annotated with what the handler and the ledger know.
         *
         * @param fixedRole the role when {@code target} does not set one (the authorization server)
         */
        private void protocol(Session s, Handler target, String fixedRole, Request request, Response response,
                              Callback callback) throws Exception {
            long started = System.nanoTime();
            Instant at = sessions.now();
            s.touch(at);
            String url = config.origin() + request.getHttpURI().getPath()
                    + (request.getHttpURI().getQuery() == null ? "" : "?" + request.getHttpURI().getQuery());
            String method = request.getMethod();
            String origin = request.getHeaders().get(HttpHeader.ORIGIN);
            Pending pending = new Pending(s, request, url, at, started);

            if (method.equals("OPTIONS") && request.getHeaders().get("Access-Control-Request-Method") != null) {
                preflight(request, response, origin);
                pending.complete(response.getStatus(), response.getHeaders(), null, 0, "preflight", null);
                callback.succeeded();
                return;
            }
            if (!s.bucket.tryTake()) {
                response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
                refuse(pending, response, callback, origin, 429, "rate");
                return;
            }
            long declared = request.getLength();
            if (declared > config.maxBodyBytes()) {
                refuse(pending, response, callback, origin, 413, "body");
                return;
            }
            byte[] body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readNBytes(config.maxBodyBytes() + 1);
            }
            if (body.length > config.maxBodyBytes()) {
                refuse(pending, response, callback, origin, 413, "body");
                return;
            }
            pending.requestBody = body;
            if (target == s.storage.handler() && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                RefLwsServer.Usage usage = s.storage.usage();
                if ((method.equals("POST") && usage.resources() >= config.maxResources())
                        || usage.bytes() + body.length > config.maxStorageBytes()) {
                    refuse(pending, response, callback, origin, 507, "storage");
                    return;
                }
            }

            BufferedRequest wrapped = new BufferedRequest(request, body);
            // The ledger reads whole answers up to the body limit; the log keeps less of them.
            Capturing capturing = new Capturing(wrapped, response, Math.max(config.maxRecordedResponseBytes(),
                    config.maxBodyBytes()), () -> cors(response, origin));
            // Recorded once, as the answer completes: at its last write, so the log has it before
            // the client does, or when the handler completes without writing a body.
            AtomicBoolean done = new AtomicBoolean();
            Runnable record = () -> {
                if (done.compareAndSet(false, true)) {
                    pending.complete(response.getStatus(), response.getHeaders(), capturing.bytes(),
                            capturing.length(), role(wrapped, fixedRole), null);
                }
            };
            capturing.onLast = record;
            Callback recorded = new Callback() {
                @Override
                public void succeeded() {
                    capturing.prepare();
                    record.run();
                    callback.succeeded();
                }

                @Override
                public void failed(Throwable x) {
                    record.run();
                    callback.failed(x);
                }

                @Override
                public InvocationType getInvocationType() {
                    return callback.getInvocationType();
                }
            };
            if (!target.handle(wrapped, capturing, recorded)) {
                capturing.prepare();
                plain(capturing, recorded, 404);
            }
        }

        private String role(Request request, String fixedRole) {
            if (fixedRole != null) {
                return fixedRole;
            }
            Object role = request.getAttribute(RefLwsServer.ROLE_ATTRIBUTE);
            return role == null ? "unknown" : role.toString();
        }

        private void refuse(Pending pending, Response response, Callback callback, String origin, int status,
                            String limit) {
            cors(response, origin);
            response.setStatus(status);
            pending.complete(status, response.getHeaders(), null, 0, "limited", limit);
            callback.succeeded();
        }

        private void preflight(Request request, Response response, String origin) {
            response.setStatus(204);
            if (origin != null) {
                response.getHeaders().put("Access-Control-Allow-Origin", origin);
                response.getHeaders().put("Access-Control-Allow-Methods",
                        "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, QUERY");
                String asked = request.getHeaders().get("Access-Control-Request-Headers");
                if (asked != null) {
                    response.getHeaders().put("Access-Control-Allow-Headers", asked);
                }
                response.getHeaders().put("Access-Control-Max-Age", "600");
                response.getHeaders().put(HttpHeader.VARY,
                        "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            }
        }

        /** CORS for a browser client: any origin, no cookies (tokens travel in headers). */
        private void cors(Response response, String origin) {
            if (origin == null || response.isCommitted()) {
                return;
            }
            response.getHeaders().put("Access-Control-Allow-Origin", origin);
            response.getHeaders().put("Access-Control-Expose-Headers", EXPOSED);
            response.getHeaders().add(HttpHeader.VARY, "Origin");
        }

        // ---- the session API ----

        private void startSession(Request request, Response response, Callback callback) throws IOException {
            if (!request.getMethod().equals("POST")) {
                response.getHeaders().put(HttpHeader.ALLOW, "POST");
                error(response, callback, 405, "method_not_allowed", "POST starts a session");
                return;
            }
            String address = config.trustForwardedFor() && request.getHeaders().get("X-Forwarded-For") != null
                    ? request.getHeaders().get("X-Forwarded-For").split(",")[0].trim()
                    : Request.getRemoteAddr(request);
            SessionManager.Created created;
            try {
                created = sessions.create(address);
            } catch (SessionManager.Refused e) {
                if (e.status == 429) {
                    response.getHeaders().put(HttpHeader.RETRY_AFTER, "3600");
                }
                error(response, callback, e.status, "refused", e.getMessage());
                return;
            }
            Session s = created.session();
            LOG.info("session {} started; {} live", s.id, sessions.size());
            ObjectNode body = describe(s);
            body.put("key", created.key());
            body.put("pageWithKey", body.get("page").asText() + "#key=" + created.key());
            ObjectNode tokens = body.putObject("tokens");
            Session.IDENTITIES.forEach(name -> tokens.put(name, s.token(name, config.tokenLifetime())));
            response.getHeaders().put(HttpHeader.LOCATION, config.publicBase() + "/sessions/" + s.id);
            json(response, callback, 201, body);
        }

        private void api(Request request, Response response, Callback callback, String rest) throws IOException {
            String[] parts = rest.split("/", -1);
            Session s = sessions.get(parts[0]);
            if (s == null) {
                error(response, callback, 404, "not_found", "no such session; it may have ended");
                return;
            }
            String method = request.getMethod();
            if (parts.length == 2 && parts[1].equals("page")) {
                staticFile(request, response, callback, "session.html");
                return;
            }
            String auth = request.getHeaders().get(HttpHeader.AUTHORIZATION);
            String key = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
            if (!s.keyMatches(key)) {
                response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"touchstone-client-session\"");
                error(response, callback, 401, "unauthorized", "the session API takes the session key as a Bearer token");
                return;
            }
            s.touch(sessions.now());
            if (parts.length == 1) {
                switch (method) {
                    case "GET" -> json(response, callback, 200, describe(s));
                    case "DELETE" -> {
                        sessions.end(s.id);
                        LOG.info("session {} ended by its owner; {} live", s.id, sessions.size());
                        response.setStatus(204);
                        callback.succeeded();
                    }
                    default -> {
                        response.getHeaders().put(HttpHeader.ALLOW, "GET, DELETE");
                        error(response, callback, 405, "method_not_allowed", "GET or DELETE");
                    }
                }
                return;
            }
            if (parts.length == 2 && parts[1].equals("exchanges") && method.equals("GET")) {
                long after = longParam(request, "after", 0);
                int limit = (int) Math.max(1, Math.min(500, longParam(request, "limit", 200)));
                List<Exchange> page = s.recorder.after(after, limit);
                ObjectNode body = JSON.createObjectNode();
                body.set("exchanges", JSON.valueToTree(page));
                body.put("last", page.isEmpty() ? after : page.getLast().seq());
                body.put("recorded", s.recorder.recorded());
                body.put("dropped", s.recorder.dropped());
                json(response, callback, 200, body);
                return;
            }
            if (parts.length == 3 && parts[1].equals("tokens") && method.equals("POST")
                    && Session.IDENTITIES.contains(parts[2])) {
                ObjectNode body = JSON.createObjectNode();
                body.put("access_token", s.token(parts[2], config.tokenLifetime()));
                body.put("token_type", "Bearer");
                body.put("expires_in", config.tokenLifetime().toSeconds());
                body.put("subject", s.webid(parts[2]));
                json(response, callback, 200, body);
                return;
            }
            error(response, callback, 404, "not_found", "no such session resource");
        }

        /** What the session page and the API say about a session; never its key or a token. */
        private ObjectNode describe(Session s) {
            ObjectNode body = JSON.createObjectNode();
            body.put("id", s.id);
            body.put("page", config.publicBase() + "/sessions/" + s.id + "/page");
            body.put("api", config.publicBase() + "/sessions/" + s.id);
            body.put("storage", s.storageUrl());
            ObjectNode as = body.putObject("authorizationServer");
            as.put("issuer", s.as.issuer());
            as.put("metadata", s.as.metadataUri().toString());
            ObjectNode ids = body.putObject("identities");
            for (String name : Session.IDENTITIES) {
                ObjectNode id = ids.putObject(name);
                id.put("webid", s.webid(name));
                id.put("role", name.equals("alice") ? "owns the storage" : "has no access until alice grants it");
            }
            body.put("client", s.clientId);
            body.put("created", s.created.toString());
            body.put("expires", sessions.expiry(s).toString());
            ObjectNode traps = body.putObject("traps");
            traps.put("opaquePageUrls", s.traps.opaquePageUrls());
            traps.put("flatResourceUris", s.traps.flatResourceUris());
            traps.put("opaqueLinksetUrls", s.traps.opaqueLinksetUrls());
            traps.put("putOnlyForText", s.traps.putOnlyForText());
            traps.put("decoy", s.traps.decoy());
            traps.put("indexLagSeconds", s.traps.indexLag().toSeconds());
            ObjectNode limits = body.putObject("limits");
            limits.put("idleTimeout", config.idleTimeout().toString());
            limits.put("maxLifetime", config.maxLifetime().toString());
            limits.put("maxBodyBytes", config.maxBodyBytes());
            limits.put("maxResources", config.maxResources());
            limits.put("maxStorageBytes", config.maxStorageBytes());
            limits.put("maxExchanges", config.maxExchanges());
            body.put("recorded", s.recorder.recorded());
            return body;
        }

        private long longParam(Request request, String name, long fallback) {
            String query = request.getHttpURI().getQuery();
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith(name + "=")) {
                        try {
                            return Long.parseLong(p.substring(name.length() + 1));
                        } catch (NumberFormatException e) {
                            return fallback;
                        }
                    }
                }
            }
            return fallback;
        }

        // ---- pages ----

        private void staticFile(Request request, Response response, Callback callback, String name) throws IOException {
            String type = STATIC_FILES.get(name);
            if (type == null || !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) {
                plain(response, callback, type == null ? 404 : 405);
                return;
            }
            byte[] bytes;
            try (InputStream in = ClientLab.class.getResourceAsStream(STATIC + name)) {
                if (in == null) {
                    plain(response, callback, 404);
                    return;
                }
                bytes = in.readAllBytes();
            }
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, type);
            response.getHeaders().put("Content-Security-Policy", CSP);
            response.getHeaders().put("X-Content-Type-Options", "nosniff");
            response.getHeaders().put("Referrer-Policy", "no-referrer");
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-cache");
            if (request.getMethod().equals("HEAD")) {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
                callback.succeeded();
            } else {
                response.write(true, ByteBuffer.wrap(bytes), callback);
            }
        }
    }

    // ---- the exchange being recorded ----

    /** An exchange from its request until its answer is complete, when it is appended to the log. */
    private final class Pending {
        private final Session session;
        private final Request request;
        private final String url;
        private final Instant at;
        private final long started;
        private final Map<String, List<String>> requestHeaders;
        private final String issuedVia;
        private final Map<String, String> advertised;
        byte[] requestBody = new byte[0];

        Pending(Session session, Request request, String url, Instant at, long started) {
            this.session = session;
            this.request = request;
            this.url = url;
            this.at = at;
            this.started = started;
            this.requestHeaders = headers(request.getHeaders());
            // Taken now, before this exchange's own answer teaches the ledger anything.
            this.issuedVia = session.recorder.issuedVia(url);
            this.advertised = session.recorder.advertisedFor(url);
        }

        void complete(int status, HttpFields responseFields, byte[] responseBody, long responseLength, String role,
                      String limit) {
            Map<String, List<String>> responseHeaders = headers(responseFields);
            String requestType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            String responseType = responseFields.get(HttpHeader.CONTENT_TYPE);
            String requestText = text(requestBody, requestType, requestBody.length);
            String responseText = responseBody == null ? null : text(responseBody, responseType, responseBody.length);
            if (role.equals("asToken")) {
                requestText = requestText == null ? null : Redaction.form(requestText);
                responseText = responseText == null ? null : Redaction.json(responseText);
            }
            // The ledger learns from the whole answer; the log keeps the first part of it.
            session.recorder.learn(url, role, status, responseHeaders, responseText);
            Object subject = request.getAttribute(RefLwsServer.SUBJECT_ATTRIBUTE);
            String[] presented = presentation(request, requestType, requestBody);
            Exchange.Annotations annotations = new Exchange.Annotations(role,
                    subject == null ? null : session.identityOf(subject.toString()),
                    presented[1], presented[0], issuedVia != null, issuedVia, advertised, null, limit);
            int cap = config.maxRecordedResponseBytes();
            Exchange.Body req = body(requestText, requestBody.length, requestBody.length, cap);
            Exchange.Body res = body(responseText, responseLength, responseBody == null ? 0 : responseBody.length, cap);
            long millis = (System.nanoTime() - started) / 1_000_000;
            String shown = redactUrl(url);
            session.recorder.append(seq -> new Exchange(seq, at.toString(), millis, request.getMethod(), shown,
                    requestHeaders, req, status, responseHeaders, res, annotations));
        }
    }

    /** {presentation, token fingerprint} for the credential a request carries, if any. */
    private static String[] presentation(Request request, String contentType, byte[] body) {
        String auth = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (auth != null) {
            int space = auth.indexOf(' ');
            String scheme = space < 0 ? auth : auth.substring(0, space);
            String credential = space < 0 ? "" : auth.substring(space + 1).trim();
            return new String[] {scheme.equalsIgnoreCase("Bearer") ? "authorization" : "authorization:" + scheme,
                    credential.isEmpty() ? null : Redaction.fingerprint(credential)};
        }
        String query = request.getHttpURI().getQuery();
        String fromQuery = formValue(query, "access_token");
        if (fromQuery != null) {
            return new String[] {"query", Redaction.fingerprint(fromQuery)};
        }
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            String fromForm = formValue(new String(body, StandardCharsets.UTF_8), "access_token");
            if (fromForm != null) {
                return new String[] {"form", Redaction.fingerprint(fromForm)};
            }
        }
        return new String[] {"none", null};
    }

    private static String formValue(String encoded, String name) {
        if (encoded == null) {
            return null;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String redactUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q + 1) + Redaction.form(url.substring(q + 1));
    }

    private static Map<String, List<String>> headers(HttpFields fields) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (HttpField f : fields) {
            out.computeIfAbsent(f.getName(), k -> new java.util.ArrayList<>())
                    .add(Redaction.header(f.getName(), f.getValue()));
        }
        return out;
    }

    /** The body as text when its media type is textual, else null. */
    private static String text(byte[] body, String contentType, int length) {
        if (body == null || length == 0 || !textual(contentType)) {
            return null;
        }
        return new String(body, 0, length, StandardCharsets.UTF_8);
    }

    private static boolean textual(String contentType) {
        if (contentType == null) {
            return false;
        }
        String t = contentType.toLowerCase(Locale.ROOT);
        return t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("turtle")
                || t.contains("x-www-form-urlencoded") || t.contains("n-triples") || t.contains("n-quads")
                || t.contains("trig") || t.contains("lws+cid") || t.contains("sparql");
    }

    /**
     * @param length the body's whole length
     * @param captured how many of its bytes were kept, and {@code text} decoded from
     */
    private static Exchange.Body body(String text, long length, long captured, int cap) {
        if (length == 0) {
            return Exchange.Body.NONE;
        }
        if (text == null) {
            return new Exchange.Body(length, null, false);
        }
        boolean cut = text.length() > cap;
        return new Exchange.Body(length, cut ? text.substring(0, cap) : text, cut || captured < length);
    }

    private static void plain(Response response, Callback callback, int status) {
        response.setStatus(status);
        callback.succeeded();
    }

    private static void json(Response response, Callback callback, int status, ObjectNode body) throws IOException {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.write(true, ByteBuffer.wrap(JSON.writeValueAsBytes(body)), callback);
    }

    private static void error(Response response, Callback callback, int status, String error, String message)
            throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("error", error);
        body.put("message", message);
        json(response, callback, status, body);
    }

    /** A handler for session paths that serve nothing: every request is a 404, and still recorded. */
    private static final Handler NOTHING = new Handler.Abstract() {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            response.setStatus(404);
            callback.succeeded();
            return true;
        }
    };

    // ---- wrappers ----

    /** A request whose body was already read, served again from memory. */
    private static final class BufferedRequest extends Request.Wrapper {
        private final ByteBufferContentSource source;
        private final long length;

        BufferedRequest(Request wrapped, byte[] body) {
            super(wrapped);
            this.source = new ByteBufferContentSource(ByteBuffer.wrap(body));
            this.length = body.length;
        }

        @Override
        public Content.Chunk read() {
            return source.read();
        }

        @Override
        public void demand(Runnable demandCallback) {
            source.demand(demandCallback);
        }

        @Override
        public void fail(Throwable failure) {
            source.fail(failure);
        }

        @Override
        public long getLength() {
            return length;
        }
    }

    /** A response that keeps a copy of the first {@code limit} bytes of its body. */
    private static final class Capturing extends Response.Wrapper {
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final int limit;
        private final Runnable beforeCommit;
        private long length;
        private boolean prepared;
        Runnable onLast;

        Capturing(Request request, Response wrapped, int limit, Runnable beforeCommit) {
            super(request, wrapped);
            this.limit = limit;
            this.beforeCommit = beforeCommit;
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback) {
            prepare();
            if (content != null && content.hasRemaining()) {
                int n = content.remaining();
                length += n;
                int room = limit - captured.size();
                if (room > 0) {
                    byte[] copy = new byte[Math.min(n, room)];
                    content.slice().get(copy);
                    captured.write(copy, 0, copy.length);
                }
            }
            if (last && onLast != null) {
                onLast.run();
            }
            super.write(last, content, callback);
        }

        synchronized void prepare() {
            if (!prepared) {
                prepared = true;
                beforeCommit.run();
            }
        }

        byte[] bytes() {
            return captured.toByteArray();
        }

        long length() {
            return length;
        }
    }
}
