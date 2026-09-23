package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

/**
 * Evaluates a step's {@code response} against what came back (EXECUTION.md section 7), in the
 * order the contract fixes: status, content type, location, links, other headers, challenge,
 * body, JSON, JWT, content negotiation. A capture is bound as soon as its expectation passes,
 * and the first failure ends the evaluation: it is the finding the test reports.
 */
final class Evaluator {

    /** What a step's evaluation found, and the locations it registered for cleanup. */
    record Evaluation(boolean passed, List<AssertionResult> results, List<URI> cleanup) {
    }

    private final JsonNode expect;
    private final Req req;
    private final Resp resp;
    private final Scope scope;
    private final Path directory;
    private final Function<String, Req> refetch;
    private final List<AssertionResult> results = new ArrayList<>();
    private final List<URI> cleanup = new ArrayList<>();

    private Evaluator(JsonNode expect, Req req, Resp resp, Scope scope, Path directory, Function<String, Req> refetch) {
        this.expect = expect;
        this.req = req;
        this.resp = resp;
        this.scope = scope;
        this.directory = directory;
        this.refetch = refetch;
    }

    /** @param refetch builds the step's request again with another Accept, for {@code connegEquivalent} */
    static Evaluation evaluate(JsonNode expect, Req req, Resp resp, Scope scope, Path directory,
                               Function<String, Req> refetch) {
        Evaluator e = new Evaluator(expect, req, resp, scope, directory, refetch);
        boolean passed = e.statusCode() && e.contentType() && e.location() && e.linkHeaders() && e.otherHeaders()
                && e.challenge() && e.body() && e.json() && e.jwt() && e.conneg();
        return new Evaluation(passed, List.copyOf(e.results), List.copyOf(e.cleanup));
    }

    private boolean check(boolean ok, String description, String expected, String actual) {
        results.add(ok ? AssertionResult.ok(description, expected, actual)
                : AssertionResult.failed(description, expected, actual));
        return ok;
    }

    // 1. statusCode
    private boolean statusCode() {
        JsonNode sc = expect.path("statusCode");
        List<String> expected = new ArrayList<>();
        boolean ok = false;
        for (JsonNode s : sc.isArray() ? sc : List.of(sc)) {
            expected.add(s.asText());
            if (s.isInt() ? s.asInt() == resp.status()
                    : s.asText().length() == 3 && s.asText().charAt(0) - '0' == resp.status() / 100) {
                ok = true;
            }
        }
        String want = expected.size() == 1 ? expected.getFirst() : String.join(" or ", expected);
        return check(ok, "status code", want, String.valueOf(resp.status()));
    }

    // 2. contentType
    private boolean contentType() {
        if (!expect.has("contentType")) {
            return true;
        }
        String want = expect.get("contentType").asText();
        String actual = Headers.essence(resp.first("Content-Type"));
        return check(want.equals(actual), "Content-Type", want, String.valueOf(actual));
    }

    // 3. location
    private boolean location() {
        if (!expect.has("location")) {
            return true;
        }
        JsonNode l = expect.get("location");
        String location = resp.first("Location");
        if (!check(location != null, "Location", "present", location == null ? "absent" : location)) {
            return false;
        }
        URI resolved = req.uri().resolve(location);
        scope.bind(l.path("capture").asText(), resolved.toString());
        if (l.path("cleanup").asBoolean(false)) {
            cleanup.add(resolved);
        }
        return true;
    }

    // 4. linkHeaders
    private boolean linkHeaders() {
        if (!expect.has("linkHeaders")) {
            return true;
        }
        List<LinkValues.Link> links = LinkValues.parse(resp.header("Link"), req.uri());
        for (JsonNode e : expect.get("linkHeaders")) {
            String rel = e.path("rel").asText();
            String href = e.has("href") ? LinkValues.resolve(req.uri(), Templates.expand(e.get("href").asText(), scope)) : null;
            String mediaType = e.has("mediaType") ? e.get("mediaType").asText() : null;
            LinkValues.Link match = null;
            for (LinkValues.Link link : links) {
                if (link.hasRel(rel) && (href == null || link.target().equals(href))
                        && (mediaType == null || mediaType.equalsIgnoreCase(link.param("type")))) {
                    match = link;
                    break;
                }
            }
            String wanted = "rel=\"" + rel + "\"" + (href == null ? "" : " to " + href)
                    + (mediaType == null ? "" : " type=\"" + mediaType + "\"");
            String actual = String.join(", ", resp.header("Link"));
            if (e.path("absent").asBoolean(false)) {
                if (!check(match == null, "Link " + wanted + " absent", "no such link", actual.isEmpty() ? "no Link" : actual)) {
                    return false;
                }
                continue;
            }
            if (!check(match != null, "Link " + wanted, "a matching link", actual.isEmpty() ? "no Link" : actual)) {
                return false;
            }
            if (e.has("capture")) {
                scope.bind(e.get("capture").asText(), match.target());
            }
        }
        return true;
    }

    // 5. otherHeaders
    private boolean otherHeaders() {
        for (JsonNode e : expect.path("otherHeaders")) {
            String name = e.path("headerName").asText();
            List<String> lines = resp.header(name);
            String combined = String.join(", ", lines);
            String actual = lines.isEmpty() ? "absent" : combined;
            for (Map.Entry<String, JsonNode> op : e.properties()) {
                boolean ok;
                String expected;
                switch (op.getKey()) {
                    case "headerName" -> {
                        continue;
                    }
                    case "headerValue" -> {
                        String v = Templates.expand(op.getValue().asText(), scope).trim();
                        expected = v;
                        ok = combined.trim().equals(v) || lines.stream().anyMatch(l -> l.trim().equals(v));
                    }
                    case "present" -> {
                        expected = op.getValue().asBoolean() ? "present" : "absent";
                        ok = op.getValue().asBoolean() != lines.isEmpty();
                    }
                    case "differsFrom" -> {
                        String v = Templates.expand(op.getValue().asText(), scope).trim();
                        expected = "present and not " + v;
                        ok = !lines.isEmpty() && lines.stream().noneMatch(l -> l.trim().equals(v));
                    }
                    case "matches" -> {
                        Pattern p = Pattern.compile(op.getValue().asText());
                        expected = "matches /" + op.getValue().asText() + "/";
                        ok = p.matcher(combined).find() || lines.stream().anyMatch(l -> p.matcher(l).find());
                    }
                    case "capture" -> {
                        if (!check(!lines.isEmpty(), "header " + name + " captured", "present", actual)) {
                            return false;
                        }
                        scope.bind(op.getValue().asText(), combined);
                        continue;
                    }
                    default -> {
                        expected = op.getKey();
                        ok = false;
                    }
                }
                if (!check(ok, "header " + name + " " + op.getKey(), expected, actual)) {
                    return false;
                }
            }
        }
        return true;
    }

    // 6. authenticationChallenge
    private boolean challenge() {
        if (!expect.has("authenticationChallenge")) {
            return true;
        }
        JsonNode e = expect.get("authenticationChallenge");
        String scheme = e.path("wwwAuthenticate").asText();
        List<JsonNode> params = new ArrayList<>();
        shorthand(e, "asUri", "as_uri", params);
        shorthand(e, "realm", "realm", params);
        e.path("params").forEach(params::add);
        List<String> lines = resp.header("WWW-Authenticate");
        for (Challenges.Challenge c : Challenges.parse(lines)) {
            if (!c.scheme().equalsIgnoreCase(scheme)) {
                continue;
            }
            Map<String, String> captured = new HashMap<>();
            if (params.stream().allMatch(p -> param(c, p, captured))) {
                captured.forEach(scope::bind);
                return check(true, "WWW-Authenticate", describe(scheme, params), String.join(", ", lines));
            }
        }
        return check(false, "WWW-Authenticate", describe(scheme, params),
                lines.isEmpty() ? "no WWW-Authenticate" : String.join(" | ", lines));
    }

    private static void shorthand(JsonNode e, String key, String paramName, List<JsonNode> out) {
        if (!e.has(key)) {
            return;
        }
        JsonNode v = e.get(key);
        com.fasterxml.jackson.databind.node.ObjectNode p = Templates.JSON.createObjectNode();
        p.put("paramName", paramName);
        if (v.isTextual()) {
            p.put("paramValue", v.asText());
        } else {
            p.setAll((com.fasterxml.jackson.databind.node.ObjectNode) v);
        }
        out.add(p);
    }

    private boolean param(Challenges.Challenge c, JsonNode p, Map<String, String> captured) {
        String value = c.param(p.path("paramName").asText());
        if (p.has("present") && p.get("present").asBoolean() == (value == null)) {
            return false;
        }
        if (p.has("paramValue") && (value == null || !value.equals(Templates.expand(p.get("paramValue").asText(), scope)))) {
            return false;
        }
        if (p.has("matches") && (value == null || !Pattern.compile(p.get("matches").asText()).matcher(value).find())) {
            return false;
        }
        if (p.has("capture")) {
            if (value == null) {
                return false;
            }
            captured.put(p.get("capture").asText(), value);
        }
        return true;
    }

    private static String describe(String scheme, List<JsonNode> params) {
        StringBuilder sb = new StringBuilder("a ").append(scheme).append(" challenge");
        for (JsonNode p : params) {
            sb.append(", ").append(p.path("paramName").asText());
            p.properties().forEach(op -> {
                if (!op.getKey().equals("paramName")) {
                    sb.append(' ').append(op.getKey()).append(' ').append(op.getValue().asText());
                }
            });
        }
        return sb.toString();
    }

    // 7. body
    private boolean body() {
        if (expect.path("bodyEmpty").asBoolean(false)
                && !check(resp.body().length == 0, "body", "empty", resp.body().length + " bytes")) {
            return false;
        }
        if (expect.has("bodyMatches")) {
            String pattern = expect.get("bodyMatches").asText();
            if (!check(Pattern.compile(pattern).matcher(resp.text()).find(), "body", "matches /" + pattern + "/",
                    abbreviate(resp.text()))) {
                return false;
            }
        }
        if (expect.has("bodyURL")) {
            String file = expect.get("bodyURL").asText();
            byte[] want = Requests.fixture(directory, file);
            return check(Arrays.equals(want, resp.body()), "body", "the bytes of " + file, abbreviate(resp.text()));
        }
        return true;
    }

    // 8. json
    private boolean json() {
        if (!expect.has("json")) {
            return true;
        }
        JsonNode root;
        try {
            root = Templates.JSON.readTree(resp.body());
        } catch (IOException e) {
            root = null;
        }
        if (!check(root != null && !root.isMissingNode(), "body", "JSON", abbreviate(resp.text()))) {
            return false;
        }
        return JsonChecks.check("json", root, expect.get("json"), scope, req.uri(), scope::bind, results);
    }

    // 9. jwt
    private boolean jwt() {
        if (!expect.has("jwt")) {
            return true;
        }
        JsonNode e = expect.get("jwt");
        String token = Templates.expand(e.path("token").asText(), scope);
        Jwts.Decoded decoded;
        try {
            decoded = Jwts.decode(token);
        } catch (IllegalArgumentException ex) {
            return check(false, "jwt", "a compact JWS", ex.getMessage());
        }
        if (e.has("jwks")) {
            String alg = decoded.header().path("alg").asText();
            if (!check(!alg.equalsIgnoreCase("none"), "jwt alg", "a signing algorithm", alg)) {
                return false;
            }
            JWKSet keys = jwks(Templates.expand(e.get("jwks").asText(), scope));
            String kid = decoded.header().path("kid").asText(null);
            JWK key = kid != null ? keys.getKeyByKeyId(kid) : keys.getKeys().size() == 1 ? keys.getKeys().getFirst() : null;
            if (!check(key != null, "jwt key", "a key in the JWKS for kid " + kid, keys.getKeys().size() + " keys")) {
                return false;
            }
            if (!check(Jwts.verify(token, key), "jwt signature", "verifies with " + key.getKeyID(), "does not verify")) {
                return false;
            }
        }
        return JsonChecks.check("jwt header", decoded.header(), e.path("header"), scope, req.uri(), scope::bind, results)
                && JsonChecks.check("jwt claims", decoded.claims(), e.path("claims"), scope, req.uri(), scope::bind, results);
    }

    private JWKSet jwks(String url) {
        Req get = new Req("GET", URI.create(url), List.of(Http.header("Accept", "application/json")), null, null);
        try {
            Resp r = scope.run().send(get);
            if (r.status() != 200) {
                throw Unresolvable.cantTell("the JWKS at " + url + " answered " + r.status());
            }
            return JWKSet.parse(r.text());
        } catch (IOException | java.text.ParseException ex) {
            throw Unresolvable.cantTell("cannot read the JWKS at " + url + ": " + ex.getMessage());
        }
    }

    // 10. connegEquivalent
    private boolean conneg() {
        if (!expect.has("connegEquivalent")) {
            return true;
        }
        List<byte[]> bodies = new ArrayList<>();
        for (JsonNode type : expect.get("connegEquivalent").path("accepts")) {
            String mediaType = type.asText();
            Req again = refetch.apply(mediaType);
            Resp r;
            try {
                r = scope.run().send(again);
            } catch (IOException ex) {
                throw Unresolvable.cantTell("conneg fetch as " + mediaType + " failed: " + ex);
            }
            String essence = Headers.essence(r.first("Content-Type"));
            if (!check(r.status() == 200 && mediaType.equals(essence), "conneg " + mediaType,
                    "200 as " + mediaType, r.status() + " as " + essence)) {
                return false;
            }
            bodies.add(r.body());
        }
        boolean identical = bodies.stream().allMatch(b -> Arrays.equals(b, bodies.getFirst()));
        return check(identical, "conneg equivalence", "byte-identical representations",
                identical ? "byte-identical" : "different bytes; the JSON-LD graphs are "
                        + JsonLdGraphs.compare(bodies, req.uri()));
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
