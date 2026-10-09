package com.ebremer.touchstone.fixtures.lws;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * D-0015). It implements the WD-20261005 behaviour the definitions test:
 * <ul>
 *   <li>containers with {@code items}/{@code totalItems} listings, each member's {@code format},
 *       {@code size} and {@code modified}, and lws+json/ld+json/json conneg with
 *       {@code Vary: Accept};</li>
 *   <li>a storage description served as {@code application/lws+cid} at the storage URI,
 *       advertised by {@code rel="https://www.w3.org/ns/lws#storage"} on every response,
 *       including a 401, and naming the root, access grant and access request services;</li>
 *   <li>{@code Last-Modified}, and the date validators {@code If-Modified-Since} (304) and
 *       {@code If-Unmodified-Since} (412), evaluated in RFC 9110 section 13.2.2's order;</li>
 *   <li>strong ETags with 304 and 412, containment-consistent create and delete, 409 on
 *       deleting a non-empty container unless {@code Depth: infinity}, JSON Patch on JSON data
 *       resources, single byte ranges;</li>
 *   <li>a linkset per resource, stored, with its own ETag, patchable with JSON Patch and
 *       refusing PUT with 405, removed with its resource;</li>
 *   <li>PUT that changes only the content, unless it asks for a combined update with
 *       {@code Prefer: set-linkset}: then its Link headers replace the types and links the
 *       client declared, and the answer carries {@code Preference-Applied: set-linkset}
 *       ({@link Traps#noCombinedUpdates} ignores the preference instead);</li>
 *   <li>access grants and access requests (section 11) as LWS containers, and authorization
 *       by ownership or grant: {@code foaf:Agent} is the public;</li>
 *   <li>container listings paginated above {@value #PAGE_SIZE} members (section 12.1.2), each
 *       page reached through {@code first}/{@code prev}/{@code next}/{@code last} links;</li>
 *   <li>a NotificationService offering {@code WebhookSubscription}: subscriptions are
 *       created (section 10.3, with read access to every topic enforced), read and cancelled,
 *       and each Create, Update and Delete inside a topic is POSTed to the subscriber's inbox
 *       as an application/lws+json Notification (section 10.2), provided the subscriber may
 *       read the resource when the event occurs (10.3.3); a new access grant with an inbox
 *       is announced there too (section 11.6).</li>
 * </ul>
 *
 * <p>Container representations name their context by IRI, as the draft's example and every
 * real server do (D-0026/D-0040). Deliveries are signed with RFC 9421 HTTP Message Signatures,
 * the key published in the storage description; each is sent off the request thread, retried on
 * a 5xx or an unreachable inbox, and a subscription is deactivated after repeated failures or a
 * 410 (the webhook suite's MAYs). Every read advertises the methods the resource supports in
 * {@code Allow}, and for a data resource the patch format in {@code Accept-Patch}.
 *
 * <p>It runs standalone on its own port, or {@linkplain #mounted mounted} under a path of another
 * server, as each client-testing session's storage is (CLIENT-TESTING.md section 4.2). It can set
 * {@link Traps}: legal behaviour a client must not assume away. Each request it handles carries
 * {@link #ROLE_ATTRIBUTE}, {@link #SUBJECT_ATTRIBUTE} and {@link #CLIENT_ATTRIBUTE}, which a
 * recorder wrapping it reads.
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
    private static final String JSON_PATCH = "application/json-patch+json";
    /** The storage URI, which here is also the storage root container. */
    private static final String STORAGE_PATH = "/";
    /** The access grant and access request services: containers outside the storage root's listing. */
    private static final String GRANTS = "/_grants/";
    private static final String REQUESTS = "/_requests/";
    /** The NotificationService endpoint, and its one subscription type. */
    private static final String SUBSCRIPTIONS = "/_subscriptions/";
    private static final String TYPE_INDEX = "/_types/index";
    /** Trap URLs (CLIENT-TESTING.md section 6.1): opaque pages and linksets, flat resources, the decoy. */
    private static final String PAGE_PREFIX = "/_t/p/";
    private static final String LINKSET_PREFIX = "/_t/l/";
    private static final String FLAT_PREFIX = "/_r/";
    private static final String DECOY = "/_t/decoy";
    /** The realm the decoy's challenge names: one that does not contain the decoy. */
    private static final String VAULT = "/_t/vault/";

    /** Request attribute: what the request addressed, such as container, page or linkset. */
    public static final String ROLE_ATTRIBUTE = "touchstone.lws.role";
    /** Request attribute: the subject of a validated access token. */
    public static final String SUBJECT_ATTRIBUTE = "touchstone.lws.subject";
    /** Request attribute: the client_id of a validated access token. */
    public static final String CLIENT_ATTRIBUTE = "touchstone.lws.client";
    /** Request attribute: for a request to a container, how many members it had when the request arrived. */
    public static final String MEMBERS_ATTRIBUTE = "touchstone.lws.members";
    /** Request attribute: the {@link Fault} that fired on the request, by its term. */
    public static final String FAULT_ATTRIBUTE = "touchstone.lws.fault";

    /**
     * Faults a client session arms (CLIENT-TESTING.md section 6.2, OBSERVATION.md section 6.2):
     * each fires once, on the next request it applies to, and each is behaviour a server may
     * legally show.
     */
    public enum Fault {
        /**
         * The next PUT to a linkset that supports PUT is refused 405, with an {@code Allow} that
         * leaves PUT out, as from a server that stopped supporting the optional PUT.
         */
        METHOD_NOT_ALLOWED("methodNotAllowed"),
        /**
         * The next POST that creates a resource creates it, then answers 503 with no
         * {@code Location}, as if the answer had been lost.
         */
        LOST_CREATE_RESPONSE("lostCreateResponse"),
        /** The next request for a page of search results is answered 410, as for an expired page. */
        PAGE_GONE("pageGone"),
        /**
         * The next request with a valid access token, to anything but the decoy, is answered 401
         * with {@code error="invalid_token"}, and the token is refused from then on, as an expired
         * or revoked one would be.
         */
        TOKEN_EXPIRED("tokenExpired"),
        /**
         * The next notification is signed with a key the storage description does not publish,
         * though its keyid names the published one. Like the three below, it is not something a
         * server does but what an attacker does, which an inbox must withstand.
         */
        FORGED_UNPUBLISHED_KEY("forgedUnpublishedKey", "unpublishedKey"),
        /** The next notification is signed, then its body altered; its Content-Digest is the signed one. */
        FORGED_ALTERED_BODY("forgedAlteredBody", "alteredBody"),
        /** The next notification is signed with the published key, but its keyid has no fragment. */
        FORGED_KEYID_WITHOUT_FRAGMENT("forgedKeyidWithoutFragment", "keyidWithoutFragment"),
        /**
         * The next notification is signed with a key of a document under the storage that claims to
         * be its description: the document's id is the storage's, not its own URL's.
         */
        FORGED_FOREIGN_KEY_DOCUMENT("forgedForeignKeyDocument", "foreignKeyDocument");

        private final String term;
        private final String forgery;

        Fault(String term) {
            this(term, null);
        }

        Fault(String term, String forgery) {
            this.term = term;
            this.forgery = forgery;
        }

        /** The fault's name in the client rules and the session API. */
        public String term() {
            return term;
        }

        /** For a forged notification, how its signature is wrong; null for any other fault. */
        public String forgery() {
            return forgery;
        }

        /** The fault named {@code term}, or null. */
        public static Fault of(String term) {
            for (Fault f : values()) {
                if (f.term.equals(term)) {
                    return f;
                }
            }
            return null;
        }
    }
    private static final String TYPE_SEARCH = "/_types/search";
    private static final String LWS_QUERY = "application/lws-query+json";
    /** Groups a type search may hold before it is refused with 422 (lws10-index section 7.2). */
    static final int MAX_FILTER_GROUPS = 32;
    private static final String WEBHOOK = "WebhookSubscription";
    private static final String AS_CONTEXT = "https://www.w3.org/ns/activitystreams";
    /** Delivers notifications off the request thread; one attempt each (section 10.3). */
    private static final java.net.http.HttpClient DELIVERY = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();
    /** A document under the storage that claims to be its description, with a key of its own (Fault#FORGED_FOREIGN_KEY_DOCUMENT). */
    private static final String FOREIGN_KEYS = "/_t/keys";

    /**
     * A notification on its way to an inbox: the request, and how its signature was made.
     *
     * @param forgery the forgery fault it carries, or null for a genuine one
     */
    public record Delivery(URI inbox, Map<String, String> headers, byte[] body, Fault forgery) {
    }

    /** Sends notifications: completes with the inbox's status, or 0 when nothing answered. */
    @FunctionalInterface
    public interface Courier {
        java.util.concurrent.CompletableFuture<Integer> send(Delivery delivery);
    }

    /** Sends a delivery with the JDK client, as a standalone storage does. */
    private static java.util.concurrent.CompletableFuture<Integer> sendDirectly(Delivery d) {
        java.net.http.HttpRequest.Builder post = java.net.http.HttpRequest.newBuilder(d.inbox())
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(d.body()));
        d.headers().forEach(post::header);
        return DELIVERY.sendAsync(post.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                .handle((r, e) -> r == null ? 0 : r.statusCode());
    }
    private static final Set<String> ACTIONS = Set.of("read", "modify", "create", "delete");
    /**
     * Members per page of a container listing. Small, so that the pagination definitions'
     * five-member container spans two pages; no other definition lists more than four.
     */
    static final int PAGE_SIZE = 4;

    /** The preference that asks for a combined update of content and linkset (lws10-core 9.3). */
    private static final String SET_LINKSET = "set-linkset";
    private static final java.util.regex.Pattern LINK_TYPE =
            java.util.regex.Pattern.compile("<([^>]*)>\\s*;[^,<]*\\brel=\"?type\"?(?=[\\s;,]|$)");
    private static final java.util.regex.Pattern TURTLE_SELF_TYPE =
            java.util.regex.Pattern.compile("<>\\s+a\\s+((?:<[^>\\s]*>\\s*,?\\s*)+)");
    /** An absolute IRI: a scheme, then no whitespace or characters RFC 3987 excludes. */
    private static final java.util.regex.Pattern ABSOLUTE_IRI =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:[^\\s<>\"{}|\\\\^`]+");

    private final AuthMode authMode;
    /** Null when mounted: then the server it is mounted in owns the lifecycle. */
    private final Server server;
    private final ServerConnector connector;
    /** A mounted storage's public URI, ending in a slash; null when standalone. */
    private final URI publicStorage;
    /** The request path before a mounted storage's own paths: publicStorage's path, unslashed. */
    private final String mount;
    private final Traps traps;
    private final LwsHandler handler;
    /** Which inboxes a notification may be sent to; a storage open to strangers restricts them. */
    private volatile java.util.function.Predicate<URI> deliveryGuard = uri -> true;
    /** How notifications are sent. */
    private volatile Courier courier = RefLwsServer::sendDirectly;
    /** Whether the linksets of data resources support PUT; containers' never do. */
    private volatile boolean linksetPut;
    /** The faults armed and not yet fired. */
    private final Set<Fault> armed = ConcurrentHashMap.newKeySet();
    /** Access tokens refused from now on, though otherwise valid: those the tokenExpired fault fired on. */
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    /** Opaque page URLs: token to page, and container path and page number to token. */
    private final ConcurrentMap<String, PageRef> pageRefs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> pageTokens = new ConcurrentHashMap<>();
    /** Opaque linkset URLs: token to the path of the resource described. */
    private final ConcurrentMap<String, String> linksetRefs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Node> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Record> grants = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Record> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    /** Consecutive failed deliveries per subscription; at MAX_DELIVERY_FAILURES it is deactivated. */
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicInteger> deliveryFailures =
            new ConcurrentHashMap<>();
    /** Attempts at one delivery: a 5xx or an unreachable inbox is tried again, a second later. */
    static final int DELIVERY_ATTEMPTS = 3;
    /** Consecutive failed deliveries before a subscription is deactivated (the webhook MAYs). */
    static final int MAX_DELIVERY_FAILURES = 5;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String grantsEtag = newEtag();
    private volatile String requestsEtag = newEtag();
    private volatile TokenValidator validator;
    private volatile String asUri;
    private volatile String storageOwner;
    /**
     * The notification twin's defects: delivery skips the subscriber's read check, and is signed
     * with a key the storage description does not publish.
     */
    private volatile boolean deliverToAnyone;
    /** Signs deliveries (lws10-notifications-webhook); its public half is in the storage description. */
    private final com.nimbusds.jose.jwk.ECKey signingKey = newSigningKey("notify-key");
    /** What the notification twin signs with instead, and never publishes. */
    private final com.nimbusds.jose.jwk.ECKey unpublishedKey = newSigningKey("notify-key");
    /** The key the document at FOREIGN_KEYS publishes. */
    private final com.nimbusds.jose.jwk.ECKey foreignKey = newSigningKey("notify-key");

    private static com.nimbusds.jose.jwk.ECKey newSigningKey(String kid) {
        try {
            return new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID(kid).generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Node {
        final boolean container;
        volatile byte[] bytes;
        volatile String contentType;
        volatile String etag = newEtag();
        volatile String owner;
        volatile Instant modified = now();
        volatile ObjectNode linkset;
        /** Types the client declared: {@link #linkTypes}, and {@code <> a} in Turtle content. */
        volatile Set<String> declaredTypes = Set.of();
        /**
         * Types from Link rel="type": on create, or on a PUT that asked for a combined update with
         * {@code Prefer: set-linkset}. Any other PUT changes only the content (lws10-core 9.3).
         */
        volatile Set<String> linkTypes = Set.of();
        /** Descriptive links the client sent as Link headers on create or on a combined update: rel to targets. */
        volatile java.util.Map<String, Set<String>> declaredLinks = java.util.Map.of();
        volatile String linksetEtag = newEtag();
        final Set<String> children = ConcurrentHashMap.newKeySet();
        /** The container's path; null for the root. A flat URI says nothing about it. */
        volatile String parent;
        /** The opaque linkset URL's token, when that trap is set. */
        volatile String linksetToken;
        /** When the types or relations the index derives last changed (Traps#indexLag). */
        volatile Instant indexed = Instant.now();

        Node(boolean container) {
            this.container = container;
        }
    }

    /** One page of a container, as an opaque page URL names it. */
    private record PageRef(String path, int page) {
    }

    /** How much a storage holds: its resources, grants, requests and subscriptions, and their bytes. */
    public record Usage(int resources, long bytes) {
    }

    /** A stored access grant or access request: its document, its policies, who made it. */
    private record Record(String id, ObjectNode document, List<Policy> policies, String author, String etag) {
    }

    /** A webhook subscription: who made it, what it covers, where it would deliver. */
    private record Subscription(String id, String subscriber, String client, List<String> topics, String inbox,
                                String expires) {
    }

    /** One AccessPolicy: actions, an assignee, and the resources it covers. */
    private record Policy(Set<String> actions, String assignee, Set<String> targets, List<Constraint> constraints) {
    }

    /** The leftOperands the Access Profile obliges a server advertising it to support. */
    private static final Set<String> LEFT_OPERANDS = Set.of("client", "format", "type", "purpose", "dateTime");
    private static final Set<String> OPERATORS = Set.of("eq", "isAnyOf", "gt", "gteq", "lt", "lteq");

    /**
     * An ODRL constraint of the Access Profile. {@code dateTime} compares the current time;
     * {@code client} the access token's {@code client_id}; {@code format} and {@code type} the
     * resource's media type and LWS type. The draft does not say how a request states its
     * {@code purpose}, so a purpose constraint is accepted and never satisfied (fail closed), as
     * is any operator that makes no sense for its operand.
     */
    private record Constraint(String leftOperand, String operator, JsonNode rightOperand) {
        boolean satisfied(Node node, String client) {
            return switch (leftOperand) {
                case "dateTime" -> compare(Instant.now());
                case "client" -> matches(client);
                case "format" -> matches(node.container ? LWS_JSON : node.contentType);
                case "type" -> matches(LWS_NS + (node.container ? "Container" : "DataResource"));
                default -> false;
            };
        }

        private boolean matches(String actual) {
            if (actual == null) {
                return false;
            }
            return switch (operator) {
                case "eq" -> rightOperand.isTextual() && rightOperand.asText().equals(actual);
                case "isAnyOf" -> {
                    for (JsonNode v : rightOperand) {
                        if (v.isTextual() && v.asText().equals(actual)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                default -> false;
            };
        }

        private boolean compare(Instant now) {
            Instant bound;
            try {
                bound = Instant.parse(rightOperand.asText());
            } catch (RuntimeException e) {
                return false;
            }
            int c = now.compareTo(bound);
            return switch (operator) {
                case "eq" -> c == 0;
                case "gt" -> c > 0;
                case "gteq" -> c >= 0;
                case "lt" -> c < 0;
                case "lteq" -> c <= 0;
                default -> false;
            };
        }
    }

    private RefLwsServer(AuthMode authMode, URI publicStorage, Traps traps) {
        this.authMode = authMode;
        this.publicStorage = publicStorage;
        this.mount = publicStorage == null ? "" : publicStorage.getRawPath().replaceAll("/$", "");
        this.traps = traps;
        this.handler = new LwsHandler();
        if (publicStorage == null) {
            this.server = new Server();
            this.connector = new ServerConnector(server);
            // A run opens many connections at once; the OS default backlog refused some of them.
            connector.setAcceptQueueSize(256);
            server.addConnector(connector);
            server.setHandler(handler);
        } else {
            this.server = null;
            this.connector = null;
        }
        Node root = new Node(true);
        store.put(STORAGE_PATH, root);
        if (traps.decoy()) {
            Node decoy = new Node(false);
            decoy.bytes = new byte[0];
            decoy.contentType = "text/plain";
            decoy.parent = STORAGE_PATH;
            store.put(DECOY, decoy);
            root.children.add(DECOY);
        }
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
        return startSecured(port, as, owner, Traps.NONE);
    }

    /** Secured mode with {@code traps} set: the self-test's trapped deployment. */
    public static RefLwsServer startSecured(int port, RefAuthorizationServer as, String owner, Traps traps) {
        RefLwsServer server = startIn(AuthMode.SECURED, port, traps);
        String realm = server.realm();
        try {
            server.validator = new TokenValidator(as.issuer(), as.jwksUri().toURL(), realm);
        } catch (Exception e) {
            server.close();
            throw new IllegalStateException("cannot build token validator", e);
        }
        server.secure(as, owner);
        return server;
    }

    /**
     * A secured storage served by another server's handler at {@code storage}, its public URI
     * (ending in a slash; the path of a request it handles must start with that URI's path). Its
     * authorization server is {@code as}, consulted in process, and nothing is delivered until
     * {@link #deliverOnlyTo} allows it.
     */
    public static RefLwsServer mounted(URI storage, RefAuthorizationServer as, String owner, Traps traps) {
        if (!storage.toString().endsWith("/")) {
            throw new IllegalArgumentException("a storage URI ends in a slash: " + storage);
        }
        RefLwsServer server = new RefLwsServer(AuthMode.SECURED, storage, traps);
        server.validator = new TokenValidator(as.issuer(), as.jwkSource(), server.realm());
        server.deliveryGuard = uri -> false;
        server.secure(as, owner);
        return server;
    }

    private void secure(RefAuthorizationServer as, String owner) {
        asUri = as.issuer();
        storageOwner = owner;
        store.get(STORAGE_PATH).owner = owner;
        as.addResource(realm());
    }

    /** The handler that serves this storage, for a mounted one to be placed in another server. */
    public Handler handler() {
        return handler;
    }

    /**
     * Lets the linksets of data resources support PUT, and advertise it in {@code Allow}, while
     * containers' linksets still refuse it: PUT on a linkset is optional (WD section 9.3.2), so a
     * client must read {@code Allow} before it replaces one. Off by default, because the server
     * self-test needs linksets without PUT.
     */
    public void linksetPutOnDataResources(boolean supported) {
        this.linksetPut = supported;
    }

    /** Arms {@code fault}: it fires once, on the next request it applies to. */
    public void arm(Fault fault) {
        armed.add(fault);
    }

    /** The faults armed that have not fired yet. */
    public Set<Fault> armed() {
        return Set.copyOf(armed);
    }

    /** Fires {@code fault} on this request if it is armed, marking the request; returns whether it fired. */
    private boolean fire(Request request, Fault fault) {
        if (armed.remove(fault)) {
            request.setAttribute(FAULT_ATTRIBUTE, fault.term());
            return true;
        }
        return false;
    }

    /** Lets notifications go only to the inboxes {@code guard} accepts. */
    public void deliverOnlyTo(java.util.function.Predicate<URI> guard) {
        this.deliveryGuard = guard;
    }

    /** Sends notifications through {@code courier}, as a client-testing session does, which guards and records them. */
    public void deliverWith(Courier courier) {
        this.courier = courier;
    }

    /** The armed forgery the next notification carries, disarmed; null when none is armed. */
    private Fault nextForgery() {
        for (Fault f : Fault.values()) {
            if (f.forgery() != null && armed.remove(f)) {
                return f;
            }
        }
        return null;
    }

    /** The inboxes the storage's webhook subscriptions deliver to now. */
    public Set<String> subscriptionInboxes() {
        Set<String> out = new java.util.HashSet<>();
        subscriptions.values().forEach(s -> {
            if (s.inbox() != null) {
                out.add(s.inbox());
            }
        });
        return out;
    }

    /** What the storage holds now, not counting its root and the decoy. */
    public Usage usage() {
        int resources = grants.size() + requests.size() + subscriptions.size();
        long bytes = 0;
        for (var e : store.entrySet()) {
            if (e.getKey().equals(STORAGE_PATH) || e.getKey().equals(DECOY)) {
                continue;
            }
            resources++;
            byte[] b = e.getValue().bytes;
            bytes += b == null ? 0 : b.length;
        }
        for (Record r : grants.values()) {
            bytes += r.document().toString().length();
        }
        for (Record r : requests.values()) {
            bytes += r.document().toString().length();
        }
        return new Usage(resources, bytes);
    }

    /**
     * The notification twin: a secured, compliant storage in every respect but one. It delivers
     * every notification in a subscription's scope, whether or not the subscriber may read the
     * resource (lws10-core 10.3.3), so the delivery-time authorization tests must fail against it.
     */
    public static RefLwsServer startLeakingNotifications(int port, RefAuthorizationServer as, String owner) {
        RefLwsServer server = startSecured(port, as, owner);
        server.deliverToAnyone = true;
        return server;
    }

    /** Broken mode: the deliberately non-compliant twin (validates nothing, forbids nothing). */
    public static RefLwsServer startBroken(int port) {
        return startIn(AuthMode.BROKEN, port);
    }

    private static RefLwsServer startIn(AuthMode mode, int port) {
        return startIn(mode, port, Traps.NONE);
    }

    private static RefLwsServer startIn(AuthMode mode, int port, Traps traps) {
        RefLwsServer instance = new RefLwsServer(mode, null, traps);
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
        return publicStorage != null ? publicStorage : URI.create("http://localhost:" + connector.getLocalPort() + "/");
    }

    /** The realm access tokens must be issued for: the storage's base URI. */
    public String realm() {
        return baseUri().toString();
    }

    /**
     * What a run left behind: every stored resource but the root, and every access grant,
     * access request and subscription. The self-test loop checks it is empty once a run has
     * cleaned up.
     */
    public List<String> residue() {
        List<String> out = new ArrayList<>();
        store.keySet().stream().filter(p -> !p.equals(STORAGE_PATH) && !p.equals(DECOY)).sorted().forEach(out::add);
        grants.keySet().forEach(id -> out.add(GRANTS + id));
        requests.keySet().forEach(id -> out.add(REQUESTS + id));
        subscriptions.keySet().forEach(id -> out.add(SUBSCRIPTIONS + id));
        return out;
    }

    public void join() throws InterruptedException {
        if (server != null) {
            server.join();
        }
    }

    @Override
    public void close() {
        if (server == null) {
            return;
        }
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
            if (publicStorage != null) {
                if (path == null || !path.startsWith(mount + "/")) {
                    role(request, "unknown");
                    status(request, response, callback, 404);
                    return true;
                }
                path = path.substring(mount.length());
            }

            String subject = null;
            String client = null;
            boolean invalidToken = false;
            String bearer = null;
            if (authMode == AuthMode.SECURED) {
                bearer = bearerToken(request);
                if (bearer != null) {
                    try {
                        if (revoked.contains(bearer)) {
                            throw new TokenValidator.InvalidTokenException("the token has expired");
                        }
                        com.nimbusds.jwt.JWTClaimsSet claims = validator.claims(bearer);
                        subject = claims.getSubject();
                        Object c = claims.getClaim("client_id");
                        client = c == null ? null : c.toString();
                        request.setAttribute(SUBJECT_ATTRIBUTE, subject);
                        if (client != null) {
                            request.setAttribute(CLIENT_ATTRIBUTE, client);
                        }
                    } catch (TokenValidator.InvalidTokenException e) {
                        invalidToken = true;
                    }
                }
            }
            if (traps.decoy() && path.equals(FOREIGN_KEYS)) {
                role(request, "keyDocument");
                foreignKeys(request, response, callback, method);
                return true;
            }
            if (traps.decoy() && path.equals(DECOY)) {
                role(request, "decoy");
                decoy(request, response, callback);
                return true;
            }
            // A refused token is answered as soon as the target's role is known, so that a recorded
            // exchange says what was addressed. Nothing before that answers differently.
            boolean refuse = invalidToken;
            if (subject != null && fire(request, Fault.TOKEN_EXPIRED)) {
                revoked.add(bearer);
                request.removeAttribute(SUBJECT_ATTRIBUTE);
                request.removeAttribute(CLIENT_ATTRIBUTE);
                refuse = true;
            }
            // An opaque page URL stands for a container and a page of it; anything else under
            // the page prefix is unknown, and a page is only read.
            int page = 0;
            if (traps.opaquePageUrls() && path.startsWith(PAGE_PREFIX)) {
                PageRef ref = pageRefs.get(path.substring(PAGE_PREFIX.length()));
                if (ref == null || !store.containsKey(ref.path())) {
                    role(request, "unknown");
                    if (refuse) {
                        challenge(request, response, callback, "invalid_token");
                    } else {
                        status(request, response, callback, 404);
                    }
                    return true;
                }
                role(request, "page");
                if (refuse) {
                    challenge(request, response, callback, "invalid_token");
                    return true;
                }
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    methodNotAllowed(response, callback, "GET, HEAD");
                    return true;
                }
                path = ref.path();
                page = ref.page();
            }

            if (path.startsWith(SUBSCRIPTIONS)) {
                role(request, path.equals(SUBSCRIPTIONS) ? "subscriptions" : "subscription");
                if (refuse) {
                    challenge(request, response, callback, "invalid_token");
                    return true;
                }
                subscriptions(request, response, callback, path, method, subject, client);
                return true;
            }
            if (path.equals(TYPE_INDEX) || path.equals(TYPE_SEARCH)) {
                role(request, path.equals(TYPE_INDEX) ? "typeIndex"
                        : request.getHttpURI().getQuery() != null ? "searchPage" : "typeSearch");
                if (refuse) {
                    challenge(request, response, callback, "invalid_token");
                    return true;
                }
                if (authMode == AuthMode.SECURED && subject == null) {
                    challenge(request, response, callback, null);
                } else {
                    new TypeServices(subject, client).handle(request, response, callback, path, method);
                }
                return true;
            }
            if (path.startsWith(GRANTS) || path.startsWith(REQUESTS)) {
                boolean service = path.equals(GRANTS) || path.equals(REQUESTS);
                role(request, (path.startsWith(GRANTS) ? "accessGrant" : "accessRequest") + (service ? "s" : ""));
                if (refuse) {
                    challenge(request, response, callback, "invalid_token");
                    return true;
                }
                new Registry(path.startsWith(GRANTS)).handle(request, response, callback, path, method, subject);
                return true;
            }

            String resourcePath = linksetSubject(path);
            boolean linkset = resourcePath != null;
            String target = linkset ? resourcePath : path;
            // The storage description is public: a client refused with a 401 finds the services
            // through it, and a webhook receiver finds the delivery signing key in it. The root
            // container's listing, served at the same URI for the LWS container types, is not.
            // Only on an explicit request for it, so an anonymous probe of the root without an
            // Accept still meets the challenge the engine discovers the authorization server by.
            String accept = request.getHeaders().get("Accept");
            boolean description = target.equals(STORAGE_PATH) && !linkset
                    && (method.equals("GET") || method.equals("HEAD"))
                    && accept != null && accept.contains(LWS_CID);
            if (page == 0) {
                Node addressed = store.get(target);
                // With opaque page URLs a page number in the query names nothing.
                boolean builtPage = traps.opaquePageUrls() && hasPageParameter(request);
                role(request, linkset ? "linkset" : description ? "storageDescription"
                        : addressed == null || builtPage ? "unknown" : !addressed.container ? "dataResource"
                        : requestedPage(request) > 1 ? "page" : "container");
                if (addressed != null && addressed.container && !linkset && !description) {
                    request.setAttribute(MEMBERS_ATTRIBUTE, addressed.children.size());
                }
            }
            if (refuse) {
                challenge(request, response, callback, "invalid_token");
                return true;
            }
            if (authMode == AuthMode.SECURED && !description) {
                String action = switch (method) {
                    case "GET", "HEAD", "OPTIONS" -> "read";
                    case "POST" -> linkset ? "modify" : "create";
                    case "DELETE" -> linkset ? "modify" : "delete";
                    default -> "modify";
                };
                Node node = store.get(target);
                if (node == null ? subject == null : !allowed(action, node, absolute(request, target), subject, client)) {
                    if (subject == null) {
                        challenge(request, response, callback, null);
                    } else {
                        status(request, response, callback, 403);
                    }
                    return true;
                }
            }

            if (linkset) {
                linksetResource(request, response, callback, resourcePath, method);
                return true;
            }
            switch (method) {
                case "GET" -> read(request, response, callback, path, true, page);
                case "HEAD" -> read(request, response, callback, path, false, page);
                case "POST" -> create(request, response, callback, path, subject);
                case "PUT" -> update(request, response, callback, path);
                case "PATCH" -> patch(request, response, callback, path);
                case "DELETE" -> delete(request, response, callback, path);
                default -> methodNotAllowed(response, callback, "GET, HEAD, POST, PUT, PATCH, DELETE");
            }
            return true;
        }

        private static void role(Request request, String role) {
            request.setAttribute(ROLE_ATTRIBUTE, role);
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
        private boolean allowed(String action, Node node, String uri, String subject, String client) {
            if (subject != null && (subject.equals(storageOwner) || subject.equals(node.owner))) {
                return true;
            }
            for (Record grant : grants.values()) {
                for (Policy p : grant.policies()) {
                    boolean assignee = p.assignee().equals(FOAF_AGENT) || p.assignee().equals(subject);
                    // "When multiple constraint objects are present, all of them MUST be satisfied."
                    if (assignee && p.actions().contains(action) && p.targets().contains(uri)
                            && p.constraints().stream().allMatch(c -> c.satisfied(node, client))) {
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
            status(request, response, callback, 401);
        }

        /**
         * The decoy (Traps#decoy): a 401 whatever is sent, naming a realm that does not contain the
         * decoy. A client that checks "that the URI of the originating request is logically
         * contained within the realm" before it presents a token never sends this one any.
         */
        private void decoy(Request request, Response response, Callback callback) {
            StringBuilder c = new StringBuilder("Bearer ");
            if (asUri != null) {
                c.append("as_uri=\"").append(asUri).append("\", ");
            }
            c.append("realm=\"").append(absolute(request, VAULT)).append('"');
            if (bearerToken(request) != null) {
                c.append(", error=\"invalid_token\"");
            }
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, c.toString());
            response.getHeaders().add("Link", storageLink(request));
            status(request, response, callback, 401);
        }

        /**
         * A document that claims to be the storage description, with a key of its own: its id is
         * the storage's, not its own URL's, so an inbox that follows lws10-notifications-webhook
         * section 5.2 step 3 refuses a notification whose keyid names it. Anyone who can write a
         * resource under a storage can publish one.
         */
        private void foreignKeys(Request request, Response response, Callback callback, String method) throws Exception {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                methodNotAllowed(response, callback, "GET, HEAD");
                return;
            }
            String self = absolute(request, FOREIGN_KEYS);
            ObjectNode doc = mapper.createObjectNode();
            doc.putArray("@context").add("https://www.w3.org/ns/cid/v1").add(LWS_CONTEXT);
            doc.put("id", absolute(request, STORAGE_PATH));
            doc.put("type", "Storage");
            ObjectNode vm = doc.putArray("verificationMethod").addObject();
            vm.put("id", self + "#notify-key");
            vm.put("type", "JsonWebKey");
            vm.put("controller", absolute(request, STORAGE_PATH));
            vm.set("publicKeyJwk", mapper.readTree(foreignKey.toPublicJWK().toJSONString()));
            doc.putArray("authentication").add(self + "#notify-key");
            byte[] bytes = bytes(doc);
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, LWS_CID);
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
            if (method.equals("HEAD")) {
                callback.succeeded();
            } else {
                response.write(true, ByteBuffer.wrap(bytes), callback);
            }
        }

        private String storageLink(Request request) {
            return "<" + absolute(request, STORAGE_PATH) + ">; rel=\"" + LWS_NS + "storage\"";
        }

        // ---- read ----

        /** @param explicitPage the page an opaque page URL named, or 0 to take it from the query */
        private void read(Request request, Response response, Callback callback, String path, boolean withBody,
                          int explicitPage) {
            Node node = store.get(path);
            if (node == null) {
                status(request, response, callback, 404);
                return;
            }
            // With opaque page URLs, a page number in the query is a URL the server never gave.
            if (traps.opaquePageUrls() && explicitPage == 0 && hasPageParameter(request)) {
                status(request, response, callback, 404);
                return;
            }
            // "Servers SHOULD support conditional requests as defined in [RFC9110], including
            // mechanisms such as entity tags (ETags) and date-based validators (like
            // If-Modified-Since headers)." Evaluated in RFC 9110 section 13.2.2's order: a date
            // validator only when the matching entity-tag validator is absent.
            Instant lastModified = node.modified.truncatedTo(ChronoUnit.SECONDS);
            if (request.getHeaders().get("If-Match") == null) {
                Instant unmodifiedSince = httpDate(request.getHeaders().get("If-Unmodified-Since"));
                if (unmodifiedSince != null && lastModified.isAfter(unmodifiedSince)) {
                    status(request, response, callback, 412);
                    return;
                }
            }
            String ifNoneMatch = request.getHeaders().get("If-None-Match");
            boolean notModified = ifNoneMatch != null
                    ? ifNoneMatch.equals("*") || ifNoneMatch.equals(node.etag)
                    : !isAfter(lastModified, modifiedSince(request.getHeaders().get("If-Modified-Since")));
            if (notModified) {
                response.getHeaders().put(HttpHeader.ETAG, node.etag);
                response.getHeaders().put(HttpHeader.LAST_MODIFIED, httpDate(lastModified));
                addResourceLinks(request, response, path, node);
                advertise(response, path, node);
                status(request, response, callback, 304);
                return;
            }
            int pages = node.container ? Math.max(1, (node.children.size() + PAGE_SIZE - 1) / PAGE_SIZE) : 1;
            int page = !node.container ? 1 : explicitPage > 0 ? explicitPage : requestedPage(request);
            if (page < 1 || page > pages) {
                status(request, response, callback, 404);
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
                    status(request, response, callback, 406);
                    return;
                }
                response.getHeaders().put(HttpHeader.VARY, "Accept");
                body = LWS_CID.equals(contentType) ? storageDescription(request) : listing(request, path, node, page);
            } else if (node.container) {
                contentType = negotiate(request.getHeaders().get("Accept"));
                if (contentType == null) {
                    status(request, response, callback, 406);
                    return;
                }
                // "Because the Content-Type of a container response depends on the request's
                // Accept header, these responses SHOULD include a Vary: Accept header."
                response.getHeaders().put(HttpHeader.VARY, "Accept");
                body = listing(request, path, node, page);
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
                        status(request, response, callback, 416);
                        return;
                    }
                    int from = (int) r[0];
                    int to = (int) r[1];
                    byte[] slice = new byte[to - from + 1];
                    System.arraycopy(body, from, slice, 0, slice.length);
                    response.setStatus(206);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
                    response.getHeaders().put(HttpHeader.ETAG, node.etag);
                    response.getHeaders().put(HttpHeader.LAST_MODIFIED, httpDate(lastModified));
                    response.getHeaders().put("Content-Range", "bytes " + from + "-" + to + "/" + body.length);
                    addResourceLinks(request, response, path, node);
                    advertise(response, path, node);
                    send(response, callback, slice, withBody);
                    return;
                }
            }
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
            // Each page is its own representation, so a later page has its own entity tag.
            response.getHeaders().put(HttpHeader.ETAG, page == 1 ? node.etag
                    : node.etag.substring(0, node.etag.length() - 1) + "-page" + page + '"');
            response.getHeaders().put(HttpHeader.LAST_MODIFIED, httpDate(lastModified));
            addResourceLinks(request, response, path, node);
            advertise(response, path, node);
            if (pages > 1) {
                addPageLinks(request, response, path, page, pages);
            }
            send(response, callback, body, withBody);
        }

        /**
         * The methods a resource supports, and for a data resource the patch format: "servers MUST
         * use standard HTTP headers to advertise their capabilities", and a client should not
         * assume PUT or a patch format it was not told of.
         */
        private void advertise(Response response, String path, Node node) {
            if (node.container) {
                response.getHeaders().put(HttpHeader.ALLOW,
                        path.equals(STORAGE_PATH) ? "GET, HEAD, POST" : "GET, HEAD, POST, DELETE");
            } else {
                response.getHeaders().put(HttpHeader.ALLOW, dataResourceMethods(node));
                response.getHeaders().put("Accept-Patch", JSON_PATCH);
            }
        }

        private String dataResourceMethods(Node node) {
            return puttable(node) ? "GET, HEAD, PUT, PATCH, DELETE" : "GET, HEAD, PATCH, DELETE";
        }

        /** Whether PUT replaces this data resource: always, unless Traps#putOnlyForText excludes it. */
        private boolean puttable(Node node) {
            if (!traps.putOnlyForText()) {
                return true;
            }
            String type = node.contentType == null ? "" : node.contentType.toLowerCase(Locale.ROOT);
            return type.startsWith("text/") || type.contains("json") || type.contains("xml")
                    || type.contains("n-triples") || type.contains("n-quads") || type.contains("trig");
        }

        private static boolean hasPageParameter(Request request) {
            String query = request.getHttpURI().getQuery();
            if (query == null) {
                return false;
            }
            for (String param : query.split("&")) {
                if (param.startsWith("page=")) {
                    return true;
                }
            }
            return false;
        }

        /** The page a container read asks for: 1 without a page parameter, 0 for a malformed one. */
        private static int requestedPage(Request request) {
            String query = request.getHttpURI().getQuery();
            if (query == null) {
                return 1;
            }
            for (String param : query.split("&")) {
                if (param.startsWith("page=")) {
                    try {
                        return Integer.parseInt(param.substring("page=".length()));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
            return 1;
        }

        /**
         * "rel=first ... MUST be present on paginated responses", next "MUST be omitted on the
         * last page", prev "MUST be omitted on the first page"; last is a MAY, given here.
         */
        private void addPageLinks(Request request, Response response, String path, int page, int pages) {
            response.getHeaders().add("Link", "<" + pageUri(request, path, 1) + ">; rel=\"first\"");
            if (page > 1) {
                response.getHeaders().add("Link", "<" + pageUri(request, path, page - 1) + ">; rel=\"prev\"");
            }
            if (page < pages) {
                response.getHeaders().add("Link", "<" + pageUri(request, path, page + 1) + ">; rel=\"next\"");
            }
            response.getHeaders().add("Link", "<" + pageUri(request, path, pages) + ">; rel=\"last\"");
        }

        private String pageUri(Request request, String path, int page) {
            if (!traps.opaquePageUrls()) {
                return absolute(request, path) + "?page=" + page;
            }
            String token = pageTokens.computeIfAbsent(path + "#" + page, k -> {
                String t = UUID.randomUUID().toString();
                pageRefs.put(t, new PageRef(path, page));
                return t;
            });
            return absolute(request, PAGE_PREFIX + token);
        }

        /** True unless {@code since} is a date and {@code modified} is no later than it. */
        private static boolean isAfter(Instant modified, Instant since) {
            return since == null || modified.isAfter(since);
        }

        /** If-Modified-Since's date; one later than now is invalid and ignored (RFC 9110 13.1.3). */
        private static Instant modifiedSince(String value) {
            Instant since = httpDate(value);
            return since != null && since.isAfter(Instant.now()) ? null : since;
        }

        /** An HTTP-date, or {@code null} when absent or invalid (RFC 9110 13.1.3: then ignored). */
        private static Instant httpDate(String value) {
            if (value == null) {
                return null;
            }
            try {
                return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static String httpDate(Instant instant) {
            return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
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
            if (traps.opaqueLinksetUrls()) {
                if (!path.startsWith(LINKSET_PREFIX)) {
                    return null;
                }
                // A token for a resource that is gone, or none at all, answers 404 as a linkset.
                String described = linksetRefs.get(path.substring(LINKSET_PREFIX.length()));
                return described != null ? described : path;
            }
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
         * A resource's linkset (RFC 9264): GET and HEAD, and PATCH with JSON Patch, which
         * the Allow and Accept-Patch headers advertise. PUT is not supported, so it is 405 with
         * the methods that are, unless {@link #linksetPutOnDataResources} lets a data resource's
         * linkset be replaced.
         */
        private void linksetResource(Request request, Response response, Callback callback, String path, String method)
                throws Exception {
            Node node = store.get(path);
            if (node == null) {
                status(request, response, callback, 404);
                return;
            }
            boolean puttable = linksetPut && !node.container;
            String allow = puttable ? "GET, HEAD, PUT, PATCH" : "GET, HEAD, PATCH";
            response.getHeaders().put(HttpHeader.ALLOW, allow);
            response.getHeaders().put("Accept-Patch", JSON_PATCH);
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
                    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(JSON_PATCH)) {
                        status(request, response, callback, 415);
                        return;
                    }
                    String ifMatch = request.getHeaders().get("If-Match");
                    if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.linksetEtag)) {
                        status(request, response, callback, 412);
                        return;
                    }
                    JsonNode patched;
                    try (InputStream in = Content.Source.asInputStream(request)) {
                        patched = JsonPatch.apply(linksetDocument(request, path, node), mapper.readTree(in.readAllBytes()));
                    } catch (JsonPatch.Failure e) {
                        status(request, response, callback, e.status());
                        return;
                    } catch (Exception e) {
                        status(request, response, callback, 400);
                        return;
                    }
                    if (!patched.isObject() || !patched.path("linkset").isArray()) {
                        status(request, response, callback, 422);
                        return;
                    }
                    node.linkset = (ObjectNode) patched;
                    node.linksetEtag = newEtag();
                    node.indexed = Instant.now();
                    response.getHeaders().put(HttpHeader.ETAG, node.linksetEtag);
                    status(request, response, callback, 204);
                }
                case "PUT" -> {
                    if (!puttable) {
                        methodNotAllowed(response, callback, allow);
                        return;
                    }
                    if (fire(request, Fault.METHOD_NOT_ALLOWED)) {
                        methodNotAllowed(response, callback, "GET, HEAD, PATCH");
                        return;
                    }
                    String contentType = request.getHeaders().get("Content-Type");
                    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(LINKSET_JSON)) {
                        status(request, response, callback, 415);
                        return;
                    }
                    String ifMatch = request.getHeaders().get("If-Match");
                    if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.linksetEtag)) {
                        status(request, response, callback, 412);
                        return;
                    }
                    JsonNode replacement;
                    try (InputStream in = Content.Source.asInputStream(request)) {
                        replacement = mapper.readTree(in.readAllBytes());
                    } catch (Exception e) {
                        status(request, response, callback, 400);
                        return;
                    }
                    if (replacement == null || !replacement.isObject() || !replacement.path("linkset").isArray()) {
                        status(request, response, callback, 422);
                        return;
                    }
                    node.linkset = (ObjectNode) replacement;
                    node.linksetEtag = newEtag();
                    node.indexed = Instant.now();
                    response.getHeaders().put(HttpHeader.ETAG, node.linksetEtag);
                    status(request, response, callback, 204);
                }
                default -> methodNotAllowed(response, callback, allow);
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
                status(request, response, callback, 404);
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
            child.parent = path;
            synchronized (store) {
                childPath = traps.flatResourceUris()
                        ? FLAT_PREFIX + UUID.randomUUID() + (isContainer ? "/" : "")
                        : path + unique(path, slug, isContainer) + (isContainer ? "/" : "");
                store.put(childPath, child);
            }
            if (!isContainer) {
                child.linkTypes = linkTypes(request);
                child.declaredTypes = declaredTypes(absolute(request, childPath), child);
                child.declaredLinks = declaredLinks(request, absolute(request, childPath));
            }
            parent.children.add(childPath);
            parent.etag = newEtag();
            parent.modified = now();
            announce(request, "Create", childPath, child, "target", path);
            if (fire(request, Fault.LOST_CREATE_RESPONSE)) {
                // Created, but the client never learns it: no Location, a server error.
                response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
                status(request, response, callback, 503);
                return;
            }

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
                status(request, response, callback, 404);
                return;
            }
            if (node.container) {
                methodNotAllowed(response, callback, "GET, HEAD, POST, DELETE");
                return;
            }
            if (!puttable(node)) {
                methodNotAllowed(response, callback, dataResourceMethods(node));
                return;
            }
            String ifMatch = request.getHeaders().get("If-Match");
            if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(request, response, callback, 412);
                return;
            }
            try (InputStream in = Content.Source.asInputStream(request)) {
                node.bytes = in.readAllBytes();
            }
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType != null) {
                node.contentType = contentType;
            }
            // A PUT changes the content only, "with no default impact on the associated linkset";
            // its Link headers replace the client's links only in a combined update, which the
            // client asks for with Prefer: set-linkset (lws10-core section 9.3). The reference
            // supports combined updates on PUT, and says so with Preference-Applied (RFC 7240),
            // unless Traps#noCombinedUpdates has it ignore the preference, as the draft allows.
            boolean combined = !traps.noCombinedUpdates() && prefersSetLinkset(request);
            if (combined) {
                node.linkTypes = linkTypes(request);
                node.declaredLinks = declaredLinks(request, absolute(request, path));
            }
            node.declaredTypes = declaredTypes(absolute(request, path), node);
            node.indexed = Instant.now();
            touch(path, node);
            announce(request, "Update", path, node, null, null);
            response.setStatus(204);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            if (combined) {
                response.getHeaders().put("Preference-Applied", SET_LINKSET);
            }
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
         * JSON Patch (RFC 6902), the baseline every server has to understand, on a data resource
         * whose representation is JSON; any other is 415, as a format the resource does not
         * support (RFC 5789 section 2.2). Containers are not patchable: their representation is
         * derived from containment, not stored. {@code If-Match} is honoured when sent and not
         * demanded. A patch applies whole or not at all.
         */
        private void patch(Request request, Response response, Callback callback, String path) throws Exception {
            Node node = store.get(path);
            if (node == null) {
                status(request, response, callback, 404);
                return;
            }
            if (node.container) {
                methodNotAllowed(response, callback, "GET, HEAD, POST, DELETE");
                return;
            }
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(JSON_PATCH)) {
                response.getHeaders().put("Accept-Patch", JSON_PATCH);
                status(request, response, callback, 415);
                return;
            }
            String ifMatch = request.getHeaders().get("If-Match");
            if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(request, response, callback, 412);
                return;
            }
            byte[] body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readAllBytes();
            }
            JsonNode target;
            try {
                target = node.bytes == null || node.bytes.length == 0
                        ? mapper.createObjectNode() : mapper.readTree(node.bytes);
            } catch (Exception e) {
                // The stored representation is not JSON, so JSON Patch does not apply to it.
                response.getHeaders().put("Accept-Patch", JSON_PATCH);
                status(request, response, callback, 415);
                return;
            }
            JsonNode patched;
            try {
                patched = JsonPatch.apply(target, mapper.readTree(body));
            } catch (JsonPatch.Failure e) {
                status(request, response, callback, e.status());
                return;
            } catch (Exception e) {
                // The patch document is not JSON.
                status(request, response, callback, 400);
                return;
            }
            node.bytes = mapper.writeValueAsBytes(patched);
            node.contentType = "application/json";
            touch(path, node);
            announce(request, "Update", path, node, null, null);
            response.setStatus(204);
            response.getHeaders().put(HttpHeader.ETAG, node.etag);
            callback.succeeded();
        }

        // ---- delete ----

        private void delete(Request request, Response response, Callback callback, String path) {
            Node node = store.get(path);
            if (node == null) {
                status(request, response, callback, 404);
                return;
            }
            if (path.equals(STORAGE_PATH)) {
                methodNotAllowed(response, callback, "GET, HEAD, POST");
                return;
            }
            String ifMatch = request.getHeaders().get("If-Match");
            if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(node.etag)) {
                status(request, response, callback, 412);
                return;
            }
            if (node.container && !node.children.isEmpty()) {
                String depth = request.getHeaders().get("Depth");
                if (!"infinity".equalsIgnoreCase(depth)) {
                    status(request, response, callback, 409);
                    return;
                }
            }
            // Announced before the resource goes, while who may read it can still be decided.
            String parentPath = parentOf(path);
            announce(request, "Delete", path, node, "origin", parentPath);
            removeRecursively(path);
            Node parent = store.get(parentPath);
            if (parent != null) {
                parent.children.remove(path);
                parent.etag = newEtag();
                parent.modified = now();
            }
            status(request, response, callback, 204);
        }

        private void removeRecursively(String path) {
            Node node = store.remove(path);
            if (node != null && node.linksetToken != null) {
                linksetRefs.remove(node.linksetToken);
            }
            if (node != null && node.container) {
                for (String child : List.copyOf(node.children)) {
                    removeRecursively(child);
                }
            }
        }

        // ---- representations ----

        /**
         * One page of a container's listing: id, type and totalItems describe the whole
         * container, items only the members on this page (section 12.1.2.1).
         */
        private byte[] listing(Request request, String path, Node node, int page) {
            ObjectNode root = mapper.createObjectNode();
            // The context is sent by IRI, as the draft's own example does and as a real server
            // does; harness-core resolves it from its bundled copy, offline (D-0026).
            root.put("@context", LWS_CONTEXT);
            root.put("id", absolute(request, path));
            root.put("type", "Container");
            List<String> children = new ArrayList<>(node.children);
            // The decoy comes first, so a client reading only the first page still meets it.
            children.sort(java.util.Comparator.comparing((String c) -> !c.equals(DECOY))
                    .thenComparing(java.util.Comparator.naturalOrder()));
            int total = 0;
            List<String> onPage = new ArrayList<>();
            for (String childPath : children) {
                if (store.containsKey(childPath)) {
                    if (total >= (page - 1) * PAGE_SIZE && total < page * PAGE_SIZE) {
                        onPage.add(childPath);
                    }
                    total++;
                }
            }
            ArrayNode items = mapper.createArrayNode();
            for (String childPath : onPage) {
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
            root.put("totalItems", total);
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
            service(services, storage + "#access-grants", "AccessGrantService", absolute(request, GRANTS))
                    .putArray("conformsTo").add(LWS_NS + "AccessProfile");
            service(services, storage + "#access-requests", "AccessRequestService", absolute(request, REQUESTS))
                    .putArray("conformsTo").add(LWS_NS + "AccessProfile");
            service(services, storage + "#notifications", "NotificationService", absolute(request, SUBSCRIPTIONS))
                    .putArray("subscriptionType").add(WEBHOOK);
            service(services, storage + "#type-index", "TypeIndexService", absolute(request, TYPE_INDEX));
            service(services, storage + "#type-search", "TypeSearchService", absolute(request, TYPE_SEARCH));
            // The key deliveries are signed with, as the webhook suite requires: a verification
            // method in verificationMethod, referenced from authentication.
            ObjectNode vm = root.putArray("verificationMethod").addObject();
            vm.put("id", storage + "#notify-key");
            vm.put("type", "JsonWebKey");
            vm.put("controller", storage);
            try {
                vm.set("publicKeyJwk", mapper.readTree(signingKey.toPublicJWK().toJSONString()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            root.putArray("authentication").add(storage + "#notify-key");
            return bytes(root);
        }

        private ObjectNode service(ArrayNode services, String id, String type, String endpoint) {
            ObjectNode s = services.addObject();
            s.put("id", id);
            s.put("type", type);
            s.put("serviceEndpoint", endpoint);
            return s;
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

        /**
         * A resource's linkset lives beside it; the suffix is this fixture's convention, not the
         * spec's. With Traps#opaqueLinksetUrls it is a random URL instead, made on first use.
         */
        private String linksetOf(String path) {
            if (traps.opaqueLinksetUrls()) {
                Node node = store.get(path);
                if (node != null) {
                    synchronized (node) {
                        if (node.linksetToken == null) {
                            String token = UUID.randomUUID().toString();
                            linksetRefs.put(token, path);
                            node.linksetToken = token;
                        }
                        return LINKSET_PREFIX + node.linksetToken;
                    }
                }
            }
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
            if (publicStorage != null) {
                return publicStorage.resolve(path.substring(1)).toString();
            }
            return URI.create(request.getHttpURI().asString()).resolve(path).toString();
        }

        /** The storage path of an absolute URI this storage serves, or null for any other URI. */
        private String pathOf(Request request, String uri) {
            try {
                if (publicStorage != null) {
                    String base = publicStorage.toString();
                    return uri.startsWith(base) && uri.indexOf('?') < 0 && uri.indexOf('#') < 0
                            ? "/" + uri.substring(base.length()) : null;
                }
                String path = URI.create(uri).getRawPath();
                return path != null && absolute(request, path).equals(uri) ? path : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** The container a resource was created in; for a path the store no longer has, its path's parent. */
        private String parentOf(String path) {
            Node node = store.get(path);
            if (node != null && node.parent != null) {
                return node.parent;
            }
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
        }

        /** Whether {@code path} lies inside the container {@code container}, at any depth. */
        private boolean inside(String path, String container) {
            String p = path;
            for (int depth = 0; depth < 1024 && p != null && !p.isEmpty() && !p.equals(STORAGE_PATH); depth++) {
                p = parentOf(p);
                if (p.equals(container)) {
                    return true;
                }
            }
            return false;
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

        /**
         * A bodiless status, or for an error RFC 9457 problem details: "Servers SHOULD use the
         * standard format defined in [RFC9457] for structured error responses". HEAD never
         * carries a body.
         */
        private void status(Request request, Response response, Callback callback, int code) {
            response.setStatus(code);
            if (code < 400 || "HEAD".equals(request.getMethod())) {
                callback.succeeded();
                return;
            }
            ObjectNode problem = mapper.createObjectNode();
            problem.put("type", "about:blank");
            problem.put("title", org.eclipse.jetty.http.HttpStatus.getMessage(code));
            problem.put("status", code);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/problem+json");
            response.write(true, ByteBuffer.wrap(bytes(problem)), callback);
        }

        private void methodNotAllowed(Response response, Callback callback, String allow) {
            response.setStatus(405);
            response.getHeaders().put(HttpHeader.ALLOW, allow);
            callback.succeeded();
        }

        // ---- type index and type search (lws10-index) ----

        /**
         * The types a client declared for a data resource: each Link rel="type" outside the LWS
         * namespace (section 5, the SHOULD), and, for Turtle, the objects of {@code <> a ...}
         * statements (the MAY: a reference server reads that one form, not Turtle at large).
         */
        /** The types a request's Link rel="type" headers declare, LWS's own classes aside. */
        private Set<String> linkTypes(Request request) {
            Set<String> types = new LinkedHashSet<>();
            String links = String.join(", ", request.getHeaders().getValuesList("Link"));
            java.util.regex.Matcher link = LINK_TYPE.matcher(links);
            while (link.find()) {
                String iri = link.group(1);
                if (!iri.startsWith(LWS_NS) && ABSOLUTE_IRI.matcher(iri).matches()) {
                    types.add(iri);
                }
            }
            return Set.copyOf(types);
        }

        /** Whether a request's Prefer header lists set-linkset (RFC 7240: tokens are case-insensitive). */
        private boolean prefersSetLinkset(Request request) {
            for (String field : request.getHeaders().getValuesList("Prefer")) {
                for (String preference : field.split(",")) {
                    String token = preference.split("[;=]", 2)[0].trim();
                    if (token.equalsIgnoreCase(SET_LINKSET)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** A resource's declared types: those from Link headers, and those its Turtle content states. */
        private Set<String> declaredTypes(String uri, Node node) {
            Set<String> types = new LinkedHashSet<>(node.linkTypes);
            if (node.contentType != null && node.contentType.toLowerCase(Locale.ROOT).startsWith("text/turtle")
                    && node.bytes != null) {
                java.util.regex.Matcher a = TURTLE_SELF_TYPE.matcher(new String(node.bytes, StandardCharsets.UTF_8));
                while (a.find()) {
                    java.util.regex.Matcher iri = java.util.regex.Pattern.compile("<([^>\\s]*)>").matcher(a.group(1));
                    while (iri.find()) {
                        types.add(URI.create(uri).resolve(iri.group(1)).toString());
                    }
                }
            }
            return Set.copyOf(types);
        }

        /** Relations the search never indexes: structural or protocol ones (section 7.1). */
        private static final Set<String> STRUCTURAL_RELATIONS = Set.of("type", "up", "linkset", "acl",
                "first", "prev", "next", "last", "self", "describes", LWS_NS + "storage", "storagedescription");

        /** The descriptive Link headers of a create or update, by relation (lower-cased if registered). */
        private java.util.Map<String, Set<String>> declaredLinks(Request request, String uri) {
            java.util.Map<String, Set<String>> out = new java.util.HashMap<>();
            String links = String.join(", ", request.getHeaders().getValuesList("Link"));
            java.util.regex.Matcher link = java.util.regex.Pattern
                    .compile("<([^>]*)>\\s*((?:;[^,<]*)*)").matcher(links);
            while (link.find()) {
                java.util.regex.Matcher rel = java.util.regex.Pattern
                        .compile("\\brel=\"?([^\";,]+)\"?").matcher(link.group(2));
                if (!rel.find()) {
                    continue;
                }
                for (String r : rel.group(1).trim().split("\\s+")) {
                    String key = r.contains(":") ? r : r.toLowerCase(Locale.ROOT);
                    if (!STRUCTURAL_RELATIONS.contains(key)) {
                        out.computeIfAbsent(key, k -> new LinkedHashSet<>())
                                .add(URI.create(uri).resolve(link.group(1)).toString());
                    }
                }
            }
            java.util.Map<String, Set<String>> frozen = new java.util.HashMap<>();
            out.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
            return java.util.Map.copyOf(frozen);
        }

        /**
         * A resource's targets for a relation, from its Link headers and its linkset alike: the
         * draft has every source of a relation treated the same (section 7.1).
         */
        private Set<String> relationTargets(String uri, Node node, String rel) {
            String key = rel.contains(":") ? rel : rel.toLowerCase(Locale.ROOT);
            if (STRUCTURAL_RELATIONS.contains(key)) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>(node.declaredLinks.getOrDefault(key, Set.of()));
            ObjectNode linkset = node.linkset;
            if (linkset != null) {
                for (JsonNode entry : linkset.path("linkset")) {
                    if (!uri.equals(entry.path("anchor").asText())) {
                        continue;
                    }
                    entry.fields().forEachRemaining(f -> {
                        String k = f.getKey().contains(":") ? f.getKey() : f.getKey().toLowerCase(Locale.ROOT);
                        if (k.equals(key)) {
                            for (JsonNode target : f.getValue()) {
                                if (target.hasNonNull("href")) {
                                    try {
                                        out.add(URI.create(uri).resolve(target.get("href").asText()).toString());
                                    } catch (IllegalArgumentException e) {
                                        // a linkset may hold a target that is no URI; it matches nothing
                                    }
                                }
                            }
                        }
                    });
                }
            }
            return out;
        }

        /**
         * The Type Index Service (GET) and the Type Search Service (QUERY). Both list only what the
         * client may read now (section 8): nothing is cached, so a revoked grant is gone from the
         * next response. The type index answers in one page; the search pages at PAGE_SIZE, with
         * stateless page links. Descriptive relations are indexed from Link headers and the linkset; structural
         * ones never are, so a key naming one matches nothing (section 7.1).
         */
        private final class TypeServices {
            private final String subject;
            private final String client;

            TypeServices(String subject, String client) {
                this.subject = subject;
                this.client = client;
            }

            void handle(Request request, Response response, Callback callback, String path, String method)
                    throws Exception {
                boolean search = path.equals(TYPE_SEARCH);
                String allow = search ? "OPTIONS, QUERY" : "GET, HEAD, OPTIONS";
                if (method.equals("OPTIONS")) {
                    response.getHeaders().put(HttpHeader.ALLOW, allow);
                    if (search) {
                        response.getHeaders().put("Accept-Query", LWS_QUERY);
                    }
                    response.setStatus(204);
                    callback.succeeded();
                    return;
                }
                // A page link of a result set is the filter, base64url-encoded, and a page number:
                // the server keeps nothing, and the link is dereferenced with GET (section 7.1).
                String q = search ? queryParam(request, "q") : null;
                boolean pageLink = q != null && (method.equals("GET") || method.equals("HEAD"));
                if (!pageLink && (search ? !method.equals("QUERY") : !(method.equals("GET") || method.equals("HEAD")))) {
                    if (search) {
                        response.getHeaders().put("Accept-Query", LWS_QUERY);
                    }
                    methodNotAllowed(response, callback, allow);
                    return;
                }
                List<List<String>> typeGroups = List.of();
                java.util.Map<String, List<List<String>>> relationGroups = new java.util.LinkedHashMap<>();
                byte[] filterBytes = null;
                if (pageLink && fire(request, Fault.PAGE_GONE)) {
                    status(request, response, callback, 410);
                    return;
                }
                if (pageLink) {
                    try {
                        filterBytes = java.util.Base64.getUrlDecoder().decode(q);
                    } catch (IllegalArgumentException e) {
                        status(request, response, callback, 404);
                        return;
                    }
                }
                if (search && !pageLink) {
                    String contentType = request.getHeaders().get("Content-Type");
                    if (contentType == null || contentType.isBlank()) {
                        status(request, response, callback, 400);
                        return;
                    }
                    String essence = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (!essence.equals(LWS_QUERY)) {
                        response.getHeaders().put("Accept-Query", LWS_QUERY);
                        status(request, response, callback, 415);
                        return;
                    }
                    try (InputStream in = Content.Source.asInputStream(request)) {
                        filterBytes = in.readAllBytes();
                    }
                }
                if (search) {
                    JsonNode filter;
                    try {
                        filter = filterBytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(filterBytes);
                    } catch (Exception e) {
                        filter = null;
                    }
                    if (filter == null || !filter.isObject()) {
                        status(request, response, callback, 400);
                        return;
                    }
                    int groups = 0;
                    List<List<String>> types = new ArrayList<>();
                    for (var field : (Iterable<java.util.Map.Entry<String, JsonNode>>) filter::fields) {
                        if (field.getKey().startsWith("@")) {
                            continue;
                        }
                        List<List<String>> parsed = groups(field.getValue());
                        if (parsed == null) {
                            status(request, response, callback, 400);
                            return;
                        }
                        groups += parsed.size();
                        if (field.getKey().equals("type")) {
                            types.addAll(parsed);
                        } else if (!parsed.isEmpty()) {
                            relationGroups.computeIfAbsent(field.getKey(), k -> new ArrayList<>()).addAll(parsed);
                        }
                    }
                    if (groups > MAX_FILTER_GROUPS) {
                        status(request, response, callback, 422);
                        return;
                    }
                    typeGroups = types;
                }
                String accept = request.getHeaders().get("Accept");
                if (!acceptable(accept)) {
                    status(request, response, callback, 406);
                    return;
                }
                // lws+json, or ld+json for a client that asks for it and not for lws+json: the
                // response is negotiated, so it varies on Accept (section 7.2).
                String mediaType = accept != null && accept.contains("application/ld+json")
                        && !accept.contains(LWS_JSON) ? "application/ld+json" : LWS_JSON;
                ObjectNode doc = mapper.createObjectNode();
                doc.put("@context", LWS_CONTEXT);
                ArrayNode items = mapper.createArrayNode();
                if (search) {
                    doc.put("type", "ContainerPage");
                    java.util.TreeMap<String, Set<String>> matches = new java.util.TreeMap<>();
                    readable(request).forEach((uri, types) -> matches.put(uri, types));
                    for (var e : matches.entrySet()) {
                        String matchPath = pathOf(request, e.getKey());
                        Node node = matchPath == null ? null : store.get(matchPath);
                        boolean all = typeGroups.stream().allMatch(g -> g.stream().anyMatch(e.getValue()::contains))
                                && relationGroups.entrySet().stream().allMatch(r -> {
                                    Set<String> targets = node == null ? Set.of() : relationTargets(e.getKey(), node, r.getKey());
                                    return r.getValue().stream().allMatch(g -> g.stream().anyMatch(targets::contains));
                                });
                        if (all) {
                            ObjectNode item = items.addObject();
                            item.put("id", e.getKey());
                            ArrayNode t = item.putArray("type");
                            e.getValue().forEach(v -> t.add(v.startsWith(LWS_NS) ? v.substring(LWS_NS.length()) : v));
                        }
                    }
                } else {
                    doc.put("type", "TypeIndex");
                    java.util.TreeSet<String> types = new java.util.TreeSet<>();
                    readable(request).values().forEach(types::addAll);
                    types.forEach(v -> items.addObject().put("id", v));
                }
                doc.put("totalItems", items.size());
                ArrayNode shown = items;
                if (search) {
                    // PAGE_SIZE results a page, so the paging definition spans pages here.
                    int page = pageLink ? requestedPage(request) : 1;
                    int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                    if (page < 1 || page > pages) {
                        status(request, response, callback, 404);
                        return;
                    }
                    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(filterBytes);
                    String base = absolute(request, TYPE_SEARCH) + "?q=" + token + "&page=";
                    response.getHeaders().add("Link", "<" + base + 1 + ">; rel=\"first\"");
                    if (!pageLink) {
                        // RFC 10008 lets a QUERY response name a resource representing its results.
                        // lws10-index defines none, but one that is exposed "MUST subject it to the
                        // same authorization filtering on every access and MUST prevent its reuse
                        // across clients": the first page link is such a resource, since every GET
                        // of it re-runs the filter for whoever asks, and so it is named here.
                        response.getHeaders().put("Content-Location", base + 1);
                    }
                    if (page < pages) {
                        response.getHeaders().add("Link", "<" + base + (page + 1) + ">; rel=\"next\"");
                    }
                    ArrayNode slice = mapper.createArrayNode();
                    for (int i = (page - 1) * PAGE_SIZE; i < Math.min(items.size(), page * PAGE_SIZE); i++) {
                        slice.add(items.get(i));
                    }
                    shown = slice;
                }
                doc.set("items", shown);
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, mediaType);
                // authorization-filtered, so never to be reused for another client (section 8)
                response.getHeaders().put(HttpHeader.CACHE_CONTROL, "private, no-store");
                response.getHeaders().put(HttpHeader.VARY, "Authorization, Accept");
                send(response, callback, bytes(doc), !method.equals("HEAD"));
            }

            /** A key's value as CNF groups, or null when it breaks the grammar (section 7.2). */
            private List<List<String>> groups(JsonNode value) {
                if (!value.isArray()) {
                    return null;
                }
                List<List<String>> out = new ArrayList<>();
                for (JsonNode element : value) {
                    List<String> group = new ArrayList<>();
                    if (element.isTextual()) {
                        group.add(element.asText());
                    } else if (element.isArray() && !element.isEmpty()) {
                        for (JsonNode member : element) {
                            if (!member.isTextual()) {
                                return null;
                            }
                            group.add(member.asText());
                        }
                    } else {
                        return null;
                    }
                    if (!group.stream().allMatch(v -> ABSOLUTE_IRI.matcher(v).matches())) {
                        return null;
                    }
                    if (!out.contains(group)) {
                        out.add(group);
                    }
                }
                return out;
            }

            /** Every resource the client may read now, with its types. */
            private java.util.Map<String, Set<String>> readable(Request request) {
                java.util.Map<String, Set<String>> out = new java.util.HashMap<>();
                Instant settled = Instant.now().minus(traps.indexLag());
                store.forEach((path, node) -> {
                    // The decoy is a trap for listings, not for the index; a write the index has
                    // not caught up with yet (Traps#indexLag) is left out, never shown stale.
                    if (path.equals(DECOY) || node.indexed.isAfter(settled)) {
                        return;
                    }
                    String uri = absolute(request, path);
                    if (authMode != AuthMode.SECURED || allowed("read", node, uri, subject, client)) {
                        Set<String> types = new LinkedHashSet<>();
                        types.add(LWS_NS + (node.container ? "Container" : "DataResource"));
                        types.addAll(node.declaredTypes);
                        out.put(uri, types);
                    }
                });
                return out;
            }

            private static String queryParam(Request request, String name) {
                String query = request.getHttpURI().getQuery();
                if (query == null) {
                    return null;
                }
                for (String param : query.split("&")) {
                    if (param.startsWith(name + "=")) {
                        return param.substring(name.length() + 1);
                    }
                }
                return null;
            }

            /** Whether Accept admits lws+json or ld+json, the formats these services produce. */
            private boolean acceptable(String accept) {
                if (accept == null || accept.isBlank()) {
                    return true;
                }
                for (String range : accept.split(",")) {
                    String[] parts = range.split(";");
                    String type = parts[0].trim().toLowerCase(Locale.ROOT);
                    boolean zero = false;
                    for (int i = 1; i < parts.length; i++) {
                        String p = parts[i].trim().toLowerCase(Locale.ROOT);
                        if (p.startsWith("q=") && p.substring(2).trim().matches("0(\\.0{0,3})?")) {
                            zero = true;
                        }
                    }
                    if (!zero && (type.equals("*/*") || type.equals("application/*") || type.equals(LWS_JSON)
                            || type.equals("application/ld+json"))) {
                        return true;
                    }
                }
                return false;
            }
        }

        // ---- notification delivery ----

        /**
         * Tells every subscriber whose topic covers {@code path} that it changed (section 10.2.3):
         * a topic covers itself and, for a container, everything inside it (10.3.2). A subscriber
         * that may not read the resource now hears nothing (10.3.3): that is decided here, at
         * the event, which for a Delete is before the resource is gone. The broken twin, which
         * forbids nothing, tells everyone.
         *
         * @param relation {@code target} for a Create, {@code origin} for a Delete, else null
         */
        private void announce(Request request, String type, String path, Node node, String relation, String related) {
            String uri = absolute(request, path);
            for (Subscription s : subscriptions.values()) {
                // Containment, not URI prefixes: a flat URI does not nest under its container's.
                boolean covered = s.topics().stream().anyMatch(t -> {
                    if (t.equals(uri)) {
                        return true;
                    }
                    String topic = t.endsWith("/") ? pathOf(request, t) : null;
                    return topic != null && inside(path, topic);
                });
                if (!covered) {
                    continue;
                }
                if (authMode == AuthMode.SECURED && !deliverToAnyone
                        && !allowed("read", node, uri, s.subscriber(), s.client())) {
                    continue;
                }
                ObjectNode activity = mapper.createObjectNode();
                activity.putArray("type").add(type);
                ObjectNode object = activity.putObject("object");
                object.put("id", uri);
                object.putArray("type").add(node.container ? "Container" : "DataResource");
                if (relation != null) {
                    activity.put(relation, absolute(request, related));
                }
                deliver(request, s.inbox(), activity, s.id());
            }
        }

        /**
         * POSTs one Notification envelope around {@code activity} to {@code inbox}: lws+json with
         * the LWS and Activity Streams contexts, the storage, and an id and published time on the
         * activity (section 10.2). No actor: "The actor property SHOULD be omitted by default."
         */
        /**
         * Sends one notification. The webhook suite lets a server retry a failed delivery and
         * deactivate a subscription after repeated failures (both MAY), and the reference does
         * both: a 5xx or an unreachable inbox is tried up to DELIVERY_ATTEMPTS times, re-signed
         * each time; 410 Gone deactivates the subscription at once, and MAX_DELIVERY_FAILURES
         * failed deliveries in a row do too. {@code subscription} is null for a notification
         * that is not a subscription's (an access request's).
         */
        private void deliver(Request request, String inbox, ObjectNode activity, String subscription) {
            activity.put("id", "urn:uuid:" + UUID.randomUUID());
            activity.put("published", DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)));
            ObjectNode envelope = mapper.createObjectNode();
            envelope.putArray("@context").add(LWS_CONTEXT).add(AS_CONTEXT);
            envelope.put("type", "Notification");
            envelope.put("storage", absolute(request, STORAGE_PATH));
            envelope.set("activity", activity);
            try {
                URI target = URI.create(inbox);
                if (!deliveryGuard.test(target)) {
                    return;
                }
                byte[] body = bytes(envelope);
                Fault forgery = nextForgery();
                String storage = absolute(request, STORAGE_PATH);
                String keyid = forgery == Fault.FORGED_KEYID_WITHOUT_FRAGMENT ? storage
                        : forgery == Fault.FORGED_FOREIGN_KEY_DOCUMENT ? absolute(request, FOREIGN_KEYS) + "#notify-key"
                        : storage + "#notify-key";
                attempt(target, body, keyid, subscription, 1, forgery);
            } catch (RuntimeException e) {
                // best-effort: an inbox that cannot be reached loses the notification
            }
        }

        private void attempt(URI target, byte[] body, String keyid, String subscription, int attempt, Fault forgery) {
            com.nimbusds.jose.jwk.ECKey key = forgery == Fault.FORGED_UNPUBLISHED_KEY || deliverToAnyone ? unpublishedKey
                    : forgery == Fault.FORGED_FOREIGN_KEY_DOCUMENT ? foreignKey : signingKey;
            Signed signed = sign(target, body, keyid, key);
            byte[] sent = body;
            if (forgery == Fault.FORGED_ALTERED_BODY) {
                // Altered after signing: the Content-Digest and the signature are the original's.
                String text = new String(body, StandardCharsets.UTF_8);
                sent = (text.substring(0, text.lastIndexOf('}')) + ",\"summary\":\"altered after it was signed\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", LWS_JSON);
            headers.put("Content-Digest", signed.contentDigest());
            headers.put("Signature-Input", signed.signatureInput());
            headers.put("Signature", signed.signature());
            courier.send(new Delivery(target, headers, sent, forgery)).whenComplete((answered, error) -> {
                int status = answered == null ? 0 : answered;
                if (status / 100 == 2) {
                    if (subscription != null) {
                        deliveryFailures.remove(subscription);
                    }
                    return;
                }
                boolean retryable = status == 0 || status / 100 == 5;
                if (retryable && attempt < DELIVERY_ATTEMPTS) {
                    java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                            .execute(() -> attempt(target, body, keyid, subscription, attempt + 1, forgery));
                    return;
                }
                if (subscription == null) {
                    return;
                }
                int failures = deliveryFailures.computeIfAbsent(subscription,
                        k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                if (status == 410 || failures >= MAX_DELIVERY_FAILURES) {
                    subscriptions.remove(subscription);
                    deliveryFailures.remove(subscription);
                }
            });
        }

        private record Signed(String contentDigest, String signatureInput, String signature) {
        }

        /**
         * An RFC 9421 signature over the components the webhook suite requires (@method, @scheme,
         * @authority, @path, content-type, content-digest), with created and keyid, ES256.
         */
        private Signed sign(URI target, byte[] body, String keyid, com.nimbusds.jose.jwk.ECKey key) {
            try {
                String digest = "sha-256=:" + java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(body)) + ":";
                String params = "(\"@method\" \"@scheme\" \"@authority\" \"@path\" \"content-type\" \"content-digest\")"
                        + ";created=" + Instant.now().getEpochSecond() + ";keyid=\"" + keyid + "\";alg=\"ecdsa-p256-sha256\"";
                String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
                String base = "\"@method\": POST\n\"@scheme\": " + target.getScheme() + "\n\"@authority\": "
                        + target.getRawAuthority() + "\n\"@path\": " + path + "\n\"content-type\": " + LWS_JSON
                        + "\n\"content-digest\": " + digest + "\n\"@signature-params\": " + params;
                java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
                s.initSign(key.toECPrivateKey());
                s.update(base.getBytes(StandardCharsets.UTF_8));
                return new Signed(digest, "sig1=" + params,
                        "sig1=:" + java.util.Base64.getEncoder().encodeToString(s.sign()) + ":");
            } catch (Exception e) {
                throw new IllegalStateException("cannot sign a delivery", e);
            }
        }

        // ---- subscriptions ----

        /**
         * The NotificationService (section 10.3). POST creates a subscription from an
         * application/lws+json request whose type is one advertised, whose topic is a non-empty
         * array of URIs, and, as a WebhookSubscription, an inbox URI. The subscriber must be able
         * to read every topic, or the request is refused. Its subscriber and the storage owner may
         * read and cancel it.
         */
        private void subscriptions(Request request, Response response, Callback callback, String path,
                                   String method, String subject, String client) throws Exception {
            boolean secured = authMode == AuthMode.SECURED;
            String id = path.substring(SUBSCRIPTIONS.length());
            if (id.isEmpty()) {
                if (method.equals("GET") || method.equals("HEAD")) {
                    if (secured && subject == null) {
                        challenge(request, response, callback, null);
                        return;
                    }
                    listSubscriptions(request, response, callback, subject, method.equals("GET"));
                    return;
                }
                if (!method.equals("POST")) {
                    methodNotAllowed(response, callback, "GET, HEAD, POST");
                    return;
                }
                if (secured && subject == null) {
                    challenge(request, response, callback, null);
                    return;
                }
                subscribe(request, response, callback, subject, client);
                return;
            }
            Subscription sub = subscriptions.get(id);
            boolean mine = sub != null && (!secured || (subject != null
                    && (subject.equals(sub.subscriber()) || subject.equals(storageOwner))));
            if (!mine) {
                // A subscription discloses its topics and inbox; to anyone else it does not exist.
                if (secured && subject == null) {
                    challenge(request, response, callback, null);
                } else {
                    status(request, response, callback, 404);
                }
                return;
            }
            switch (method) {
                case "GET", "HEAD" -> {
                    response.setStatus(200);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, LWS_JSON);
                    send(response, callback, bytes(subscriptionDocument(request, sub)), method.equals("GET"));
                }
                case "DELETE" -> {
                    subscriptions.remove(id);
                    status(request, response, callback, 204);
                }
                default -> methodNotAllowed(response, callback, "GET, HEAD, DELETE");
            }
        }

        private void subscribe(Request request, Response response, Callback callback, String subject, String client)
                throws Exception {
            // "The request body MUST conform to the application/lws+json media type."
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(LWS_JSON)) {
                status(request, response, callback, 415);
                return;
            }
            JsonNode body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = mapper.readTree(in.readAllBytes());
            } catch (Exception e) {
                status(request, response, callback, 400);
                return;
            }
            // type and topic are REQUIRED, type one of the advertised subscription types, topic an
            // array of URIs; a WebhookSubscription also needs the inbox it delivers to.
            JsonNode topic = body == null ? null : body.path("topic");
            if (body == null || !body.isObject() || !WEBHOOK.equals(body.path("type").asText(null))
                    || !topic.isArray() || topic.isEmpty() || !isUri(body.path("inbox"))) {
                status(request, response, callback, 400);
                return;
            }
            List<String> topics = new ArrayList<>();
            for (JsonNode t : topic) {
                if (!isUri(t)) {
                    status(request, response, callback, 400);
                    return;
                }
                topics.add(t.asText());
            }
            // "If a subscriber does not have the equivalent of read access to all resources
            // listed in the topic array, the server MUST reject the subscription request." The
            // broken twin forbids nothing, so it subscribes anyone to anything.
            if (authMode == AuthMode.SECURED) {
                for (String t : topics) {
                    String topicPath = pathOf(request, t);
                    Node node = topicPath == null ? null : store.get(topicPath);
                    if (node == null || !allowed("read", node, t, subject, client)) {
                        status(request, response, callback, 403);
                        return;
                    }
                }
            }
            String id = UUID.randomUUID().toString();
            Subscription sub = new Subscription(id, subject, client, List.copyOf(topics), body.path("inbox").asText(),
                    body.path("expires").isTextual() ? body.path("expires").asText() : null);
            subscriptions.put(id, sub);
            response.setStatus(201);
            response.getHeaders().put(HttpHeader.LOCATION, absolute(request, SUBSCRIPTIONS + id));
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, LWS_JSON);
            response.write(true, ByteBuffer.wrap(bytes(subscriptionDocument(request, sub))), callback);
        }

        /**
         * The webhook suite's subscription listing: the caller's active subscriptions (all of them
         * for the storage owner) as an LWS container whose members are the subscription URLs.
         */
        private void listSubscriptions(Request request, Response response, Callback callback, String subject,
                                       boolean withBody) {
            boolean owner = authMode != AuthMode.SECURED || (subject != null && subject.equals(storageOwner));
            ObjectNode root = mapper.createObjectNode();
            root.put("@context", LWS_CONTEXT);
            root.put("id", absolute(request, SUBSCRIPTIONS));
            root.put("type", "Container");
            ArrayNode items = root.putArray("items");
            subscriptions.values().stream()
                    .filter(s -> owner || (subject != null && subject.equals(s.subscriber())))
                    .map(Subscription::id).sorted()
                    .forEach(id -> {
                        ObjectNode item = items.addObject();
                        item.put("id", absolute(request, SUBSCRIPTIONS + id));
                        item.put("type", "DataResource");
                        item.put("format", LWS_JSON);
                    });
            root.put("totalItems", items.size());
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, LWS_JSON);
            response.getHeaders().add("Link", "<" + LWS_NS + "Container>; rel=\"type\"");
            send(response, callback, bytes(root), withBody);
        }

        /** The subscription response: type and subscription are REQUIRED; topic and inbox echo the request. */
        private ObjectNode subscriptionDocument(Request request, Subscription sub) {
            ObjectNode doc = mapper.createObjectNode();
            doc.putArray("@context").add(LWS_CONTEXT);
            doc.put("type", WEBHOOK);
            doc.put("subscription", absolute(request, SUBSCRIPTIONS + sub.id()));
            ArrayNode topics = doc.putArray("topic");
            sub.topics().forEach(topics::add);
            doc.put("inbox", sub.inbox());
            if (sub.expires() != null) {
                doc.put("expires", sub.expires());
            }
            return doc;
        }

        private static boolean isUri(JsonNode v) {
            return v.isTextual() && v.asText().matches("^[A-Za-z][A-Za-z0-9+.-]*:.+");
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
                        status(request, response, callback, 404);
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
                        status(request, response, callback, 204);
                    }
                    default -> methodNotAllowed(response, callback, "GET, HEAD, DELETE");
                }
            }

            private void deny(Request request, Response response, Callback callback, String subject) {
                if (subject == null) {
                    challenge(request, response, callback, null);
                } else {
                    status(request, response, callback, 403);
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
                    status(request, response, callback, 400);
                    return;
                }
                // The access data model: type, storage and access are REQUIRED, and an inbox,
                // when given, MUST be a URI. A document that breaks them is refused, since
                // storing it would serve a grant or request that does not conform.
                List<Policy> policies = body == null || !body.isObject() || !hasType(body.path("type"), type)
                        || !body.path("storage").isTextual()
                        || (body.has("inbox") && !isUri(body.path("inbox")))
                        ? null : policies(body.path("access"));
                if (policies == null) {
                    status(request, response, callback, 400);
                    return;
                }
                String id = UUID.randomUUID().toString();
                ObjectNode document = ((ObjectNode) body).deepCopy();
                document.put("id", absolute(request, base + id));
                records.put(id, new Record(id, document, grantsService ? policies : List.of(), subject, newEtag()));
                bump();
                // "When an inbox property is present on an access request or access grant, the server
                // SHOULD deliver notifications to that endpoint" (section 11.6).
                if (grantsService && body.path("inbox").isTextual()) {
                    ObjectNode activity = mapper.createObjectNode();
                    activity.putArray("type").add("Create");
                    ObjectNode object = activity.putObject("object");
                    object.put("id", absolute(request, base + id));
                    object.putArray("type").add("AccessGrant");
                    deliver(request, body.path("inbox").asText(), activity, null);
                }
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
                    // type is REQUIRED and MUST include AccessPolicy; target, when given, MUST
                    // be an object.
                    if (!hasType(p.path("type"), "AccessPolicy")
                            || (p.has("target") && !p.path("target").isObject())) {
                        return null;
                    }
                    List<Constraint> constraints = constraints(p.path("constraint"));
                    if (constraints == null) {
                        return null;
                    }
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
                    out.add(new Policy(Set.copyOf(actions), assignee, Set.copyOf(targets), constraints));
                }
                return out;
            }

            /**
             * The constraint objects of a policy, or null when they are malformed: each MUST have
             * a leftOperand, an operator and a rightOperand, and a leftOperand this profile
             * does not define is refused rather than silently ignored.
             */
            private List<Constraint> constraints(JsonNode constraint) {
                if (constraint.isMissingNode()) {
                    return List.of();
                }
                if (!constraint.isArray()) {
                    return null;
                }
                List<Constraint> out = new ArrayList<>();
                for (JsonNode c : constraint) {
                    String left = c.path("leftOperand").asText("");
                    String op = c.path("operator").asText("");
                    if (!LEFT_OPERANDS.contains(left) || !OPERATORS.contains(op) || !c.has("rightOperand")) {
                        return null;
                    }
                    out.add(new Constraint(left, op, c.get("rightOperand")));
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
