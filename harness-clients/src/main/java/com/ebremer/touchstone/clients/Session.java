package com.ebremer.touchstone.clients;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.ebremer.touchstone.fixtures.as.RefAuthorizationServer;
import com.ebremer.touchstone.fixtures.lws.RefLwsServer;
import com.ebremer.touchstone.fixtures.lws.Traps;

/**
 * One client developer's session (CLIENT-TESTING.md section 4.2): a storage and its
 * authorization server of their own, two identities, and the log of everything their client sent.
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
    /** The client_id of the tokens the session hands out itself. */
    final String clientId;
    private final byte[] keyHash;
    private volatile Instant lastActive;

    Session(String id, String key, ClientLabConfig config, Instant now) {
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
        String metadata = as.metadataUri().toString();
        this.recorder = new Recorder(url -> url.startsWith(base + "/") || url.equals(metadata), config.maxExchanges());
        this.bucket = new TokenBucket(config.requestBurst(), config.requestsPerSecond());
        this.clientId = base + "/client";
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
        return as.issue(webid(name), clientId, storage.realm(), lifetime);
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
