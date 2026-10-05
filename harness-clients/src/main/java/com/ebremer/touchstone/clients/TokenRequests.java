package com.ebremer.touchstone.clients;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the recorder knows about a request to the session's token endpoint (OBSERVATION.md
 * sections 4.9 and 4.10): the subject token it presents, decoded and classified, and whether the
 * realm it asks a token for contains the request the session challenged with that realm. It reads
 * the raw form body, before redaction, and keeps no credential: a JWT's header and claims are not
 * secret, and its signature is dropped.
 */
final class TokenRequests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORM = "application/x-www-form-urlencoded";

    /**
     * @param credentialSource selfIssued, openidProvider, authorizationServer or other; null when
     *     the request presents no subject token
     * @param credential the subject token's header and claims, {@code {"header": ..., "claims": ...}},
     *     or null when it presents none or it is not a JWT
     * @param audienceIncludesAs whether the JWT's aud names the session's authorization server;
     *     null when there is no JWT
     * @param identifiersAgree whether the JWT's sub, iss and client_id are the same string; null
     *     when there is no JWT
     * @param realmContainsRequest whether the request's resource is a realm the session presented
     *     for a URL it contains; null when it names no resource, or a realm never presented
     */
    record Facts(String credentialSource, JsonNode credential, Boolean audienceIncludesAs, Boolean identifiersAgree,
                 Boolean realmContainsRequest) {
        static final Facts NONE = new Facts(null, null, null, null, null);
    }

    private TokenRequests() {
    }

    /** The facts of a token request with this Content-Type and raw body, in {@code session}. */
    static Facts of(Session session, String contentType, byte[] body) {
        String essence = contentType == null ? null : contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!FORM.equals(essence)) {
            return Facts.NONE;
        }
        Map<String, String> form = form(new String(body, StandardCharsets.UTF_8));
        String resource = form.get("resource");
        Boolean realm = resource == null ? null : session.recorder.realmContains(resource);
        String token = form.get("subject_token");
        if (token == null) {
            return new Facts(null, null, null, null, realm);
        }
        ObjectNode jwt = decode(token);
        String source = session.op.issued(token) ? "openidProvider"
                : session.tokens.contains(token) ? "authorizationServer"
                : jwt != null && namesIdentity(session, jwt) ? "selfIssued" : "other";
        if (jwt == null) {
            return new Facts(source, null, null, null, realm);
        }
        JsonNode claims = jwt.get("claims");
        JsonNode aud = claims.get("aud");
        String issuer = session.as.issuer();
        boolean audience = aud != null && (aud.isTextual() ? aud.asText().equals(issuer) : contains(aud, issuer));
        JsonNode sub = claims.get("sub");
        boolean agree = sub != null && sub.isTextual() && sub.equals(claims.get("iss")) && sub.equals(claims.get("client_id"));
        return new Facts(source, jwt, audience, agree, realm);
    }

    /**
     * The facts a proxy session can know of a token request to the server behind it
     * (OBSERVATION.md section 11): only whether the realm it asks for contains the request a 401
     * refused. Where its credential came from, and what it says, need the session's own servers.
     */
    static Facts realmOnly(Session session, String contentType, byte[] body) {
        String essence = contentType == null ? null : contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!FORM.equals(essence)) {
            return Facts.NONE;
        }
        String resource = form(new String(body, StandardCharsets.UTF_8)).get("resource");
        return new Facts(null, null, null, null, resource == null ? null : session.recorder.realmContains(resource));
    }

    /**
     * A compact JWT's header and claims, {@code {"header": ..., "claims": ...}}: three
     * base64url segments, the first two JSON objects. Null for anything else, a JWE included.
     */
    static ObjectNode decode(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (header == null || !header.isObject() || claims == null || !claims.isObject()) {
                return null;
            }
            ObjectNode out = JSON.createObjectNode();
            out.set("header", header);
            out.set("claims", claims);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the JWT's kid, iss, sub or client_id names one of the session's identities, or a fragment of one. */
    private static boolean namesIdentity(Session session, ObjectNode jwt) {
        String[] values = {jwt.path("header").path("kid").asText(null), jwt.path("claims").path("iss").asText(null),
                jwt.path("claims").path("sub").asText(null), jwt.path("claims").path("client_id").asText(null)};
        for (Session.Identity identity : session.identities.values()) {
            for (String v : values) {
                if (v != null && (v.equals(identity.webid()) || v.startsWith(identity.webid() + "#"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(JsonNode array, String value) {
        if (array.isArray()) {
            for (JsonNode n : array) {
                if (n.isTextual() && n.asText().equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first value of each parameter of a form body. */
    static Map<String, String> form(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                out.putIfAbsent(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                        eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // a malformed escape: the parameter is ignored
            }
        }
        return out;
    }
}
