package com.ebremer.touchstone.clients;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.fixtures.as.RefAuthorizationServer;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.ebremer.touchstone.fixtures.lws.Traps;
import com.ebremer.touchstone.fixtures.op.RefOpenIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

/**
 * One client developer's session (CLIENT-TESTING.md section 4.2): a storage, its authorization
 * server and an OpenID Provider of their own, two identities with their identity documents, the
 * log of everything their client sent, and the judge of the client rules.
 * Its id is public and appears in every URL it serves; its key is secret, unlocks the session's
 * page and API, and is kept only as a hash.
 */
final class Session {

    static final List<String> IDENTITIES = List.of("alice", "bob");
    /** The areas a rule belongs to, any of which a developer may declare out of scope (CLIENT-TESTING.md section 5.1). */
    static final List<String> AREAS = List.of("core", "authentication", "notifications", "index");
    /** The service type naming an agent's OpenID Provider in its identity document (lws10-authn-openid section 5). */
    static final String OPENID_PROVIDER = "https://www.w3.org/ns/lws#OpenIdProvider";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /**
     * An identity's secrets, which the session API hands to the key holder: the password it signs
     * in to the session's OpenID Provider with, and the private key of the verification method its
     * identity document lists, for self-issued credentials (the CID suite).
     */
    record Identity(String name, String webid, String password, ECKey key, String document) {
    }

    /**
     * The client under test as its developer names it (CLIENT-TESTING.md section 3), the subject
     * of the session's EARL report. Any part may be null.
     *
     * @param homepage an absolute http(s) URL
     */
    record ClientUnderTest(String name, String version, String homepage) {
        static final ClientUnderTest UNNAMED = new ClientUnderTest(null, null, null);
    }

    final String id;
    /** The session's URLs start with this: the service's public base, then /s/ and the id. */
    final String base;
    final Instant created;
    final Traps traps;
    final RefAuthorizationServer as;
    final RefOpenIdProvider op;
    final RefLwsServer storage;
    final java.util.Map<String, Identity> identities = new java.util.LinkedHashMap<>();
    final Recorder recorder;
    final TokenBucket bucket;
    final Judge judge;
    /**
     * Every access token the session handed out, through its API or its token endpoint. Kept in
     * memory only, so the recorder can tell a token wherever a client puts it (OBSERVATION.md
     * section 4.4).
     */
    final Set<String> tokens = ConcurrentHashMap.newKeySet();
    /** The client_id of the tokens the session hands out itself. */
    final String clientId;
    private final byte[] keyHash;
    private volatile Instant lastActive;
    /** The client under test, as its developer last named it. */
    volatile ClientUnderTest clientUnderTest = ClientUnderTest.UNNAMED;
    /** When the results began: the session's start, or the latest reset. */
    private volatile Instant resultsSince;
    /** Notifications sent to inboxes, and those still awaiting an answer. */
    private final java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();

    /** The proxy of a proxy session (CLIENT-TESTING.md section 10), or null for a session with its own storage. */
    final ProxySession proxy;

    Session(String id, String key, ClientLabConfig config, ClientRules rules, Instant now) {
        this(id, key, config, rules, now, null);
    }

    /** @param proxyTarget the real server a proxy session fronts, or null for a session with its own storage */
    Session(String id, String key, ClientLabConfig config, ClientRules rules, Instant now,
            ProxyTargets.ProxyTarget proxyTarget) {
        this.id = id;
        this.base = config.publicBase() + "/s/" + id;
        this.created = now;
        this.lastActive = now;
        this.resultsSince = now;
        this.keyHash = sha256(key);
        this.traps = Traps.all(config.indexLag());
        this.as = RefAuthorizationServer.mounted(URI.create(base + "/as"));
        this.op = RefOpenIdProvider.mounted(URI.create(base + "/op"));
        op.alsoAudience(as.issuer());
        if (proxyTarget != null && proxyTarget.issuer() != null) {
            // The server behind the proxy can take an ID Token from the session's provider.
            op.alsoAudience(proxyTarget.issuer());
        }
        for (String name : IDENTITIES) {
            Identity identity = identity(name);
            identities.put(name, identity);
            op.addUser(name, identity.webid(), identity.password());
        }
        // The session's authorization server trusts the session's identities and provider only, so
        // that no subject token can make the service fetch a URL of a client's choosing (section 8.3).
        as.dereferenceOnly(this::document);
        this.storage = RefLwsServer.mounted(URI.create(storageUrl()), as, webid("alice"), traps);
        // Notifications go nowhere until the outbound guard of section 8.3 exists (phase C5).
        this.storage.deliverOnlyTo(uri -> false);
        // Some linksets take PUT and some do not, so a client must read Allow first.
        this.storage.linksetPutOnDataResources(true);
        String metadata = as.metadataUri().toString();
        String wellKnown = proxyTarget == null ? null
                : "/.well-known/lws-configuration" + config.basePath() + "/p/" + proxyTarget.id();
        String proxied = proxyTarget == null ? null : proxyTarget.prefix();
        String proxiedMetadata = wellKnown == null ? null : config.origin() + wellKnown;
        this.recorder = new Recorder(url -> url.startsWith(base + "/") || url.equals(metadata)
                || (proxied != null && (url.startsWith(proxied) || url.startsWith(proxiedMetadata))), config.maxExchanges());
        this.proxy = proxyTarget == null ? null : new ProxySession(proxyTarget, wellKnown, recorder);
        this.bucket = new TokenBucket(config.requestBurst(), config.requestsPerSecond());
        this.clientId = base + "/client";
        this.judge = new Judge(rules, Set.of(), proxy == null ? Set.of() : ProxySession.unavailable(rules));
        recorder.issue(publicStorage(), "session");
        IDENTITIES.forEach(name -> recorder.issue(webid(name), "session"));
    }

    /** The URL of the session's own storage, which a proxy session does not serve. */
    String storageUrl() {
        return base + "/storage/";
    }

    /** The storage a client uses: the session's own, or the real one a proxy session fronts. */
    String publicStorage() {
        return proxy == null ? storageUrl() : proxy.target.storage().toString();
    }

    String webid(String name) {
        return base + "/id/" + name;
    }

    /** The verification method of {@code name}'s key: the identity document's URL and a fragment. */
    String keyId(String name) {
        return webid(name) + "#key-1";
    }

    /**
     * A new identity: a password, a P-256 key, and its controlled identifier document, which names
     * the key for authentication (lws10-authn-ssi-cid section 5) and the session's OpenID Provider
     * as its issuer of ID Tokens (lws10-authn-openid section 5).
     */
    private Identity identity(String name) {
        ECKey key;
        try {
            key = new ECKeyGenerator(Curve.P_256).keyID(keyId(name)).algorithm(com.nimbusds.jose.JWSAlgorithm.ES256)
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate a key for " + name, e);
        }
        ObjectNode doc = JSON.createObjectNode();
        doc.putArray("@context").add("https://www.w3.org/ns/cid/v1");
        doc.put("id", webid(name));
        ObjectNode method = doc.putArray("authentication").addObject();
        method.put("id", keyId(name));
        method.put("type", "JsonWebKey");
        method.put("controller", webid(name));
        method.set("publicKeyJwk", JSON.valueToTree(key.toPublicJWK().toJSONObject()));
        ObjectNode service = doc.putArray("service").addObject();
        service.put("type", OPENID_PROVIDER);
        service.put("serviceEndpoint", op.issuer());
        byte[] password = new byte[12];
        RANDOM.nextBytes(password);
        return new Identity(name, webid(name), java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(password),
                key, doc.toString());
    }

    /**
     * The document at {@code url} that the session's authorization server may dereference: an
     * identity document, or the OpenID Provider's discovery document or JWKS. Null for any other.
     */
    String document(String url) {
        for (Identity identity : identities.values()) {
            if (identity.webid().equals(url)) {
                return identity.document();
            }
        }
        if (url.equals(op.discoveryUri())) {
            return op.discovery();
        }
        return url.equals(op.jwksUri()) ? op.jwks() : null;
    }

    /** alice or bob for the session's identities; any other subject as it is. */
    String identityOf(String subject) {
        for (String name : IDENTITIES) {
            if (webid(name).equals(subject)) {
                return name;
            }
        }
        return subject;
    }

    /** An access token for {@code name}, as the session's authorization server would issue it. */
    String token(String name, Duration lifetime) {
        String token = as.issue(webid(name), clientId, storage.realm(), lifetime);
        tokens.add(token);
        return token;
    }

    /** Starts the results over (CLIENT-TESTING.md section 3): the storage and the log stay. */
    void resetResults(Instant now) {
        judge.reset();
        resultsSince = now;
    }

    Instant resultsSince() {
        return resultsSince;
    }

    /** The areas in scope, in the order of {@link #AREAS}. */
    List<String> areas() {
        Set<String> out = judge.outOfScope();
        return AREAS.stream().filter(a -> !out.contains(a)).toList();
    }

    /**
     * Starts the task of rule {@code name}: opens its trigger and arms its fault (OBSERVATION.md
     * sections 6.1 and 6.2). Returns false when the rule has no task.
     */
    boolean startTask(String name) {
        if (!judge.startTask(name)) {
            return false;
        }
        judge.taskOf(name).ifPresent(t -> {
            if (t.arm() != null) {
                armFault(t.arm());
            }
        });
        return true;
    }

    /** Arms the fault named {@code term}; returns false when there is none by that name. */
    boolean armFault(String term) {
        if (proxy != null) {
            return proxy.arm(term);
        }
        RefLwsServer.Fault fault = RefLwsServer.Fault.of(term);
        if (fault == null) {
            return false;
        }
        storage.arm(fault);
        return true;
    }

    /**
     * Sends the storage's notifications through {@code courier}, which guards and records them
     * (CLIENT-TESTING.md section 8.3). Until then nothing is delivered.
     */
    void deliverWith(RefLwsServer.Courier courier) {
        storage.deliverWith(courier);
        storage.deliverOnlyTo(uri -> true);
    }

    /** Takes one delivery from the session's allowance; false when it is used up. */
    boolean takeDelivery(int max) {
        return deliveries.incrementAndGet() <= max;
    }

    /** Notifications sent, or refused for the allowance, so far. */
    int deliveries() {
        return deliveries.get();
    }

    /** Notifications awaiting their inbox's answer. */
    java.util.concurrent.atomic.AtomicInteger inFlight() {
        return inFlight;
    }

    boolean keyMatches(String presented) {
        return presented != null && MessageDigest.isEqual(sha256(presented), keyHash);
    }

    void touch(Instant now) {
        lastActive = now;
    }

    Instant lastActive() {
        return lastActive;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
