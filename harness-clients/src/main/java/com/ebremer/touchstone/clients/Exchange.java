package com.ebremer.touchstone.clients;

import java.util.List;
import java.util.Map;

/**
 * One request a client sent to its session and the answer it got, as the traffic log keeps it
 * (CLIENT-TESTING.md section 4.3): redacted, bodies cut short, annotated with what only the
 * server knows, and with the verdicts of the rules it was a trial of.
 *
 * @param seq its place in the session's log, from 1
 * @param at when the request arrived, ISO 8601
 * @param millis how long the answer took
 * @param url the request's public URL, credentials in its query redacted
 * @param rules the client rules this exchange was a trial of, and how each judged it
 */
record Exchange(long seq, String at, long millis, String method, String url,
                Map<String, List<String>> requestHeaders, Body requestBody,
                int status, Map<String, List<String>> responseHeaders, Body responseBody,
                Annotations annotations, List<Judge.Verdict> rules) {

    /**
     * A body: its length in bytes, and its text when the media type is textual, cut to the
     * recorder's limit.
     */
    record Body(long length, String text, boolean truncated) {
        static final Body NONE = new Body(0, null, false);
    }

    /**
     * What the server knows about the exchange: the annotations of OBSERVATION.md section 4, and
     * a few more for the traffic log.
     *
     * @param server storage or authorizationServer, or null for another URL of the session
     * @param role what the request addressed (OBSERVATION.md section 4.2), or preflight, or
     *     limited (refused by a bound)
     * @param identity alice or bob, the subject IRI of another valid token, or null when no valid
     *     token was presented
     * @param token the fingerprint of the first credential presented, or null
     * @param presentation where the request carried a credential: bearer, otherScheme, query,
     *     form, otherHeader; or none
     * @param issued whether the session had handed out this URL before the request
     * @param issuedVia how the URL was handed out: session, location, content-location,
     *     link:<rel>, challenge:as_uri, body:<role>
     * @param builtBy for a URL not issued, query or path: how it relates to the issued URL it was
     *     built from (OBSERVATION.md section 4.3); null otherwise
     * @param builtFrom that issued URL, or null
     * @param builtFromRole that URL's role, or null when the client never requested it
     * @param advertised what the URL's last answer advertised: Allow, Accept-Patch, Accept-Query,
     *     ETag
     * @param methodAdvertised whether Allow listed the request's method
     * @param patchFormatAdvertised whether Accept-Patch listed the request's Content-Type
     * @param queryFormatAdvertised whether Accept-Query listed the request's Content-Type
     * @param repeat whether the request has the method, Content-Type and body of the previous
     *     request to the same URL
     * @param containerEmpty whether the request addressed a container with no members
     * @param fault the fault that fired on this exchange, or null
     * @param limit the bound that refused the request (rate, body, storage), or null
     */
    record Annotations(String server, String role, String identity, String token, List<String> presentation,
                       boolean issued, String issuedVia, String builtBy, String builtFrom, String builtFromRole,
                       Map<String, String> advertised, boolean methodAdvertised, boolean patchFormatAdvertised,
                       boolean queryFormatAdvertised, boolean repeat, boolean containerEmpty, String fault,
                       String limit) {
    }
}
