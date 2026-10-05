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
 * {@code as_uri}, and the URLs in the JSON representations the server generates. A request for
 * a URL outside it is one the client built, and the ledger says which handed-out URL it was
 * built from. The recorder also keeps, per URL, what its answers advertised and the role of its
 * latest exchange.
 */
final class Recorder {

    /** Roles whose bodies the server generated, so the URLs in them were handed out by it. */
    private static final Set<String> GENERATED = Set.of("storageDescription", "container", "page", "linkset",
            "typeIndex", "typeSearch", "searchPage", "subscriptions", "subscription", "accessGrants", "accessGrant",
            "accessRequests", "accessRequest", "asMetadata");
    /** The headers that say what a resource supports. */
    private static final List<String> ADVERTISING = List.of("Allow", "Accept-Patch", "Accept-Query", "ETag");
    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;[^,<]*)*)");
    private static final Pattern REL = Pattern.compile("(?i)\\brel\\s*=\\s*\"?([^\";,]+)\"?");
    private static final Pattern AS_URI = Pattern.compile("(?i)\\bas_uri\\s*=\\s*\"([^\"]*)\"");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How a URL was first handed out, and when, in the order of handing out. */
    private record Issue(String how, long order) {
    }

    /** A URL the client built: how it relates to a handed-out one, and which (OBSERVATION.md section 4.3). */
    record Built(String by, String from) {
    }

    private final Predicate<String> inSession;
    private final int capacity;
    private final ArrayDeque<Exchange> log = new ArrayDeque<>();
    private long nextSeq = 1;
    private long dropped;
    private final AtomicLong issues = new AtomicLong();
    private final Map<String, Issue> issued = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> advertised = new ConcurrentHashMap<>();
    private final Map<String, String> roles = new ConcurrentHashMap<>();

    /**
     * @param inSession whether a URL is one of the session's, the only ones the ledger tracks
     * @param capacity exchanges kept; the oldest are dropped beyond it
     */
    Recorder(Predicate<String> inSession, int capacity) {
        this.inSession = inSession;
        this.capacity = capacity;
    }

    /** Records that the session handed out {@code url}, and how; the first way is kept. */
    void issue(String url, String how) {
        String key = withoutFragment(url);
        if (key != null && inSession.test(key)) {
            issued.computeIfAbsent(key, k -> new Issue(how, issues.incrementAndGet()));
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
            roles.put(key, role);
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
        for (String challenge : values(headers, "WWW-Authenticate")) {
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
        if (body != null && GENERATED.contains(role) && status / 100 == 2) {
            try {
                collect(JSON.readTree(body), "body:" + role);
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
            if (!a.isEmpty() && key != null) {
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
        while (log.size() > capacity) {
            log.removeFirst();
            dropped++;
        }
        return e;
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
