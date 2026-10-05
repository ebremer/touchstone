package com.ebremer.touchstone.clients;

import java.util.List;
import java.util.Map;

/**
 * One request a client sent to its session and the answer it got, as the traffic log keeps it
 * (CLIENT-TESTING.md section 4.3): redacted, bodies cut short, and annotated with what only the
 * server knows.
 *
 * @param seq its place in the session's log, from 1
 * @param at when the request arrived, ISO 8601
 * @param millis how long the answer took
 * @param url the request's public URL, credentials in its query redacted
 */
record Exchange(long seq, String at, long millis, String method, String url,
                Map<String, List<String>> requestHeaders, Body requestBody,
                int status, Map<String, List<String>> responseHeaders, Body responseBody,
                Annotations annotations) {

    /**
     * A body: its length in bytes, and its text when the media type is textual, cut to the
     * recorder's limit.
     */
    record Body(long length, String text, boolean truncated) {
        static final Body NONE = new Body(0, null, false);
    }

    /**
     * What the server knows about the exchange.
     *
     * @param role what the request addressed: storageDescription, container, page, dataResource,
     *     linkset, a service (accessGrants, subscriptions, typeSearch, ...), decoy, asMetadata,
     *     asJwks, asToken, preflight, unknown (nothing there), or limited (refused by a bound)
     * @param identity alice or bob, the subject IRI of another valid token, or null when no valid
     *     token was presented
     * @param token the presented token's fingerprint, or null
     * @param presentation how a token was presented: authorization (a Bearer header),
     *     authorization:<scheme> for another scheme, query, form, or none
     * @param issued whether the session had handed out this URL before the request, so the client
     *     did not build it
     * @param issuedVia how the URL was handed out: session, location, content-location,
     *     link:<rel>, challenge:as_uri, body:<role>
     * @param advertised what the URL's last answer to this client advertised: Allow,
     *     Accept-Patch, Accept-Query, ETag
     * @param fault the fault that fired on this exchange, or null (phase C3)
     * @param limit the bound that refused the request (rate, body, storage), or null
     */
    record Annotations(String role, String identity, String token, String presentation, boolean issued,
                       String issuedVia, Map<String, String> advertised, String fault, String limit) {
    }
}
