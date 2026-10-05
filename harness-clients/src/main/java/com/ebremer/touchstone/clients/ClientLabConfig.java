package com.ebremer.touchstone.clients;

import java.net.URI;
import java.time.Duration;

/**
 * How a client-session service runs, and the bounds it keeps (CLIENT-TESTING.md section 8.2).
 *
 * @param publicBase the service's public URL, with a path and no trailing slash, such as
 *     {@code https://example.org/touchstone/clients}; requests arrive with that path (a reverse
 *     proxy keeps it), and every URL a session hands out starts with this
 * @param bindHost the interface to listen on, normally the loopback one behind a reverse proxy
 * @param port the port to listen on; 0 picks a free one
 * @param trustForwardedFor whether X-Forwarded-For names the client's address, as it does behind
 *     a reverse proxy that sets it; otherwise the connection's address is used
 * @param maxSessions live sessions at once
 * @param sessionsPerAddressPerHour sessions one address may start in an hour
 * @param idleTimeout a session with no request for this long ends
 * @param maxLifetime a session ends this long after it started, busy or not
 * @param maxBodyBytes the largest request body a session accepts
 * @param maxRecordedResponseBytes how much of a response body the traffic log keeps
 * @param maxExchanges exchanges the traffic log keeps; older ones are dropped, and counted
 * @param maxResources resources one session's storage may hold
 * @param maxStorageBytes bytes one session's storage may hold
 * @param requestBurst requests a session may send at once
 * @param requestsPerSecond the sustained rate after a burst
 * @param tokenLifetime how long an access token handed out by the session lives
 * @param indexLag how long a write takes to reach the type index and search (Traps#indexLag)
 * @param maxDeliveries notifications one session may send to inboxes
 * @param allowPrivateInboxes whether notifications may go to http URLs and private addresses: for
 *     local development and the self-test only, never on a public service (section 8.3)
 */
public record ClientLabConfig(URI publicBase, String bindHost, int port, boolean trustForwardedFor,
                              int maxSessions, int sessionsPerAddressPerHour, Duration idleTimeout,
                              Duration maxLifetime, int maxBodyBytes, int maxRecordedResponseBytes,
                              int maxExchanges, int maxResources, long maxStorageBytes, int requestBurst,
                              double requestsPerSecond, Duration tokenLifetime, Duration indexLag,
                              int maxDeliveries, boolean allowPrivateInboxes) {

    public ClientLabConfig {
        String base = publicBase.toString();
        if (base.endsWith("/") || publicBase.getRawPath() == null || publicBase.getRawPath().isEmpty()
                || publicBase.getRawQuery() != null || publicBase.getRawFragment() != null) {
            throw new IllegalArgumentException("the public base is an http(s) URL with a path and no trailing slash: "
                    + publicBase);
        }
    }

    /** The bounds CLIENT-TESTING.md section 8.2 gives, for a service at {@code publicBase}. */
    public static ClientLabConfig defaults(URI publicBase, String bindHost, int port) {
        return new ClientLabConfig(publicBase, bindHost, port, false,
                100, 10, Duration.ofHours(2), Duration.ofHours(24),
                1 << 20, 64 << 10, 5000, 500, 16L << 20, 200, 20,
                Duration.ofHours(1), Duration.ofSeconds(3), 500, false);
    }

    /** The path every request to the service starts with: the public base's path. */
    public String basePath() {
        return publicBase.getRawPath();
    }

    /** The scheme, host and port of the public base. */
    public String origin() {
        return publicBase.getScheme() + "://" + publicBase.getRawAuthority();
    }

    public ClientLabConfig withTrustForwardedFor(boolean trust) {
        return new ClientLabConfig(publicBase, bindHost, port, trust, maxSessions, sessionsPerAddressPerHour,
                idleTimeout, maxLifetime, maxBodyBytes, maxRecordedResponseBytes, maxExchanges, maxResources,
                maxStorageBytes, requestBurst, requestsPerSecond, tokenLifetime, indexLag, maxDeliveries,
                allowPrivateInboxes);
    }

    /** The same, with private inboxes allowed or not: allowed only for local development and the self-test. */
    public ClientLabConfig withPrivateInboxes(boolean allow) {
        return new ClientLabConfig(publicBase, bindHost, port, trustForwardedFor, maxSessions, sessionsPerAddressPerHour,
                idleTimeout, maxLifetime, maxBodyBytes, maxRecordedResponseBytes, maxExchanges, maxResources,
                maxStorageBytes, requestBurst, requestsPerSecond, tokenLifetime, indexLag, maxDeliveries, allow);
    }
}
