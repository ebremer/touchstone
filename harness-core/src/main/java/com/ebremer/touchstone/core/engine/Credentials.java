package com.ebremer.touchstone.core.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ebremer.touchstone.core.definitions.IdentityDefinition;
import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.ebremer.touchstone.core.exec.Target;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Identities and credentials (EXECUTION.md section 5): where each identity's access token
 * comes from, the single defect a fault identity adds, and the subject credentials minted for
 * the authentication suites. One instance per run; keys generated here live for the run.
 *
 * <p>Target properties it reads, all optional:
 * <ul>
 *   <li>{@code as.signingKey}: the authorization server's private JWK, for HarnessIssuedTokens;
 *       {@code as.clientId}: the {@code client_id} its tokens carry (default {@code touchstone});</li>
 *   <li>{@code token.<name>}, or the environment variable {@code TOUCHSTONE_TOKEN_<NAME>}: a
 *       static access token;</li>
 *   <li>{@code webid.<name>}, or {@code TOUCHSTONE_WEBID_<NAME>}: the identity's agent IRI;</li>
 *   <li>{@code didkey.jwk.<name>}: a P-256 private JWK. For alice or bob, a subject credential
 *       the engine exchanges for their access token (5.2, item 2); for {@code didkey}, the
 *       pinned key (5.3);</li>
 *   <li>{@code saml.idpKey} (an RSA private JWK) and {@code saml.idpCertificate} (PEM): the
 *       harness identity provider the target trusts (SamlTrust).</li>
 * </ul>
 */
final class Credentials {

    static final String HARNESS_ISSUED = "HarnessIssuedTokens";
    private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String JWT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    private final RunSession run;
    private final Target target;
    private final Lazy<ECKey> didkeyKey;
    private final Lazy<ECKey> cidKey;
    private final Lazy<ECKey> opKey;
    private final Lazy<ECKey> rogueOpKey;
    private final Lazy<JWK> asKey;
    private final Lazy<RSAKey> samlKey;
    private final Map<String, Exchanged> exchanged = new ConcurrentHashMap<>();

    /** An access token obtained by token exchange, and when to stop using it. */
    private record Exchanged(String token, long refreshAfter) {
    }

    Credentials(RunSession run) {
        this.run = run;
        this.target = run.target();
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        this.didkeyKey = new Lazy<>(() -> pinnedOrFresh("didkey", null));
        this.cidKey = new Lazy<>(() -> Jwts.freshEc("key-" + shortId));
        this.opKey = new Lazy<>(() -> Jwts.freshEc("op-" + shortId));
        this.rogueOpKey = new Lazy<>(() -> Jwts.freshEc("rogue-" + shortId));
        this.asKey = new Lazy<>(this::parseAsKey);
        this.samlKey = new Lazy<>(this::parseSamlKey);
    }

    // ------------------------------------------------------------------ identities

    IdentityDefinition identity(String name) {
        return run.definitions().identity(name)
                .orElseThrow(() -> Unresolvable.cantTell("identity " + name + " is not registered"));
    }

    private static String suite(IdentityDefinition id) {
        String s = id.suite() == null ? "" : id.suite();
        if (s.contains("did-key")) {
            return "didkey";
        }
        if (s.contains("ssi-cid")) {
            return "cid";
        }
        if (s.contains("openid")) {
            return "oidc";
        }
        if (s.contains("saml")) {
            return "saml";
        }
        throw Unresolvable.cantTell("identity " + id.name() + " names no suite the engine knows: " + id.suite());
    }

    /** {@code ${identity.<name>.webid}}: the identity's agent IRI (section 5). */
    String webid(String name, Scope scope) {
        IdentityDefinition id = identity(name);
        if (id.basis() != null) {
            return webid(id.basis(), scope);
        }
        switch (id.kind()) {
            case IdentityDefinition.NO_CREDENTIAL:
                return RunSession.FOAF_AGENT;
            case IdentityDefinition.STORAGE_ACCESS_TOKEN: {
                String configured = setting("webid." + name, "TOUCHSTONE_WEBID_");
                if (configured != null) {
                    return configured;
                }
                if (target.properties().containsKey("didkey.jwk." + name)) {
                    return DidKeys.did(pinnedOrFresh(name, null));
                }
                throw Unresolvable.inapplicable("no agent IRI for " + name + ": set webid." + name
                        + " or TOUCHSTONE_WEBID_" + envName(name));
            }
            default: {
                if (suite(id).equals("didkey")) {
                    return DidKeys.did(didkeyKey.get());
                }
                if (id.webid() == null) {
                    throw Unresolvable.cantTell("identity " + name + " defines no webid");
                }
                return Templates.expand(id.webid(), scope);
            }
        }
    }

    // ------------------------------------------------------------------ access tokens (5.2)

    /**
     * The access token a request made as {@code name} carries, or empty for none: anonymous,
     * and alice or bob on a target that does not enforce authentication.
     */
    Optional<String> accessToken(String name, Scope scope) {
        IdentityDefinition id = identity(name);
        if (id.kind().equals(IdentityDefinition.NO_CREDENTIAL)) {
            return Optional.empty();
        }
        if (!id.kind().equals(IdentityDefinition.STORAGE_ACCESS_TOKEN)) {
            throw Unresolvable.cantTell(name + " is a " + id.kind() + ", which cannot act on the storage");
        }
        if (id.basis() == null) {
            return baseToken(name, scope);
        }
        String fault = id.fault();
        switch (fault) {
            case "SignatureCorrupted", "AlgNone", "UnknownKeyId" -> {
                String real = baseToken(id.basis(), scope).orElseThrow(() -> Unresolvable.inapplicable(
                        name + " needs a real " + id.basis() + " token to derive " + fault + " from, and the target"
                                + " gives none"));
                try {
                    return Optional.of(switch (fault) {
                        case "SignatureCorrupted" -> Jwts.corruptSignature(real);
                        case "AlgNone" -> Jwts.algNone(real);
                        default -> Jwts.resignWithUnknownKey(real);
                    });
                } catch (IllegalArgumentException e) {
                    throw Unresolvable.inapplicable(id.basis() + "'s token is not a JWS, so " + fault
                            + " cannot be derived from it: " + e.getMessage());
                }
            }
            case "Expired" -> {
                if (harnessIssued()) {
                    return Optional.of(mint(webid(id.basis(), scope), fault, scope));
                }
                return Optional.of(waitForExpiry(id.basis(), scope));
            }
            default -> {
                if (!harnessIssued()) {
                    throw Unresolvable.inapplicable(name + " (" + fault + ") needs " + HARNESS_ISSUED);
                }
                return Optional.of(mint(webid(id.basis(), scope), fault, scope));
            }
        }
    }

    /** Section 5.2, items 1 to 4, in that order of preference. */
    private Optional<String> baseToken(String name, Scope scope) {
        if (harnessIssued()) {
            return Optional.of(mint(webid(name, scope), null, scope));
        }
        if (target.properties().containsKey("didkey.jwk." + name)) {
            return Optional.of(exchange(name, scope));
        }
        String token = setting("token." + name, "TOUCHSTONE_TOKEN_");
        if (token != null) {
            return Optional.of(token);
        }
        if (!target.capabilities().contains("Authentication")) {
            return Optional.empty();
        }
        throw Unresolvable.inapplicable("no credential for " + name + " on target " + target.id()
                + ": set token." + name + " or TOUCHSTONE_TOKEN_" + envName(name)
                + ", or declare " + HARNESS_ISSUED + " with as.signingKey");
    }

    private boolean harnessIssued() {
        if (!target.capabilities().contains(HARNESS_ISSUED)) {
            return false;
        }
        asKey.get();
        return true;
    }

    /** An RFC 9068 access token from the authorization server's own key (5.2, item 1), with at most one fault. */
    private String mint(String subject, String fault, Scope scope) {
        JWK key = asKey.get();
        ObjectNode header = Templates.JSON.createObjectNode();
        header.put("alg", Jwts.algorithmFor(key));
        header.put("typ", "at+jwt");
        if (key.getKeyID() != null) {
            header.put("kid", key.getKeyID());
        }
        long now = Instant.now().getEpochSecond();
        String realm = scope.resolve("as.realm").asText();
        ObjectNode claims = Templates.JSON.createObjectNode();
        claims.put("iss", scope.resolve("as.issuer").asText());
        claims.putArray("aud").add(realm);
        claims.put("sub", subject);
        claims.put("client_id", target.properties().getOrDefault("as.clientId", "touchstone"));
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("jti", UUID.randomUUID().toString());
        if (fault != null) {
            switch (fault) {
                case "Expired" -> {
                    claims.put("iat", now - 3900);
                    claims.put("exp", now - 3600);
                }
                case "NotYetValid" -> claims.put("nbf", now + 3600);
                case "IssuedInFuture" -> {
                    claims.put("iat", now + 3600);
                    claims.put("exp", now + 3900);
                }
                case "WrongAudience" -> claims.set("aud", Templates.JSON.createArrayNode()
                        .add("https://not-this-storage.invalid/"));
                case "MultipleAudiences" -> claims.set("aud", Templates.JSON.createArrayNode()
                        .add(realm).add("https://another-storage.invalid/"));
                case "WrongIssuer" -> claims.put("iss", "https://untrusted-issuer.invalid/");
                default -> throw Unresolvable.cantTell("fault " + fault + " does not apply to an access token");
            }
        }
        return Jwts.sign(header, claims, key);
    }

    /**
     * Expired without the signing key: hold a real token until its exp plus 60 s of clock skew
     * has passed, when that is at most 600 s away (5.2).
     */
    private String waitForExpiry(String basis, Scope scope) {
        String real = baseToken(basis, scope).orElseThrow(() -> Unresolvable.inapplicable(
                "Expired needs a real " + basis + " token, and the target gives none"));
        long exp;
        try {
            exp = Jwts.decode(real).claims().path("exp").asLong(0);
        } catch (IllegalArgumentException e) {
            throw Unresolvable.inapplicable(basis + "'s token is not a JWT, so its expiry cannot be awaited");
        }
        long wait = exp + 60 - Instant.now().getEpochSecond();
        if (exp == 0 || wait > 600) {
            throw Unresolvable.inapplicable("Expired needs " + HARNESS_ISSUED + ", or a " + basis
                    + " token that expires within 540 s");
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Unresolvable.cantTell("interrupted while waiting for a token to expire");
            }
        }
        return real;
    }

    /**
     * 5.2, item 2: a configured did:key credential exchanged at the token endpoint exactly as
     * the token-exchange tests do. Kept for the run, and renewed a minute before it expires.
     */
    private String exchange(String name, Scope scope) {
        Exchanged cached = exchanged.get(name);
        long now = Instant.now().getEpochSecond();
        if (cached != null && now < cached.refreshAfter()) {
            return cached.token();
        }
        ECKey key = pinnedOrFresh(name, null);
        String credential = mintJwt(identity("didkey"), key, Map.of("self.webid", TextNode.valueOf(DidKeys.did(key))),
                null, scope);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", TOKEN_EXCHANGE);
        form.put("resource", scope.resolve("as.realm").asText());
        form.put("subject_token", credential);
        form.put("subject_token_type", JWT_TOKEN_TYPE);
        String body = form(form);
        URI endpoint = URI.create(scope.resolve("as.tokenEndpoint").asText());
        Req req = new Req("POST", endpoint, List.of(
                Http.header("Content-Type", "application/x-www-form-urlencoded"),
                Http.header("Accept", "application/json")),
                body.getBytes(StandardCharsets.UTF_8), body);
        Resp resp;
        try {
            resp = run.send(req);
        } catch (IOException e) {
            throw Unresolvable.cantTell("token exchange for " + name + " failed: " + e);
        }
        JsonNode json;
        try {
            json = Templates.JSON.readTree(resp.body());
        } catch (IOException e) {
            json = null;
        }
        if (resp.status() != 200 || json == null || !json.path("access_token").isTextual()) {
            throw Unresolvable.inapplicable("token exchange for " + name + " at " + endpoint + " answered "
                    + resp.status() + " without an access_token");
        }
        String token = json.path("access_token").asText();
        long expiresIn = json.path("expires_in").asLong(300);
        exchanged.put(name, new Exchanged(token, now + Math.max(0, expiresIn - 60)));
        return token;
    }

    // ------------------------------------------------------------------ subject credentials (5.3)

    /** {@code ${credential.<name>}}: the exact string presented as {@code subject_token}. */
    String subjectCredential(String name, Scope scope) {
        IdentityDefinition id = identity(name);
        if (!id.kind().equals(IdentityDefinition.SUBJECT_CREDENTIAL)) {
            throw Unresolvable.cantTell(name + " is not a SubjectCredential identity");
        }
        IdentityDefinition base = id.basis() == null ? id : identity(id.basis());
        String fault = id.fault();
        Map<String, JsonNode> self = new HashMap<>();
        switch (suite(base)) {
            case "didkey" -> {
                ECKey key = didkeyKey.get();
                self.put("self.webid", TextNode.valueOf(DidKeys.did(key)));
                return mintJwt(base, key, self, fault, scope);
            }
            case "cid" -> {
                ECKey key = cidKey.get();
                self.put("self.webid", TextNode.valueOf(webid(base.name(), scope)));
                self.put("self.kid", TextNode.valueOf(key.getKeyID()));
                self.put("self.publicJwk", publicJwk(key));
                return mintJwt(base, key, self, fault, scope);
            }
            case "oidc" -> {
                ECKey key = opKey.get();
                self.put("self.webid", TextNode.valueOf(webid(base.name(), scope)));
                self.put("self.kid", TextNode.valueOf(key.getKeyID()));
                return mintJwt(base, key, self, fault, scope);
            }
            default -> {
                Map<String, JsonNode> saml = Map.of("self.webid", TextNode.valueOf(webid(base.name(), scope)));
                JsonNode fields = Templates.expandJson(base.samlAssertion(), scope.withSelf(saml));
                RSAKey key = samlKey.get();
                try {
                    return SamlAssertions.mint(fields, base.algorithm(), key.toRSAPrivateKey(), key.toRSAPublicKey(),
                            samlCertificate(), fault);
                } catch (com.nimbusds.jose.JOSEException e) {
                    throw Unresolvable.inapplicable("saml.idpKey is not a usable RSA key: " + e.getMessage());
                }
            }
        }
    }

    /** A JWT credential from an identity's templates, with the fault vocab.yamlld defines applied. */
    private String mintJwt(IdentityDefinition base, ECKey key, Map<String, JsonNode> self, String fault, Scope scope) {
        Templates.Resolver resolver = scope.withSelf(self);
        ObjectNode header = (ObjectNode) Templates.expandJson(base.credentialHeader(), resolver);
        ObjectNode claims = (ObjectNode) Templates.expandJson(base.credentialClaims(), resolver);
        long now = Instant.now().getEpochSecond();
        JWK signer = key;
        if (fault != null) {
            switch (fault) {
                case "Expired" -> {
                    claims.put("iat", now - 3900);
                    claims.put("exp", now - 3600);
                }
                case "ClientIdMismatch" -> claims.put("client_id", "https://other-client.invalid/");
                case "AudienceExcludesAuthorizationServer" -> claims.set("aud", Templates.JSON.createArrayNode()
                        .add("https://not-the-authorization-server.invalid/"));
                case "MissingExpiration" -> claims.remove("exp");
                case "MissingIssuedAt" -> claims.remove("iat");
                case "MissingAuthorizedParty" -> claims.remove("azp");
                case "UntrustedIssuer" -> {
                    ECKey rogue = rogueOpKey.get();
                    claims.put("iss", scope.resolve("fixtures.baseUrl").asText() + "rogue-op");
                    header.put("kid", rogue.getKeyID());
                    signer = rogue;
                }
                case "SignatureCorrupted", "AlgNone", "UnknownKeyId" -> {
                    // applied to the signed token below
                }
                default -> throw Unresolvable.cantTell("fault " + fault + " does not apply to a JWT credential");
            }
        }
        String token = Jwts.sign(header, claims, signer);
        if (fault == null) {
            return token;
        }
        return switch (fault) {
            case "SignatureCorrupted" -> Jwts.corruptSignature(token);
            case "AlgNone" -> Jwts.algNone(token);
            case "UnknownKeyId" -> Jwts.resignWithUnknownKey(token);
            default -> token;
        };
    }

    // ------------------------------------------------------------------ fixture host documents

    /** The identity documents and OpenID Provider documents the fixture host serves, by path under its base. */
    Map<String, JsonNode> fixtureDocuments(Scope scope) {
        Map<String, JsonNode> docs = new LinkedHashMap<>();
        String base = run.fixturesBaseUrl();
        for (IdentityDefinition id : run.definitions().identities().values()) {
            if (id.basis() != null || id.identityDocument() == null || id.webid() == null) {
                continue;
            }
            String webid = webid(id.name(), scope);
            if (!webid.startsWith(base)) {
                continue;
            }
            Map<String, JsonNode> self = new HashMap<>();
            self.put("self.webid", TextNode.valueOf(webid));
            if (suite(id).equals("cid")) {
                ECKey key = cidKey.get();
                self.put("self.kid", TextNode.valueOf(key.getKeyID()));
                self.put("self.publicJwk", publicJwk(key));
            } else if (suite(id).equals("oidc")) {
                self.put("self.kid", TextNode.valueOf(opKey.get().getKeyID()));
            }
            docs.put(webid.substring(base.length()), Templates.expandJson(id.identityDocument(), scope.withSelf(self)));
        }
        provider(docs, base, "op", opKey.get());
        provider(docs, base, "rogue-op", rogueOpKey.get());
        return docs;
    }

    /** OpenID Connect Discovery and a JWKS for a harness OpenID Provider at {@code base + path}. */
    private static void provider(Map<String, JsonNode> docs, String base, String path, ECKey key) {
        ObjectNode discovery = Templates.JSON.createObjectNode();
        discovery.put("issuer", base + path);
        discovery.put("jwks_uri", base + path + "/jwks");
        discovery.put("authorization_endpoint", base + path + "/authorize");
        discovery.putArray("response_types_supported").add("id_token");
        discovery.putArray("subject_types_supported").add("public");
        discovery.putArray("id_token_signing_alg_values_supported").add("ES256");
        docs.put(path + "/.well-known/openid-configuration", discovery);
        ObjectNode jwks = Templates.JSON.createObjectNode();
        ((ArrayNode) jwks.putArray("keys")).add(publicJwk(key));
        docs.put(path + "/jwks", jwks);
    }

    private static JsonNode publicJwk(ECKey key) {
        try {
            ObjectNode jwk = (ObjectNode) Templates.JSON.readTree(key.toPublicJWK().toJSONString());
            jwk.put("alg", "ES256");
            return jwk;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ configuration

    private ECKey pinnedOrFresh(String name, String kid) {
        String jwk = target.properties().get("didkey.jwk." + name);
        if (jwk == null) {
            return Jwts.freshEc(kid);
        }
        try {
            JWK parsed = JWK.parse(jwk);
            if (!(parsed instanceof ECKey ec) || !ec.isPrivate()) {
                throw Unresolvable.inapplicable("didkey.jwk." + name + " is not a private P-256 JWK");
            }
            return ec;
        } catch (ParseException e) {
            throw Unresolvable.inapplicable("didkey.jwk." + name + " is not a JWK: " + e.getMessage());
        }
    }

    private JWK parseAsKey() {
        String jwk = target.properties().get("as.signingKey");
        if (jwk == null) {
            throw Unresolvable.inapplicable("the target declares " + HARNESS_ISSUED + " but gives no as.signingKey");
        }
        try {
            JWK key = JWK.parse(jwk);
            if (!key.isPrivate()) {
                throw Unresolvable.inapplicable("as.signingKey is a public key; minting needs the private one");
            }
            return key;
        } catch (ParseException e) {
            throw Unresolvable.inapplicable("as.signingKey is not a JWK: " + e.getMessage());
        }
    }

    private RSAKey parseSamlKey() {
        String jwk = target.properties().get("saml.idpKey");
        if (jwk == null) {
            throw Unresolvable.inapplicable("no saml.idpKey: the harness identity provider has no key the"
                    + " target trusts");
        }
        try {
            JWK key = JWK.parse(jwk);
            if (!(key instanceof RSAKey rsa) || !rsa.isPrivate()) {
                throw Unresolvable.inapplicable("saml.idpKey is not a private RSA JWK");
            }
            return rsa;
        } catch (ParseException e) {
            throw Unresolvable.inapplicable("saml.idpKey is not a JWK: " + e.getMessage());
        }
    }

    private X509Certificate samlCertificate() {
        String pem = target.properties().get("saml.idpCertificate");
        if (pem == null) {
            return null;
        }
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw Unresolvable.inapplicable("saml.idpCertificate is not a PEM certificate: " + e.getMessage());
        }
    }

    /** A target property, else the environment variable {@code prefix + NAME}. */
    private String setting(String property, String envPrefix) {
        String value = target.properties().get(property);
        if (value == null) {
            String name = property.substring(property.indexOf('.') + 1);
            value = System.getenv(envPrefix + envName(name));
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String envName(String name) {
        return name.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    static String form(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        fields.forEach((k, v) -> parts.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }
}
