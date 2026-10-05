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

/**
 * One client developer's session (CLIENT-TESTING.md section 4.2): a storage and its
 * authorization server of their own, two identities, the log of everything their client sent, and
 * the judge of the client rules.
 * Its id is public and appears in every URL it serves; its key is secret, unlocks the session's
 * page and API, and is kept only as a hash.
 */
final class Session {

    static final List<String> IDENTITIES = List.of("alice", "bob");

    final String id;
    /** The session's URLs start with this: the service's public base, then /s/ and the id. */
    final String base;
    final Instant created;
    final Traps traps;
    final RefAuthorizationServer as;
    final RefLwsServer storage;
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

    Session(String id, String key, ClientLabConfig config, ClientRules rules, Instant now) {
        this.id = id;
        this.base = config.publicBase() + "/s/" + id;
        this.created = now;
        this.lastActive = now;
        this.keyHash = sha256(key);
        this.traps = Traps.all(config.indexLag());
        this.as = RefAuthorizationServer.mounted(URI.create(base + "/as"));
        this.storage = RefLwsServer.mounted(URI.create(storageUrl()), as, webid("alice"), traps);
        // Notifications go nowhere until the outbound guard of section 8.3 exists (phase C5).
        this.storage.deliverOnlyTo(uri -> false);
        // Some linksets take PUT and some do not, so a client must read Allow first.
        this.storage.linksetPutOnDataResources(true);
        String metadata = as.metadataUri().toString();
        this.recorder = new Recorder(url -> url.startsWith(base + "/") || url.equals(metadata), config.maxExchanges());
        this.bucket = new TokenBucket(config.requestBurst(), config.requestsPerSecond());
        this.clientId = base + "/client";
        this.judge = new Judge(rules, Set.of());
        recorder.issue(storageUrl(), "session");
        IDENTITIES.forEach(name -> recorder.issue(webid(name), "session"));
    }

    String storageUrl() {
        return base + "/storage/";
    }

    String webid(String name) {
        return base + "/id/" + name;
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
        RefLwsServer.Fault fault = RefLwsServer.Fault.of(term);
        if (fault == null) {
            return false;
        }
        storage.arm(fault);
        return true;
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
