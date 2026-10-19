package com.ebremer.touchstone.clients;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A session's traffic log and the URL ledger (CLIENT-TESTING.md section 4.3, OBSERVATION.md
 * section 4.3). The ledger holds every URL of the session that the session handed out: the
 * storage URL on the session page, and from then on whatever its answers name, in
 * {@code Location}, {@code Content-Location} and {@code Link} headers, a challenge's
 * {@code resource_metadata} (or, from a server behind a proxy that still sends it, its
 * {@code as_uri}), and the URLs in the JSON representations the server generates. A request for
 * a URL outside it is one the client built, and the ledger says which handed-out URL it was
 * built from. The recorder also keeps, per URL, what its answers advertised and the role of its
 * latest exchange.
 */
final class Recorder {

    /** Roles whose bodies the server generated, so the URLs in them were handed out by it. */
    private static final Set<String> GENERATED = Set.of("storageDescription", "container", "page", "linkset",
            "typeIndex", "typeSearch", "searchPage", "subscriptions", "subscription", "accessGrants", "accessGrant",
            "accessRequests", "accessRequest", "asMetadata", "identityDocument", "opDiscovery",
            "protectedResourceMetadata", "decoyStorage");
    /** The headers that say what a resource supports. */
    private static final List<String> ADVERTISING = List.of("Allow", "Accept-Patch", "Accept-Query", "ETag");
    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;[^,<]*)*)");
    private static final Pattern REL = Pattern.compile("(?i)\\brel\\s*=\\s*\"?([^\";,]+)\"?");
    private static final Pattern AS_URI = Pattern.compile("(?i)\\bas_uri\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern RESOURCE_METADATA =
            Pattern.compile("(?i)\\bresource_metadata\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");
    private static final String STORAGE_REL = "https://www.w3.org/ns/lws#storage";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The characters of body text the log allows for each exchange it may keep, on average. */
    static final int CHARS_PER_EXCHANGE = 8 << 10;
    /**
     * The URLs the ledger remembers anything about, in each of its maps. A session's own storage
     * hands out far fewer; beyond it, a URL first seen is not remembered, so that a client
     * requesting endless made-up URLs cannot grow the session without bound.
     */
    static final int MAX_URLS = 10_000;

    /** How a URL was first handed out, and when, in the order of handing out. */
    private record Issue(String how, long order) {
    }

    /** A URL the client built: how it relates to a handed-out one, and which (OBSERVATION.md section 4.3). */
    record Built(String by, String from) {
    }

    private final Predicate<String> inSession;
    private final int capacity;
    private final ArrayDeque<Exchange> log = new ArrayDeque<>();
    /** The most the log holds, in characters (size(Exchange)): {@link #CHARS_PER_EXCHANGE} for each exchange it may keep. */
    private final long maxSize;
    private long size;
    private long nextSeq = 1;
    private long dropped;
    private final AtomicLong issues = new AtomicLong();
    private final Map<String, Issue> issued = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> advertised = new ConcurrentHashMap<>();
    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Map<String, String> lastRequests = new ConcurrentHashMap<>();
    /**
     * Each storage a 401's Link named, and whether the discovery checks of lws10-core section 5.2.3
     * passed for the latest request it was named for (OBSERVATION.md section 4.10).
     */
    private final Map<String, Boolean> storages = new ConcurrentHashMap<>();
    /**
     * Whether the storage description of a storage names a StorageRoot with the scheme and
     * authority of a URL: true or false for the session's own storages, null for any other.
     */
    private volatile java.util.function.BiFunction<String, String, Boolean> storageRoots = (storage, url) -> null;

    /**
     * @param inSession whether a URL is one of the session's, the only ones the ledger tracks
     * @param capacity exchanges kept; the oldest are dropped beyond it
     */
    Recorder(Predicate<String> inSession, int capacity) {
        this.inSession = inSession;
        this.capacity = capacity;
        this.maxSize = (long) capacity * CHARS_PER_EXCHANGE;
    }

    /** Records that the session handed out {@code url}, and how; the first way is kept. */
    void issue(String url, String how) {
        String key = withoutFragment(url);
        if (key != null && inSession.test(key)) {
            if (room(issued, key)) {
                issued.computeIfAbsent(key, k -> new Issue(how, issues.incrementAndGet()));
            }
        }
    }

    /** How {@code url} was handed out, or null when it never was. */
    String issuedVia(String url) {
        String key = withoutFragment(url);
        Issue i = key == null ? null : issued.get(key);
        return i == null ? null : i.how();
    }

    /**
     * For a URL never handed out, the handed-out URL it was built from, with the same scheme and
     * authority: by {@code query}, the same path with another query; failing that by {@code path},
     * a path it extends, or a sibling's. The longest path wins, then the URL handed out first.
     * Null when none qualifies.
     */
    Built builtFrom(String url) {
        URI u = parse(withoutFragment(url));
        if (u == null) {
            return null;
        }
        String origin = origin(u);
        String path = Objects.requireNonNullElse(u.getRawPath(), "");
        String query = u.getRawQuery();
        String byQuery = null;
        long queryOrder = Long.MAX_VALUE;
        String byPath = null;
        int pathLength = -1;
        long pathOrder = Long.MAX_VALUE;
        for (Map.Entry<String, Issue> e : issued.entrySet()) {
            URI k = parse(e.getKey());
            if (k == null || !origin.equals(origin(k))) {
                continue;
            }
            String kp = Objects.requireNonNullElse(k.getRawPath(), "");
            long order = e.getValue().order();
            if (kp.equals(path)) {
                if (!Objects.equals(k.getRawQuery(), query) && order < queryOrder) {
                    byQuery = e.getKey();
                    queryOrder = order;
                }
                continue;
            }
            boolean extended = kp.endsWith("/") && path.startsWith(kp);
            boolean sibling = parent(kp).equals(parent(path));
            if ((extended || sibling) && (kp.length() > pathLength || (kp.length() == pathLength && order < pathOrder))) {
                byPath = e.getKey();
                pathLength = kp.length();
                pathOrder = order;
            }
        }
        return byQuery != null ? new Built("query", byQuery) : byPath != null ? new Built("path", byPath) : null;
    }

    /**
     * Records {@code signature} (method, Content-Type and body digest) as the latest request to
     * {@code url}, and says whether the previous request to it had the same (OBSERVATION.md
     * section 4.6).
     */
    boolean repeats(String url, String signature) {
        String key = withoutFragment(url);
        return key != null && room(lastRequests, key) && signature.equals(lastRequests.put(key, signature));
    }

    /** Tells the recorder where the storage roots of the session's storages are (OBSERVATION.md section 4.10). */
    void storageRoots(java.util.function.BiFunction<String, String, Boolean> roots) {
        this.storageRoots = roots;
    }

    /**
     * Whether the discovery checks passed for the latest request a 401 named {@code storage} for
     * (OBSERVATION.md section 4.10); null when no 401 named it.
     */
    Boolean discoveryValid(String storage) {
        return storages.get(storage);
    }

    /**
     * The discovery checks of lws10-core section 5.2.3 for a 401 answering {@code url}: its
     * resource_metadata has the URL's scheme and authority, and the storage it links to has its
     * storage root there too. The third check, that the metadata's resource is the URL, holds
     * for every 401 the session's own storage gives, so it is not looked at. Null when the
     * storage is not the session's, whose description the recorder cannot vouch for.
     */
    private Boolean discoveryChecks(String url, String resourceMetadata, String storage) {
        Boolean root = storageRoots.apply(storage, url);
        if (root == null) {
            return null;
        }
        return resourceMetadata != null && sameOrigin(URI.create(url).resolve(resourceMetadata), parse(url)) && root;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a != null && b != null && a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getRawAuthority() != null && a.getRawAuthority().equalsIgnoreCase(b.getRawAuthority());
    }

    /** What {@code url}'s answers advertised, before the exchange now being recorded. */
    Map<String, String> advertisedFor(String url) {
        Map<String, String> a = advertised.get(withoutFragment(url));
        return a == null ? Map.of() : Map.copyOf(a);
    }

    /** The role of the latest exchange for {@code url}, or null when the client never requested it. */
    String roleOf(String url) {
        String key = withoutFragment(url);
        return key == null ? null : roles.get(key);
    }

    /**
     * Learns from an answer to a request for {@code url}: its role, the URLs it hands out, and
     * what the resource advertises. Called after the exchange's own annotations are taken.
     */
    void learn(String url, String role, int status, Map<String, List<String>> headers, String body) {
        String key = withoutFragment(url);
        if (key != null && !role.equals("preflight") && !role.equals("limited")) {
            if (room(roles, key)) {
                roles.put(key, role);
            }
        }
        URI base = URI.create(url);
        for (String location : values(headers, "Location")) {
            issue(resolve(base, location), "location");
        }
        for (String location : values(headers, "Content-Location")) {
            issue(resolve(base, location), "content-location");
        }
        for (String link : values(headers, "Link")) {
            Matcher m = LINK.matcher(link);
            while (m.find()) {
                Matcher rel = REL.matcher(m.group(2));
                String rels = rel.find() ? rel.group(1).trim().toLowerCase(Locale.ROOT) : "related";
                for (String r : rels.split("\\s+")) {
                    issue(resolve(base, m.group(1)), "link:" + r);
                }
            }
        }
        String resourceMetadata = null;
        for (String challenge : values(headers, "WWW-Authenticate")) {
            Matcher r = RESOURCE_METADATA.matcher(challenge);
            if (r.find()) {
                resourceMetadata = r.group(1) != null ? r.group(1) : r.group(2);
                issue(resolve(base, resourceMetadata), "challenge:resource_metadata");
            }
            Matcher m = AS_URI.matcher(challenge);
            while (m.find()) {
                String issuer = resolve(base, m.group(1));
                if (issuer != null) {
                    issue(issuer, "challenge:as_uri");
                    // RFC 8414 section 3.1 derives the metadata URL from the issuer, so a client
                    // that fetches it is following the issuer, not building a URL.
                    URI i = URI.create(issuer);
                    String path = i.getRawPath() == null ? "" : i.getRawPath();
                    issue(i.resolve("/.well-known/lws-configuration" + path).toString(), "challenge:as_uri");
                }
            }
        }
        if (status == 401 && key != null) {
            for (String link : values(headers, "Link")) {
                Matcher m = LINK.matcher(link);
                while (m.find()) {
                    Matcher rel = REL.matcher(m.group(2));
                    if (rel.find() && List.of(rel.group(1).trim().split("\\s+")).contains(STORAGE_REL)) {
                        String storage = resolve(base, m.group(1));
                        Boolean checked = storage == null ? null : discoveryChecks(url, resourceMetadata, storage);
                        if (checked != null && room(storages, storage)) {
                            storages.put(storage, checked);
                        }
                    }
                }
            }
        }
        if (body != null && GENERATED.contains(role) && status / 100 == 2) {
            try {
                JsonNode doc = JSON.readTree(body);
                collect(doc, "body:" + role);
                if (role.equals("protectedResourceMetadata")) {
                    // RFC 8414 section 3.1 derives each authorization server's metadata URL from
                    // its issuer, so a client that fetches it follows the issuer, not a URL it built.
                    for (JsonNode issuer : doc.path("authorization_servers")) {
                        URI i = parse(issuer.asText(null));
                        if (i != null && i.getScheme() != null && i.getRawAuthority() != null) {
                            String path = i.getRawPath() == null ? "" : i.getRawPath().replaceAll("/$", "");
                            issue(i.resolve("/.well-known/lws-configuration" + path).toString(), "body:" + role);
                        }
                    }
                }
                if (role.equals("identityDocument")) {
                    // OpenID Connect Discovery section 4 derives the configuration's URL from the
                    // provider's issuer, as RFC 8414 does the metadata's.
                    for (JsonNode service : doc.path("service")) {
                        if (service.path("serviceEndpoint").isTextual()
                                && service.path("type").toString().contains("OpenIdProvider")) {
                            issue(service.path("serviceEndpoint").asText().replaceAll("/$", "")
                                    + "/.well-known/openid-configuration", "body:" + role);
                        }
                    }
                }
            } catch (Exception e) {
                // not JSON after all: nothing handed out
            }
        }
        // A 405 or 415 says what is supported instead (RFC 9110 section 15.5.6, RFC 5789
        // section 2.2, RFC 10008), so it advertises as much as a success does.
        if (status / 100 == 2 || status == 304 || status == 405 || status == 415) {
            Map<String, String> a = new LinkedHashMap<>();
            for (String name : ADVERTISING) {
                List<String> v = values(headers, name);
                if (!v.isEmpty()) {
                    a.put(name, String.join(", ", v));
                }
            }
            if (!a.isEmpty() && key != null && room(advertised, key)) {
                advertised.merge(key, a, (old, now) -> {
                    Map<String, String> merged = new LinkedHashMap<>(old);
                    merged.putAll(now);
                    return merged;
                });
            }
        }
    }

    private void collect(JsonNode node, String how) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            String v = node.asText();
            if (v.startsWith("http://") || v.startsWith("https://")) {
                issue(v, how);
            }
        } else if (node.isContainerNode()) {
            for (JsonNode child : node) {
                collect(child, how);
            }
        }
    }

    /**
     * Appends an exchange, numbering it, and drops the oldest beyond the capacity. {@code build}
     * runs under the log's lock, so exchanges are judged one at a time, in the order they are
     * numbered.
     */
    synchronized Exchange append(java.util.function.LongFunction<Exchange> build) {
        Exchange e = build.apply(nextSeq++);
        log.addLast(e);
        size += size(e);
        while (log.size() > capacity || (size > maxSize && log.size() > 1)) {
            size -= size(log.removeFirst());
            dropped++;
        }
        return e;
    }

    /** About how much of the log an exchange takes, in characters: its bodies' text, and a kilobyte for the rest. */
    private static long size(Exchange e) {
        return 1024 + length(e.requestBody()) + length(e.responseBody());
    }

    private static long length(Exchange.Body body) {
        return body == null || body.text() == null ? 0 : body.text().length();
    }

    /** Up to {@code limit} exchanges numbered after {@code seq}, oldest first. */
    synchronized List<Exchange> after(long seq, int limit) {
        List<Exchange> out = new ArrayList<>();
        Iterator<Exchange> it = log.iterator();
        while (it.hasNext() && out.size() < limit) {
            Exchange e = it.next();
            if (e.seq() > seq) {
                out.add(e);
            }
        }
        return out;
    }

    synchronized long dropped() {
        return dropped;
    }

    synchronized long recorded() {
        return nextSeq - 1;
    }

    /** Whether {@code map} may hold {@code key}: it does already, or has room for one more (MAX_URLS). */
    private static boolean room(Map<String, ?> map, String key) {
        return map.containsKey(key) || map.size() < MAX_URLS;
    }

    private static List<String> values(Map<String, List<String>> headers, String name) {
        List<String> out = new ArrayList<>();
        headers.forEach((k, v) -> {
            if (k.equalsIgnoreCase(name)) {
                out.addAll(v);
            }
        });
        return out;
    }

    private static String resolve(URI base, String ref) {
        try {
            return base.resolve(ref.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static URI parse(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static String origin(URI u) {
        return String.valueOf(u.getScheme()).toLowerCase(Locale.ROOT) + "://" + u.getRawAuthority();
    }

    /** A path up to and including its last slash. */
    private static String parent(String path) {
        return path.substring(0, path.lastIndexOf('/') + 1);
    }

    static String withoutFragment(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }
}
