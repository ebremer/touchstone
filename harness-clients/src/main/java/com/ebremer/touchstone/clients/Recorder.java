package com.ebremer.touchstone.clients;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A session's traffic log and the URL ledger (CLIENT-TESTING.md section 4.3). The ledger holds
 * every URL of the session that the session handed out: the storage URL on the session page, and
 * from then on whatever its answers name, in {@code Location}, {@code Content-Location} and
 * {@code Link} headers, a challenge's {@code as_uri}, and the URLs in the JSON representations
 * the server generates. A request for a URL outside it is one the client built. The recorder also
 * keeps, per URL, what its last answer advertised.
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

    private final Predicate<String> inSession;
    private final int capacity;
    private final ArrayDeque<Exchange> log = new ArrayDeque<>();
    private long nextSeq = 1;
    private long dropped;
    private final Map<String, String> issued = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> advertised = new ConcurrentHashMap<>();

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
            issued.putIfAbsent(key, how);
        }
    }

    /** How {@code url} was handed out, or null when it never was. */
    String issuedVia(String url) {
        String key = withoutFragment(url);
        return key == null ? null : issued.get(key);
    }

    /** What {@code url}'s last answer advertised, before the exchange now being recorded. */
    Map<String, String> advertisedFor(String url) {
        Map<String, String> a = advertised.get(withoutFragment(url));
        return a == null ? Map.of() : Map.copyOf(a);
    }

    /**
     * Learns from an answer to a request for {@code url}: the URLs it hands out, and what the
     * resource advertises. Called after the exchange's own annotations are taken.
     */
    void learn(String url, String role, int status, Map<String, List<String>> headers, String body) {
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
        if (status / 100 == 2 || status == 304 || status == 405) {
            Map<String, String> a = new LinkedHashMap<>();
            for (String name : ADVERTISING) {
                List<String> v = values(headers, name);
                if (!v.isEmpty()) {
                    a.put(name, String.join(", ", v));
                }
            }
            if (!a.isEmpty()) {
                advertised.merge(withoutFragment(url), a, (old, now) -> {
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

    /** Appends an exchange, numbering it, and drops the oldest beyond the capacity. */
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

    private static String withoutFragment(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }
}
