package com.ebremer.touchstone.clients;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ebremer.touchstone.core.definitions.ClientRules;

/**
 * The live sessions, and the bounds on them (CLIENT-TESTING.md section 8.2): a global cap, a cap
 * per address per hour, and expiry when idle or old. Everything is in memory; an ended session
 * leaves nothing behind.
 */
final class SessionManager {

    /** A new session and its key, which is shown once and never stored. */
    record Created(Session session, String key) {
    }

    /** A session that could not be started, with the HTTP status that says why. */
    static final class Refused extends Exception {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final ClientLabConfig config;
    private final ClientRules rules;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> starts = new HashMap<>();

    SessionManager(ClientLabConfig config, ClientRules rules, Clock clock) {
        this.config = config;
        this.rules = rules;
        this.clock = clock;
    }

    Created create(String address) throws Refused {
        Instant now = clock.instant();
        synchronized (starts) {
            Deque<Instant> recent = starts.computeIfAbsent(address, a -> new ArrayDeque<>());
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
                recent.removeFirst();
            }
            if (recent.size() >= config.sessionsPerAddressPerHour()) {
                throw new Refused(429, "too many sessions started from this address in the last hour");
            }
            if (sessions.size() >= config.maxSessions()) {
                throw new Refused(503, "the service is at its limit of live sessions; try again later");
            }
            recent.addLast(now);
            String id = randomToken(12);
            String key = randomToken(32);
            Session session = new Session(id, key, config, rules, now);
            sessions.put(id, session);
            return new Created(session, key);
        }
    }

    /** The live session {@code id}, or null; a session found expired is ended on the way. */
    Session get(String id) {
        Session s = id == null ? null : sessions.get(id);
        if (s != null && expired(s, clock.instant())) {
            end(id);
            return null;
        }
        return s;
    }

    void end(String id) {
        Session s = sessions.remove(id);
        if (s != null) {
            s.storage.close();
            s.as.close();
        }
    }

    /** Ends every expired session; returns how many. */
    int sweep() {
        Instant now = clock.instant();
        int ended = 0;
        for (Session s : sessions.values()) {
            if (expired(s, now)) {
                end(s.id);
                ended++;
            }
        }
        synchronized (starts) {
            starts.values().removeIf(d -> d.isEmpty() || d.peekLast().isBefore(now.minus(Duration.ofHours(1))));
        }
        return ended;
    }

    int size() {
        return sessions.size();
    }

    Instant now() {
        return clock.instant();
    }

    /** When {@code s} ends if nothing more happens: idle timeout or maximum lifetime, whichever is first. */
    Instant expiry(Session s) {
        Instant idle = s.lastActive().plus(config.idleTimeout());
        Instant old = s.created.plus(config.maxLifetime());
        return idle.isBefore(old) ? idle : old;
    }

    private boolean expired(Session s, Instant now) {
        return !now.isBefore(expiry(s));
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
