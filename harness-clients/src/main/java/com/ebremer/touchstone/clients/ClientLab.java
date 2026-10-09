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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 *   <li>{@code POST /sessions}: starts a session (section 4.4), with optional settings
 *       (SessionSettings), which {@code PATCH /sessions/{sid}} changes later;</li>
 *   <li>{@code /sessions/{sid}}, {@code .../exchanges}, {@code .../results} (JSON, or the JSON, EARL
 *       or JUnit XML export with {@code ?format=}), {@code .../reset},
 *       {@code .../tasks/{rule}}, {@code .../faults/{fault}}, {@code .../tokens/{name}},
 *       {@code .../assertions/{name}}, {@code .../credentials/{name}}, {@code .../clients}: the session
 *       API, which takes the session
 *       key as a Bearer token; {@code .../page}: the session page, which reads the key from its
 *       URL's fragment;</li>
 *   <li>{@code /s/{sid}/storage/...}, {@code /s/{sid}/as/...}, {@code /s/{sid}/op/...} and
 *       {@code /s/{sid}/id/{name}}: the session's storage, authorization server, OpenID Provider and
 *       identity documents, every exchange with them recorded; the authorization server's metadata
 *       is at {@code /.well-known/lws-configuration} followed by its issuer's path.</li>
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
    private static final String STORAGE = "storage";
    private static final String AS = "authorizationServer";
    private static final String OP = "openidProvider";
    private static final String IDENTITY_HOST = "identityHost";
    /** The largest body the session API reads: a client registration, or a session's settings. */
    private static final int MAX_API_BODY = 16 << 10;
    private static final String EXPOSED = "Location, Link, ETag, Allow, Accept-Patch, Accept-Query, Accept-Ranges,"
            + " Content-Location, Content-Range, Last-Modified, WWW-Authenticate, Vary, Retry-After";

    private final ClientLabConfig config;
    private final ClientRules rules;
    private final SessionManager sessions;
    private final Server server;
    private final ServerConnector connector;
    private final ScheduledExecutorService sweeper;
    private final Outbound outbound;
    /** The real servers a proxy session may front (CLIENT-TESTING.md section 10), and the client that forwards to them. */
    private final ProxyTargets proxyTargets;
    private final org.eclipse.jetty.client.HttpClient proxyHttp;

    private ClientLab(ClientLabConfig config, ClientRules rules, Clock clock, ProxyTargets proxyTargets) {
        this.config = config;
        this.rules = rules;
        this.proxyTargets = proxyTargets;
        this.proxyHttp = proxyTargets.isEmpty() ? null : proxyClient();
        this.sessions = new SessionManager(config, rules, clock);
        this.server = new Server();
        this.connector = new ServerConnector(server);
        connector.setHost(config.bindHost());
        connector.setPort(config.port());
        connector.setAcceptQueueSize(256);
        server.addConnector(connector);
        server.setHandler(new LabHandler());
        this.outbound = new Outbound(config.allowPrivateInboxes(), java.time.Duration.ofSeconds(10));
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-session-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    /** @param rules the client rules every session judges its client by (OBSERVATION.md) */
    public static ClientLab start(ClientLabConfig config, ClientRules rules) {
        return start(config, rules, Clock.systemUTC());
    }

    static ClientLab start(ClientLabConfig config, ClientRules rules, Clock clock) {
        return start(config, rules, clock, ProxyTargets.NONE);
    }

    /** A service whose sessions may also front the real servers {@code proxyTargets} registers. */
    static ClientLab start(ClientLabConfig config, ClientRules rules, Clock clock, ProxyTargets proxyTargets) {
        ClientLab lab = new ClientLab(config, rules, clock, proxyTargets);
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
        LOG.info("client sessions at {} (listening on {}:{}), judged by {} client rules", config.publicBase(),
                config.bindHost(), lab.port(), rules.rules().size());
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
        } finally {
            outbound.close();
            if (proxyHttp != null) {
                try {
                    proxyHttp.stop();
                } catch (Exception e) {
                    LOG.warn("cannot stop the proxy's client", e);
                }
            }
        }
    }

    /**
     * The client a proxy session forwards with: no redirect followed, no cookie kept, no content
     * decoded, and no handler acting on a 401, so the server's answer reaches the client as it is.
     * The targets are registered out of band, so their addresses are not checked as an inbox's are.
     */
    private static org.eclipse.jetty.client.HttpClient proxyClient() {
        org.eclipse.jetty.client.HttpClient http = new org.eclipse.jetty.client.HttpClient();
        http.setFollowRedirects(false);
        http.setHttpCookieStore(new org.eclipse.jetty.http.HttpCookieStore.Empty());
        http.setConnectTimeout(10_000);
        http.setIdleTimeout(30_000);
        http.setUserAgentField(null);
        try {
            http.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the proxy's client", e);
        }
        http.getProtocolHandlers().clear();
        http.getContentDecoderFactories().clear();
        return http;
    }

    // ---- deliveries: the session's requests to clients' inboxes ----

    /**
     * Sends one of the storage's notifications (CLIENT-TESTING.md section 8.3): only where the
     * outbound guard allows and within the session's allowance. Each is recorded and judged as an
     * exchange of the session's own, the inbox's answer its status (OBSERVATION.md section 3).
     */
    private java.util.concurrent.CompletableFuture<Integer> deliver(Session s, RefLwsServer.Delivery d) {
        Instant at = sessions.now();
        long started = System.nanoTime();
        // The keyid's URL is the session's to hand out: an inbox dereferences it.
        String input = d.headers().getOrDefault("Signature-Input", "");
        java.util.regex.Matcher keyid = java.util.regex.Pattern.compile("keyid=\"([^\"#]*)").matcher(input);
        if (keyid.find()) {
            s.recorder.issue(keyid.group(1), "delivery:keyid");
        }
        String refusal = outbound.refusal(d.inbox());
        String limit = refusal == null ? null : "inbox";
        if (refusal == null && !s.takeDelivery(config.maxDeliveries())) {
            refusal = "the session has sent the " + config.maxDeliveries() + " notifications it may send";
            limit = "deliveries";
        }
        if (refusal != null) {
            recordDelivery(s, d, at, started, Outbound.Answer.none(refusal), limit);
            return java.util.concurrent.CompletableFuture.completedFuture(0);
        }
        s.inFlight().incrementAndGet();
        return outbound.post(d.inbox(), d.headers(), d.body(), config.maxRecordedResponseBytes())
                .handle((answer, error) -> {
                    Outbound.Answer a = answer != null ? answer : Outbound.Answer.none(String.valueOf(error));
                    try {
                        recordDelivery(s, d, at, started, a, null);
                    } finally {
                        s.inFlight().decrementAndGet();
                    }
                    return a.status();
                });
    }

    private void recordDelivery(Session s, RefLwsServer.Delivery d, Instant at, long started, Outbound.Answer answer,
                                String limit) {
        Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
        d.headers().forEach((k, v) -> requestHeaders.put(k, List.of(Redaction.header(k, v))));
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        answer.headers().forEach((k, v) -> responseHeaders.put(k, v.stream().map(x -> Redaction.header(k, x)).toList()));
        String responseType = answer.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
        String requestText = text(d.body(), d.headers().get("Content-Type"), d.body().length);
        String responseText = answer.status() == 0 ? answer.problem()
                : text(answer.body(), responseType, answer.body().length);
        String forgery = d.forgery() == null ? null : d.forgery().forgery();
        Exchange.Annotations annotations = new Exchange.Annotations(null, "delivery", null, null, List.of("none"),
                false, null, null, null, null, Map.of(), false, false, false, false, false, null, null, null, null, null,
                forgery == null ? "genuine" : forgery, null, d.forgery() == null ? null : d.forgery().term(), limit);
        int cap = config.maxRecordedResponseBytes();
        Exchange.Body req = body(requestText, d.body().length, d.body().length, cap);
        Exchange.Body res = answer.status() == 0
                ? new Exchange.Body(0, responseText, false)
                : body(responseText, answer.body().length, answer.body().length, cap);
        long millis = (System.nanoTime() - started) / 1_000_000;
        String url = d.inbox().toString();
        byte[] body = d.body();
        s.recorder.append(seq -> {
            Observed observed = new Observed(seq, "POST", url, requestHeaders, body, answer.status(), annotations);
            return new Exchange(seq, at.toString(), millis, "POST", url, requestHeaders, req, answer.status(),
                    responseHeaders, res, annotations, s.judge.judge(observed));
        });
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
            String proxiedWellKnown = "/.well-known/lws-configuration" + base + "/p/";
            if (path.startsWith(proxiedWellKnown)) {
                String rest = path.substring(proxiedWellKnown.length());
                int slash = rest.indexOf('/');
                proxied(rest.substring(0, slash < 0 ? rest.length() : slash), request, response, callback);
                return true;
            }
            if (path.startsWith(wellKnown)) {
                String rest = path.substring(wellKnown.length());
                int slash = rest.indexOf('/');
                Session s = slash < 0 ? null : sessions.get(rest.substring(0, slash));
                if (s == null || s.proxy != null || !rest.substring(slash).equals("/as")) {
                    plain(response, callback, 404);
                    return true;
                }
                protocol(s, s.as.handler(), AS, "asMetadata", request, response, callback);
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
            } else if (rest.equals("/proxies")) {
                proxies(request, response, callback);
            } else if (rest.startsWith("/p/")) {
                String inProxy = rest.substring("/p/".length());
                int slash = inProxy.indexOf('/');
                if (slash < 0) {
                    plain(response, callback, 404);
                } else {
                    proxied(inProxy.substring(0, slash), request, response, callback);
                }
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
                if (s.proxy != null && (sub.startsWith("/storage/") || sub.startsWith("/as/"))) {
                    // A proxy session's storage and authorization server are the real server's.
                    plain(response, callback, 404);
                } else if (sub.startsWith("/storage/")) {
                    protocol(s, s.storage.handler(), STORAGE, null, request, response, callback);
                } else if (sub.startsWith("/as/")) {
                    String role = switch (sub) {
                        case "/as/token" -> "asToken";
                        case "/as/jwks" -> "asJwks";
                        default -> "unknown";
                    };
                    protocol(s, s.as.handler(), AS, role, request, response, callback);
                } else if (sub.startsWith("/op/")) {
                    String role = switch (sub) {
                        case "/op/.well-known/openid-configuration" -> "opDiscovery";
                        case "/op/jwks" -> "opJwks";
                        case "/op/authorize" -> "opAuthorize";
                        case "/op/token" -> "opToken";
                        default -> "unknown";
                    };
                    protocol(s, s.op.handler(), OP, role, request, response, callback);
                } else if (sub.startsWith("/id/")) {
                    Session.Identity identity = s.identities.get(sub.substring("/id/".length()));
                    protocol(s, identityDocument(identity), IDENTITY_HOST, identity == null ? "unknown" : "identityDocument",
                            request, response, callback);
                } else {
                    protocol(s, NOTHING, null, "unknown", request, response, callback);
                }
            } else {
                plain(response, callback, 404);
            }
            return true;
        }

        /**
         * A request to proxy target {@code id}: forwarded, recorded and judged in the session that
         * holds the target (CLIENT-TESTING.md section 10). Without one, nothing is forwarded.
         */
        private void proxied(String id, Request request, Response response, Callback callback) throws Exception {
            if (proxyTargets.find(id).isEmpty()) {
                plain(response, callback, 404);
                return;
            }
            Session s = sessions.holder(id);
            if (s == null) {
                error(response, callback, 503, "no_session", "no session holds the proxy target " + id
                        + "; start one with {\"proxy\": \"" + id + "\"}");
                return;
            }
            String url = config.origin() + request.getHttpURI().getPath();
            protocol(s, s.proxy.handler(proxyHttp, address(request), config.publicBase()), s.proxy.server(url), null,
                    request, response, callback);
        }

        /** The proxy targets, by id and storage: what the start page offers. */
        private void proxies(Request request, Response response, Callback callback) throws IOException {
            if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
                response.getHeaders().put(HttpHeader.ALLOW, "GET");
                error(response, callback, 405, "method_not_allowed", "GET");
                return;
            }
            ObjectNode body = JSON.createObjectNode();
            ArrayNode list = body.putArray("proxies");
            for (ProxyTargets.ProxyTarget t : proxyTargets.all()) {
                ObjectNode item = list.addObject();
                item.put("id", t.id());
                item.put("storage", t.storage().toString());
                item.put("held", sessions.holder(t.id()) != null);
            }
            json(response, callback, 200, body);
        }

        /** The client's address: the first X-Forwarded-For entry behind a proxy that sets it, else the connection's. */
        private String address(Request request) {
            return config.trustForwardedFor() && request.getHeaders().get("X-Forwarded-For") != null
                    ? request.getHeaders().get("X-Forwarded-For").split(",")[0].trim()
                    : Request.getRemoteAddr(request);
        }

        // ---- the protocol space: recorded ----

        /**
         * Serves a request to the session's storage or authorization server, and records it:
         * preflights answered here, the session's bounds enforced, CORS headers added for a
         * browser client, and the exchange annotated with what the handler and the ledger know.
         *
         * @param server the annotation {@code server}: which of the session's servers is addressed, or null
         * @param fixedRole the role when {@code target} does not set one (the authorization server)
         */
        private void protocol(Session s, Handler target, String server, String fixedRole, Request request,
                              Response response, Callback callback) throws Exception {
            long started = System.nanoTime();
            Instant at = sessions.now();
            s.touch(at);
            String url = config.origin() + request.getHttpURI().getPath()
                    + (request.getHttpURI().getQuery() == null ? "" : "?" + request.getHttpURI().getQuery());
            String method = request.getMethod();
            String origin = request.getHeaders().get(HttpHeader.ORIGIN);
            Pending pending = new Pending(s, request, url, at, started, server);

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
            if (target == s.storage.handler() && method.equals("POST")) {
                // Taken before the request is handled: whether a subscription already delivers to its inbox.
                try {
                    com.fasterxml.jackson.databind.JsonNode doc = JSON.readTree(body);
                    if (doc != null && doc.path("inbox").isTextual()) {
                        pending.inboxShared = s.storage.subscriptionInboxes().contains(doc.path("inbox").asText());
                    }
                } catch (IOException e) {
                    // not JSON: no inbox
                }
            }
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
            String address = address(request);
            // The settings are checked before the session counts against the address's allowance.
            SessionSettings settings = settings(request, response, callback);
            if (settings == null) {
                return;
            }
            ProxyTargets.ProxyTarget proxyTarget = null;
            if (settings.proxy() != null) {
                proxyTarget = proxyTargets.find(settings.proxy()).orElse(null);
                if (proxyTarget == null) {
                    error(response, callback, 400, "invalid_settings", proxyTargets.isEmpty()
                            ? "this service has no proxy targets" : "no proxy target named " + settings.proxy());
                    return;
                }
            }
            SessionManager.Created created;
            try {
                created = sessions.create(address, proxyTarget);
            } catch (SessionManager.Refused e) {
                if (e.status == 429) {
                    response.getHeaders().put(HttpHeader.RETRY_AFTER, "3600");
                }
                error(response, callback, e.status, "refused", e.getMessage());
                return;
            }
            Session s = created.session();
            settings.applyTo(s);
            s.deliverWith(d -> deliver(s, d));
            LOG.info("session {} started; {} live", s.id, sessions.size());
            ObjectNode body = describe(s);
            body.put("key", created.key());
            body.put("pageWithKey", body.get("page").asText() + "#key=" + created.key());
            if (s.proxy == null) {
                // A proxy session's tokens come from the real server's authorization server.
                ObjectNode tokens = body.putObject("tokens");
                Session.IDENTITIES.forEach(name -> tokens.put(name, s.token(name, config.tokenLifetime())));
            }
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
                    case "PATCH" -> {
                        SessionSettings settings = settings(request, response, callback);
                        if (settings != null && settings.proxy() != null) {
                            error(response, callback, 400, "invalid_settings",
                                    "a session's proxy target is chosen when it starts");
                        } else if (settings != null) {
                            settings.applyTo(s);
                            LOG.info("session {}: settings changed", s.id);
                            json(response, callback, 200, describe(s));
                        }
                    }
                    case "DELETE" -> {
                        sessions.end(s.id);
                        LOG.info("session {} ended by its owner; {} live", s.id, sessions.size());
                        response.setStatus(204);
                        callback.succeeded();
                    }
                    default -> {
                        response.getHeaders().put(HttpHeader.ALLOW, "GET, PATCH, DELETE");
                        error(response, callback, 405, "method_not_allowed", "GET, PATCH or DELETE");
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
            if (parts.length == 2 && parts[1].equals("results") && method.equals("GET")) {
                results(s, request, response, callback);
                return;
            }
            if (parts.length == 2 && parts[1].equals("reset") && method.equals("POST")) {
                s.resetResults(sessions.now());
                LOG.info("session {}: results reset", s.id);
                response.setStatus(204);
                callback.succeeded();
                return;
            }
            if (parts.length == 3 && parts[1].equals("tasks") && method.equals("POST")) {
                if (s.judge.unavailable(parts[2])) {
                    error(response, callback, 409, "inapplicable",
                            "a proxy session cannot judge " + parts[2] + ", so its task does nothing here");
                    return;
                }
                if (!s.startTask(parts[2])) {
                    error(response, callback, 404, "not_found", "no rule named " + parts[2] + " has a task");
                    return;
                }
                LOG.info("session {}: task {} started", s.id, parts[2]);
                response.setStatus(204);
                callback.succeeded();
                return;
            }
            if (parts.length == 3 && parts[1].equals("faults") && method.equals("POST")) {
                if (!s.armFault(parts[2])) {
                    error(response, callback, 404, "not_found", "no fault named " + parts[2]);
                    return;
                }
                response.setStatus(204);
                callback.succeeded();
                return;
            }
            if (parts.length == 3 && parts[1].equals("credentials") && method.equals("GET")
                    && s.identities.containsKey(parts[2])) {
                Session.Identity identity = s.identities.get(parts[2]);
                ObjectNode body = JSON.createObjectNode();
                body.put("name", identity.name());
                body.put("webid", identity.webid());
                body.put("username", identity.name());
                body.put("password", identity.password());
                body.put("verificationMethod", s.keyId(identity.name()));
                body.set("privateKeyJwk", JSON.valueToTree(identity.key().toJSONObject()));
                json(response, callback, 200, body);
                return;
            }
            if (parts.length == 2 && parts[1].equals("clients")) {
                switch (method) {
                    case "GET" -> {
                        ObjectNode body = JSON.createObjectNode();
                        body.set("clients", clients(s));
                        json(response, callback, 200, body);
                    }
                    case "POST" -> register(s, request, response, callback);
                    default -> {
                        response.getHeaders().put(HttpHeader.ALLOW, "GET, POST");
                        error(response, callback, 405, "method_not_allowed", "GET or POST");
                    }
                }
                return;
            }
            if (parts.length == 3 && parts[1].equals("tokens") && s.proxy != null) {
                error(response, callback, 404, "not_found",
                        "a proxy session has no tokens of its own: the server behind the proxy issues them");
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
            if (parts.length == 3 && parts[1].equals("assertions") && s.proxy != null) {
                error(response, callback, 404, "not_found",
                        "a proxy session hands out no SAML assertions: the server behind the proxy trusts its own identity providers");
                return;
            }
            if (parts.length == 3 && parts[1].equals("assertions") && method.equals("POST")
                    && Session.IDENTITIES.contains(parts[2])) {
                assertion(s, parts[2], request, response, callback);
                return;
            }
            error(response, callback, 404, "not_found", "no such session resource");
        }

        /**
         * A SAML 2.0 assertion about an identity from the session's identity provider (DECISIONS.md
         * D-0087): base64url-encoded, for the session's authorization server, valid for five minutes.
         * The body may name the client, {@code {"client_id": "..."}}, an absolute URI, which becomes
         * the Recipient and an audience; by default it is the session's own client identifier.
         */
        private void assertion(Session s, String name, Request request, Response response, Callback callback)
                throws IOException {
            byte[] raw = apiBody(request);
            if (raw == null) {
                error(response, callback, 413, "too_large", "a request for an assertion is at most " + MAX_API_BODY + " bytes");
                return;
            }
            String client = s.clientId;
            if (raw.length > 0) {
                com.fasterxml.jackson.databind.JsonNode doc;
                try {
                    doc = JSON.readTree(raw);
                } catch (IOException e) {
                    doc = null;
                }
                com.fasterxml.jackson.databind.JsonNode id = doc == null ? null : doc.get("client_id");
                if (doc == null || !doc.isObject() || (id != null && !(id.isTextual() && absolute(id.asText())))) {
                    error(response, callback, 400, "invalid_request",
                            "send nothing, or {\"client_id\": \"...\"} with an absolute URI, as JSON");
                    return;
                }
                if (id != null) {
                    client = id.asText();
                }
            }
            java.time.Duration lifetime = java.time.Duration.ofMinutes(5);
            ObjectNode body = JSON.createObjectNode();
            body.put("assertion", s.assertion(name, client, lifetime));
            body.put("issuer", s.saml.entityId());
            body.put("subject", s.webid(name));
            body.put("recipient", client);
            body.putArray("audience").add(client).add(s.as.issuer());
            body.put("expires_in", lifetime.toSeconds());
            json(response, callback, 200, body);
        }

        private static boolean absolute(String uri) {
            try {
                return URI.create(uri).isAbsolute();
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        /**
         * Registers an OpenID client with the session's provider (CLIENT-TESTING.md section 12.3):
         * {@code {"redirect_uris": [...], "client_id": "..."}}, the identifier optional. A client
         * already registered gets the new redirect URIs.
         */
        private void register(Session s, Request request, Response response, Callback callback) throws IOException {
            byte[] raw = apiBody(request);
            if (raw == null) {
                error(response, callback, 413, "too_large", "a registration is at most " + MAX_API_BODY + " bytes");
                return;
            }
            com.fasterxml.jackson.databind.JsonNode doc;
            try {
                doc = JSON.readTree(raw);
            } catch (IOException e) {
                doc = null;
            }
            if (doc == null || !doc.isObject() || !doc.path("redirect_uris").isArray()
                    || (doc.has("client_id") && !doc.get("client_id").isTextual())) {
                error(response, callback, 400, "invalid_client_metadata",
                        "send {\"redirect_uris\": [\"...\"]}, and optionally \"client_id\", as JSON");
                return;
            }
            List<String> uris = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode u : doc.get("redirect_uris")) {
                uris.add(u.asText());
            }
            String clientId = doc.has("client_id") ? doc.get("client_id").asText()
                    : s.base + "/clients/" + java.util.UUID.randomUUID();
            com.ebremer.touchstone.fixtures.op.RefOpenIdProvider.Client client;
            try {
                client = s.op.register(clientId, uris);
            } catch (IllegalArgumentException e) {
                error(response, callback, 400, "invalid_client_metadata", e.getMessage());
                return;
            }
            LOG.info("session {}: OpenID client registered", s.id);
            ObjectNode body = JSON.createObjectNode();
            body.put("client_id", client.clientId());
            ArrayNode list = body.putArray("redirect_uris");
            client.redirectUris().forEach(list::add);
            json(response, callback, 201, body);
        }

        /**
         * The settings a request's body gives (SessionSettings), none for an empty body; null when
         * the body is refused, the refusal already sent.
         */
        private SessionSettings settings(Request request, Response response, Callback callback) throws IOException {
            byte[] raw = apiBody(request);
            if (raw == null) {
                error(response, callback, 413, "too_large", "settings are at most " + MAX_API_BODY + " bytes");
                return null;
            }
            try {
                return SessionSettings.parse(JSON.readTree(raw));
            } catch (IllegalArgumentException e) {
                error(response, callback, 400, "invalid_settings", e.getMessage());
            } catch (IOException e) {
                error(response, callback, 400, "invalid_settings", "the body is not JSON");
            }
            return null;
        }

        /**
         * The session's results (CLIENT-TESTING.md section 3.6): JSON, or with {@code ?format=} the
         * JSON, EARL (Turtle) or JUnit XML export, named for saving.
         */
        private void results(Session s, Request request, Response response, Callback callback) throws IOException {
            String format = param(request, "format");
            ObjectNode body = SessionReports.results(s, sessions.now());
            String file = "touchstone-client-session-" + s.id;
            switch (format == null ? "" : format) {
                case "" -> json(response, callback, 200, body);
                case "json" -> {
                    response.getHeaders().put(HttpHeader.CONTENT_DISPOSITION, "attachment; filename=\"" + file + ".json\"");
                    json(response, callback, 200, body);
                }
                case "earl" -> text(response, callback, "text/turtle; charset=utf-8", file + "-earl.ttl",
                        SessionReports.earl(s, body));
                case "junit" -> text(response, callback, "application/xml; charset=utf-8", file + "-junit.xml",
                        SessionReports.junit(s, body));
                default -> error(response, callback, 400, "invalid_format", "format is json, earl or junit");
            }
        }

        private ArrayNode clients(Session s) {
            ArrayNode list = JSON.createArrayNode();
            for (var c : s.op.clients()) {
                ObjectNode item = list.addObject();
                item.put("client_id", c.clientId());
                ArrayNode uris = item.putArray("redirect_uris");
                c.redirectUris().forEach(uris::add);
            }
            return list;
        }

        /** What the session page and the API say about a session; never its key, a token or a password. */
        private ObjectNode describe(Session s) {
            ObjectNode body = JSON.createObjectNode();
            body.put("id", s.id);
            body.put("page", config.publicBase() + "/sessions/" + s.id + "/page");
            body.put("api", config.publicBase() + "/sessions/" + s.id);
            body.put("results", config.publicBase() + "/sessions/" + s.id + "/results");
            body.put("rules", rules.rules().size());
            body.put("storage", s.publicStorage());
            if (s.proxy == null) {
                ObjectNode as = body.putObject("authorizationServer");
                as.put("issuer", s.as.issuer());
                as.put("metadata", s.as.metadataUri().toString());
            } else {
                ObjectNode proxy = body.putObject("proxy");
                proxy.put("target", s.proxy.target.id());
                proxy.put("issuer", s.proxy.target.issuer());
                ArrayNode faults = proxy.putArray("faults");
                ProxySession.FAULTS.forEach(faults::add);
            }
            if (s.proxy == null) {
                ObjectNode saml = body.putObject("samlIdentityProvider");
                saml.put("entityId", s.saml.entityId());
            }
            ObjectNode op = body.putObject("openidProvider");
            op.put("issuer", s.op.issuer());
            op.put("discovery", s.op.discoveryUri());
            op.put("registration", config.publicBase() + "/sessions/" + s.id + "/clients");
            op.set("clients", clients(s));
            ObjectNode ids = body.putObject("identities");
            for (String name : Session.IDENTITIES) {
                ObjectNode id = ids.putObject(name);
                id.put("webid", s.webid(name));
                id.put("verificationMethod", s.keyId(name));
                id.put("credentials", config.publicBase() + "/sessions/" + s.id + "/credentials/" + name);
                if (s.proxy == null) {
                    id.put("assertions", config.publicBase() + "/sessions/" + s.id + "/assertions/" + name);
                }
                id.put("role", s.proxy != null ? "whatever the server behind the proxy grants"
                        : name.equals("alice") ? "owns the storage" : "has no access until alice grants it");
            }
            body.put("client", s.clientId);
            body.set("clientUnderTest", SessionReports.clientUnderTest(s.clientUnderTest));
            ArrayNode areas = body.putArray("areas");
            s.areas().forEach(areas::add);
            ObjectNode exports = body.putObject("exports");
            for (String format : List.of("json", "earl", "junit")) {
                exports.put(format, config.publicBase() + "/sessions/" + s.id + "/results?format=" + format);
            }
            body.put("created", s.created.toString());
            body.put("expires", sessions.expiry(s).toString());
            ObjectNode traps = body.putObject("traps");
            if (s.proxy != null) {
                // The server behind the proxy is as it is: the session sets no trap.
                traps.put("none", true);
            } else {
            traps.put("opaquePageUrls", s.traps.opaquePageUrls());
            traps.put("flatResourceUris", s.traps.flatResourceUris());
            traps.put("opaqueLinksetUrls", s.traps.opaqueLinksetUrls());
            traps.put("putOnlyForText", s.traps.putOnlyForText());
            traps.put("linksetPutOnlyForDataResources", true);
            traps.put("decoy", s.traps.decoy());
            traps.put("indexLagSeconds", s.traps.indexLag().toSeconds());
            traps.put("noCombinedUpdates", s.traps.noCombinedUpdates());
            }
            ObjectNode limits = body.putObject("limits");
            limits.put("idleTimeout", config.idleTimeout().toString());
            limits.put("maxLifetime", config.maxLifetime().toString());
            limits.put("maxBodyBytes", config.maxBodyBytes());
            limits.put("maxResources", config.maxResources());
            limits.put("maxStorageBytes", config.maxStorageBytes());
            limits.put("maxExchanges", config.maxExchanges());
            ArrayNode faults = body.putArray("armedFaults");
            if (s.proxy == null) {
                s.storage.armed().forEach(f -> faults.add(f.term()));
            } else {
                s.proxy.armed().forEach(faults::add);
            }
            body.put("recorded", s.recorder.recorded());
            ObjectNode deliveries = body.putObject("deliveries");
            deliveries.put("sent", s.deliveries());
            deliveries.put("inFlight", s.inFlight().get());
            deliveries.put("max", config.maxDeliveries());
            return body;
        }

        private long longParam(Request request, String name, long fallback) {
            String value = param(request, name);
            if (value != null) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
            return fallback;
        }

        /** The first value of query parameter {@code name}, as it is (the API's values need no decoding), or null. */
        private String param(Request request, String name) {
            String query = request.getHttpURI().getQuery();
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith(name + "=")) {
                        return p.substring(name.length() + 1);
                    }
                }
            }
            return null;
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

    /** An exchange from its request until its answer is complete, when it is judged and appended to the log. */
    private final class Pending {
        private final Session session;
        private final Request request;
        private final String url;
        private final Instant at;
        private final long started;
        private final String server;
        private final Map<String, List<String>> requestHeaders;
        private final String issuedVia;
        private final Recorder.Built built;
        private final String builtFromRole;
        private final Map<String, String> advertised;
        byte[] requestBody = new byte[0];
        /** For a POST naming an inbox, whether a subscription already delivered to it; else null. */
        Boolean inboxShared;

        Pending(Session session, Request request, String url, Instant at, long started, String server) {
            this.session = session;
            this.request = request;
            this.url = url;
            this.at = at;
            this.started = started;
            this.server = server;
            this.requestHeaders = headers(request.getHeaders());
            // Taken now, before this exchange's own answer teaches the ledger anything
            // (OBSERVATION.md section 4: an answer never vouches for its own request).
            this.issuedVia = session.recorder.issuedVia(url);
            this.built = issuedVia == null ? session.recorder.builtFrom(url) : null;
            this.builtFromRole = built == null ? null : session.recorder.roleOf(built.from());
            this.advertised = session.recorder.advertisedFor(url);
        }

        void complete(int status, HttpFields responseFields, byte[] responseBody, long responseLength, String role,
                      String limit) {
            Map<String, List<String>> responseHeaders = headers(responseFields);
            String requestType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            String responseType = responseFields.get(HttpHeader.CONTENT_TYPE);
            String requestText = text(requestBody, requestType, requestBody.length);
            String responseText = responseBody == null ? null : text(responseBody, responseType, responseBody.length);
            byte[] judgedBody = requestBody;
            // Taken from the raw body, and from the ledger as it was before this answer.
            TokenRequests.Facts facts = !role.equals("asToken") || !request.getMethod().equals("POST")
                    ? TokenRequests.Facts.NONE
                    : session.proxy == null ? TokenRequests.of(session, requestType, requestBody)
                    : TokenRequests.realmOnly(session, requestType, requestBody);
            if (role.equals("asToken")) {
                rememberIssuedToken(responseText);
            }
            // Credentials in a form body (a token request, a sign-in) and in a token response are
            // kept as fingerprints only (OBSERVATION.md section 9).
            if (essence(requestType) != null && essence(requestType).equals("application/x-www-form-urlencoded")) {
                judgedBody = Redaction.form(new String(requestBody, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                requestText = requestText == null ? null : Redaction.form(requestText);
            }
            if (role.equals("asToken") || role.equals("opToken")) {
                responseText = responseText == null ? null : Redaction.json(responseText);
            }
            // The ledger learns from the whole answer; the log keeps the first part of it.
            session.recorder.learn(url, role, status, responseHeaders, responseText);
            Object subject = request.getAttribute(RefLwsServer.SUBJECT_ATTRIBUTE);
            Presented presented = presentation(request, requestType, requestBody, session.tokens);
            String method = request.getMethod();
            String essence = essence(requestType);
            boolean judged = !role.equals("preflight") && !role.equals("limited");
            boolean repeat = judged && session.recorder.repeats(url, method + "\n" + essence + "\n" + digest(requestBody));
            Object members = request.getAttribute(RefLwsServer.MEMBERS_ATTRIBUTE);
            Object fault = request.getAttribute(RefLwsServer.FAULT_ATTRIBUTE);
            Exchange.Annotations annotations = new Exchange.Annotations(server, role,
                    subject == null ? null : session.identityOf(subject.toString()),
                    presented.fingerprint(), presented.places(), issuedVia != null, issuedVia,
                    built == null ? null : built.by(), built == null ? null : built.from(), builtFromRole,
                    advertised,
                    listed(advertised.get("Allow"), method, false),
                    essence != null && listed(advertised.get("Accept-Patch"), essence, true),
                    essence != null && listed(advertised.get("Accept-Query"), essence, true),
                    repeat, role.equals("container") && Integer.valueOf(0).equals(members),
                    facts.credentialSource(), facts.credential(), facts.audienceIncludesAs(), facts.identifiersAgree(),
                    facts.realmContainsRequest(), null,
                    role.equals("subscriptions") && method.equals("POST") ? inboxShared : null,
                    fault == null ? null : fault.toString(), limit);
            int cap = config.maxRecordedResponseBytes();
            Exchange.Body req = body(requestText, requestBody.length, requestBody.length, cap);
            Exchange.Body res = body(responseText, responseLength, responseBody == null ? 0 : responseBody.length, cap);
            long millis = (System.nanoTime() - started) / 1_000_000;
            String shown = redactUrl(url);
            byte[] body = judgedBody;
            session.recorder.append(seq -> {
                // Judged under the log's lock, in the order of the log (OBSERVATION.md section 6).
                Observed observed = new Observed(seq, method, shown, requestHeaders, body, status, annotations);
                return new Exchange(seq, at.toString(), millis, method, shown, requestHeaders, req, status,
                        responseHeaders, res, annotations, session.judge.judge(observed));
            });
        }

        /** A token the session's token endpoint issued, so the recorder knows it wherever a client puts it. */
        private void rememberIssuedToken(String responseText) {
            if (responseText == null) {
                return;
            }
            try {
                String token = JSON.readTree(responseText).path("access_token").asText(null);
                if (token != null && !token.isEmpty()) {
                    session.tokens.add(token);
                }
            } catch (IOException e) {
                // no JSON, no token
            }
        }
    }

    /** A body's SHA-256, for telling a repeated request (OBSERVATION.md section 4.6). */
    private static String digest(byte[] body) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where a request carried a credential, and the fingerprint of the first one (OBSERVATION.md section 4.4). */
    private record Presented(List<String> places, String fingerprint) {
    }

    private static Presented presentation(Request request, String contentType, byte[] body, Set<String> tokens) {
        Set<String> places = new LinkedHashSet<>();
        String first = null;
        String auth = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (auth != null) {
            int space = auth.indexOf(' ');
            String scheme = space < 0 ? auth : auth.substring(0, space);
            String credential = space < 0 ? "" : auth.substring(space + 1).trim();
            places.add(scheme.equalsIgnoreCase("Bearer") ? "bearer" : "otherScheme");
            first = credential.isEmpty() ? null : credential;
        }
        String query = request.getHttpURI().getQuery();
        String fromQuery = formValue(query, "access_token");
        if (fromQuery == null) {
            fromQuery = tokenAmong(query, tokens);
        }
        if (fromQuery != null) {
            places.add("query");
            first = first == null ? fromQuery : first;
        }
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            String fromForm = formValue(new String(body, StandardCharsets.UTF_8), "access_token");
            if (fromForm != null) {
                places.add("form");
                first = first == null ? fromForm : first;
            }
        }
        for (HttpField field : request.getHeaders()) {
            if (field.getName().equalsIgnoreCase("Authorization") || field.getValue() == null) {
                continue;
            }
            for (String token : tokens) {
                if (field.getValue().contains(token)) {
                    places.add("otherHeader");
                    first = first == null ? token : first;
                }
            }
        }
        if (places.isEmpty()) {
            places.add("none");
        }
        return new Presented(List.copyOf(places), first == null ? null : Redaction.fingerprint(first));
    }

    /** The value of the first query parameter that is a token the session issued, or null. */
    private static String tokenAmong(String query, Set<String> tokens) {
        if (query == null || tokens.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (tokens.contains(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Whether a comma-separated header value lists {@code item}: methods compare exactly (RFC 9110
     * section 9.1), media types by essence.
     */
    private static boolean listed(String headerValue, String item, boolean mediaType) {
        if (headerValue == null) {
            return false;
        }
        for (String member : headerValue.split(",")) {
            String m = mediaType ? essence(member) : member.trim();
            if (item.equals(m)) {
                return true;
            }
        }
        return false;
    }

    private static String essence(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        int semi = mediaType.indexOf(';');
        return (semi < 0 ? mediaType : mediaType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
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
                || t.contains("trig") || t.contains("cid") || t.contains("sparql");
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

    /** A text export, named {@code file} for saving. */
    private static void text(Response response, Callback callback, String type, String file, String body) {
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, type);
        response.getHeaders().put(HttpHeader.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
    }

    /** The request's body, or null when it is larger than a session API request may be. */
    private static byte[] apiBody(Request request) throws IOException {
        try (InputStream in = Content.Source.asInputStream(request)) {
            byte[] raw = in.readNBytes(MAX_API_BODY + 1);
            return raw.length > MAX_API_BODY ? null : raw;
        }
    }

    private static void error(Response response, Callback callback, int status, String error, String message)
            throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("error", error);
        body.put("message", message);
        json(response, callback, status, body);
    }

    /** Serves an identity's controlled identifier document, read-only; null serves a 404. */
    private static Handler identityDocument(Session.Identity identity) {
        return new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                if (identity == null) {
                    response.setStatus(404);
                    callback.succeeded();
                    return true;
                }
                String method = request.getMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD");
                    response.setStatus(405);
                    callback.succeeded();
                    return true;
                }
                byte[] bytes = identity.document().getBytes(StandardCharsets.UTF_8);
                response.setStatus(200);
                // CID 1.0 section 6.1 registers application/cid for controlled identifier documents.
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/cid");
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
                if (method.equals("HEAD")) {
                    callback.succeeded();
                } else {
                    response.write(true, ByteBuffer.wrap(bytes), callback);
                }
                return true;
            }
        };
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
