package com.ebremer.touchstone.core.engine;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.JWK;

/**
 * What the fixture host learns about a delivery's HTTP Message Signature (RFC 9421) and its
 * Content-Digest (RFC 9530), recorded with the delivery (EXECUTION.md section 5.4), so a test can
 * assert on each fact with an ordinary {@code json} expectation.
 *
 * <p>The signature base is rebuilt from the inbox URL the server was given, not from the request
 * as it reached the fixture host: a reverse proxy in front of the host changes the scheme,
 * authority and possibly the path, and the server signed what it addressed.
 */
final class HttpSignatures {

    private HttpSignatures() {
    }

    /**
     * Inspects a delivery.
     *
     * @param inbox       the inbox URL the server was given (scheme, authority and path of the base)
     * @param headers     the request headers, names in any case
     * @param description fetches the storage description a keyid's URL names, or returns null when
     *                    it may not or cannot be fetched
     * @return {@code {"signature": {...}, "contentDigest": {...}}}
     */
    static ObjectNode inspect(URI inbox, String method, Map<String, List<String>> headers, byte[] body,
                              Function<URI, JsonNode> description) {
        Map<String, String> h = new LinkedHashMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
        ObjectNode out = Templates.JSON.createObjectNode();
        out.set("contentDigest", contentDigest(h.get("content-digest"), body));
        out.set("signature", signature(inbox, method, h, description));
        return out;
    }

    // ------------------------------------------------------------------ Content-Digest (RFC 9530)

    private static ObjectNode contentDigest(String header, byte[] body) {
        ObjectNode d = Templates.JSON.createObjectNode();
        d.put("present", header != null);
        if (header == null) {
            d.putNull("valid");
            return d;
        }
        boolean any = false;
        boolean valid = true;
        var algorithms = d.putArray("algorithms");
        for (Map.Entry<String, String> m : dictionary(header).entrySet()) {
            String jdk = switch (m.getKey()) {
                case "sha-256" -> "SHA-256";
                case "sha-512" -> "SHA-512";
                default -> null;
            };
            algorithms.add(m.getKey());
            if (jdk == null) {
                continue;
            }
            any = true;
            String value = m.getValue();
            if (!value.startsWith(":") || !value.endsWith(":") || value.length() < 2) {
                valid = false;
                continue;
            }
            try {
                byte[] claimed = Base64.getDecoder().decode(value.substring(1, value.length() - 1));
                valid &= MessageDigest.isEqual(claimed, MessageDigest.getInstance(jdk).digest(body));
            } catch (Exception e) {
                valid = false;
            }
        }
        d.put("valid", any && valid);
        return d;
    }

    // ------------------------------------------------------------------ the signature (RFC 9421)

    private static ObjectNode signature(URI inbox, String method, Map<String, String> h,
                                        Function<URI, JsonNode> description) {
        ObjectNode s = Templates.JSON.createObjectNode();
        String input = h.get("signature-input");
        String sigs = h.get("signature");
        s.put("present", input != null && sigs != null);
        if (input == null || sigs == null) {
            return fail(s, "no Signature-Input and Signature");
        }
        Map<String, String> inputs = dictionary(input);
        Map<String, String> signatures = dictionary(sigs);
        String label = inputs.keySet().stream().filter(signatures::containsKey).findFirst().orElse(null);
        if (label == null) {
            return fail(s, "no label appears in both Signature-Input and Signature");
        }
        s.put("label", label);
        String params = inputs.get(label);
        int close = params.indexOf(')');
        if (!params.startsWith("(") || close < 0) {
            return fail(s, "Signature-Input is not an inner list");
        }
        List<String> covered = new ArrayList<>();
        for (String item : params.substring(1, close).trim().split("\\s+")) {
            if (item.length() >= 2 && item.startsWith("\"") && item.endsWith("\"")) {
                covered.add(item.substring(1, item.length() - 1));
            } else if (!item.isEmpty()) {
                return fail(s, "a covered component is not a quoted string: " + item);
            }
        }
        var c = s.putArray("covered");
        covered.forEach(c::add);
        Map<String, String> p = parameters(params.substring(close + 1));
        String created = p.get("created");
        if (created != null && created.matches("\\d+")) {
            s.put("created", Long.parseLong(created));
        } else {
            s.putNull("created");
        }
        String keyid = unquote(p.get("keyid"));
        String alg = unquote(p.get("alg"));
        putOrNull(s, "keyid", keyid);
        putOrNull(s, "alg", alg);

        // the key the keyid names, in the storage description
        if (keyid == null || !keyid.contains("#")) {
            return fail(s, "the keyid is not a URL with a fragment");
        }
        URI storage;
        try {
            storage = URI.create(keyid.substring(0, keyid.indexOf('#')));
        } catch (IllegalArgumentException e) {
            return fail(s, "the keyid is not a URL: " + keyid);
        }
        JsonNode doc = description.apply(storage);
        if (doc == null) {
            return fail(s, "could not fetch the storage description at " + storage);
        }
        s.put("storageDescriptionIdMatches", storage.toString().equals(doc.path("id").asText(null)));
        String fragment = keyid.substring(keyid.indexOf('#'));
        JsonNode vm = null;
        for (JsonNode m : doc.path("verificationMethod")) {
            String id = m.path("id").asText("");
            if (id.equals(keyid) || id.equals(fragment) || id.equals(fragment.substring(1))) {
                vm = m;
                break;
            }
        }
        s.put("keyResolved", vm != null);
        if (vm == null) {
            s.put("keyInAuthentication", false);
            return fail(s, "no verificationMethod in the storage description has the id " + keyid);
        }
        String vmId = vm.path("id").asText();
        boolean inAuthentication = false;
        for (JsonNode a : doc.path("authentication")) {
            String ref = a.isTextual() ? a.asText() : a.path("id").asText("");
            if (ref.equals(vmId) || ref.equals(keyid) || ref.equals(fragment)) {
                inAuthentication = true;
            }
        }
        s.put("keyInAuthentication", inAuthentication);

        // the signature base (RFC 9421 section 2.5), rebuilt from the inbox URL the server was given
        StringBuilder base = new StringBuilder();
        for (String component : covered) {
            String value;
            switch (component) {
                case "@method" -> value = method.toUpperCase(Locale.ROOT);
                case "@scheme" -> value = inbox.getScheme().toLowerCase(Locale.ROOT);
                case "@authority" -> value = inbox.getRawAuthority().toLowerCase(Locale.ROOT);
                case "@path" -> value = inbox.getRawPath() == null || inbox.getRawPath().isEmpty() ? "/" : inbox.getRawPath();
                case "@target-uri" -> value = inbox.toString();
                case "@query" -> value = "?" + (inbox.getRawQuery() == null ? "" : inbox.getRawQuery());
                default -> {
                    if (component.startsWith("@")) {
                        return fail(s, "unsupported derived component " + component);
                    }
                    value = h.get(component);
                    if (value == null) {
                        return fail(s, "covered header " + component + " is absent");
                    }
                    value = value.trim();
                }
            }
            base.append('"').append(component).append("\": ").append(value).append('\n');
        }
        base.append("\"@signature-params\": ").append(params);

        String sig = signatures.get(label);
        if (!sig.startsWith(":") || !sig.endsWith(":") || sig.length() < 2) {
            return fail(s, "the Signature value is not a byte sequence");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(sig.substring(1, sig.length() - 1));
            JWK jwk = JWK.parse(vm.path("publicKeyJwk").toString());
            String algorithm = alg != null ? alg : inferAlg(jwk);
            boolean ok = verify(algorithm, jwk, base.toString().getBytes(StandardCharsets.UTF_8), bytes);
            s.put("verified", ok);
            if (ok) {
                s.putNull("reason");
            } else {
                s.put("reason", "the signature does not verify with " + vmId + " (" + algorithm + ")");
            }
        } catch (Exception e) {
            return fail(s, "cannot verify: " + e.getMessage());
        }
        return s;
    }

    private static ObjectNode fail(ObjectNode s, String reason) {
        s.put("verified", false);
        s.put("reason", reason);
        return s;
    }

    private static void putOrNull(ObjectNode n, String key, String value) {
        if (value == null) {
            n.putNull(key);
        } else {
            n.put(key, value);
        }
    }

    private static String inferAlg(JWK jwk) {
        return switch (jwk.getKeyType().getValue()) {
            case "EC" -> "ecdsa-p256-sha256";
            case "OKP" -> "ed25519";
            case "RSA" -> "rsa-pss-sha512";
            default -> "unknown";
        };
    }

    private static boolean verify(String alg, JWK jwk, byte[] data, byte[] sig) throws Exception {
        Signature v;
        PublicKey key;
        switch (alg) {
            case "ecdsa-p256-sha256" -> {
                v = Signature.getInstance("SHA256withECDSAinP1363Format");
                key = jwk.toECKey().toECPublicKey();
            }
            case "ed25519" -> {
                v = Signature.getInstance("Ed25519");
                byte[] x = Base64.getUrlDecoder().decode(jwk.toJSONObject().get("x").toString());
                byte[] spki = new byte[12 + x.length];
                System.arraycopy(new BigInteger("302a300506032b6570032100", 16).toByteArray(), 0, spki, 0, 12);
                System.arraycopy(x, 0, spki, 12, x.length);
                key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            }
            case "rsa-pss-sha512" -> {
                v = Signature.getInstance("RSASSA-PSS");
                v.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1));
                key = jwk.toRSAKey().toRSAPublicKey();
            }
            case "rsa-v1_5-sha256" -> {
                v = Signature.getInstance("SHA256withRSA");
                key = jwk.toRSAKey().toRSAPublicKey();
            }
            default -> throw new IllegalArgumentException("unsupported alg " + alg);
        }
        v.initVerify(key);
        v.update(data);
        return v.verify(sig);
    }

    // ------------------------------------------------------------------ RFC 8941, as far as needed

    /** A Structured Field Dictionary: member name to its raw value text (inner list and parameters kept). */
    static Map<String, String> dictionary(String field) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String member : splitTopLevel(field, ',')) {
            String m = member.trim();
            int eq = m.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(m.substring(0, eq).trim(), m.substring(eq + 1).trim());
        }
        return out;
    }

    /** {@code ;name=value} parameters after an item or inner list. */
    private static Map<String, String> parameters(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : splitTopLevel(text, ';')) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            out.put(eq < 0 ? t : t.substring(0, eq).trim(), eq < 0 ? "?1" : t.substring(eq + 1).trim());
        }
        return out;
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        boolean bytes = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ':' && depth == 0) {
                bytes = !bytes;
            } else if (!bytes && ch == '(') {
                depth++;
            } else if (!bytes && ch == ')') {
                depth--;
            } else if (!bytes && depth == 0 && ch == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String unquote(String v) {
        if (v == null) {
            return null;
        }
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\") : v;
    }
}
