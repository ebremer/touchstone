package com.ebremer.touchstone.fixtures.as;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import com.ebremer.touchstone.fixtures.ssi.Base58;
import com.ebremer.touchstone.fixtures.ssi.DidKey;
import com.ebremer.touchstone.fixtures.ssi.SelfIssuedJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * How the reference authorization server validates a subject token before it issues anything
 * (core WD section 5.2.3, "validate before issuing"): the did:key and CID self-issued JWTs, the
 * OpenID Connect ID Token and the SAML 2.0 assertion, each the way its suite says. Written
 * apart from the harness's own credential code on purpose: a reference that shared the
 * harness's encoder would share its bugs, and the two would cancel out.
 */
final class SubjectTokens {

    static final String JWT = "urn:ietf:params:oauth:token-type:jwt";
    static final String ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token";
    static final String SAML2 = "urn:ietf:params:oauth:token-type:saml2";
    private static final String LWS = "https://www.w3.org/ns/lws#";
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final long SKEW = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A token that must not be exchanged; the message goes into {@code error_description}. */
    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message);
        }
    }

    /** Who a valid subject token authenticates, and the client presenting it. */
    record Subject(String subject, String clientId) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<String, PublicKey> samlIdps;

    SubjectTokens(Map<String, PublicKey> samlIdps) {
        this.samlIdps = samlIdps;
    }

    Subject verify(String token, String type, String audience) throws Invalid {
        return switch (type) {
            case JWT -> {
                SignedJWT jwt = parse(token);
                String sub = claims(jwt).getSubject();
                yield sub != null && sub.startsWith("did:key:") ? didKey(token, jwt, audience) : cid(jwt, audience);
            }
            case ID_TOKEN -> oidc(parse(token), audience);
            case SAML2 -> saml(token, audience);
            default -> throw new Invalid("unsupported subject_token_type " + type);
        };
    }

    /** The broken twin's "validation": read the subject and believe it. */
    Subject unverified(String token, String type) {
        try {
            if (type.equals(SAML2)) {
                Document doc = xml(token);
                Node nameId = doc.getElementsByTagNameNS(SAML, "NameID").item(0);
                return new Subject(nameId == null ? "anonymous" : nameId.getTextContent().trim(), "unknown");
            }
            String[] parts = token.split("\\.", -1);
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return new Subject(claims.path("sub").asText("anonymous"), claims.path("client_id").asText("unknown"));
        } catch (Exception e) {
            return new Subject("anonymous", "unknown");
        }
    }

    // ------------------------------------------------------------------ did:key

    private Subject didKey(String token, SignedJWT jwt, String audience) throws Invalid {
        JWTClaimsSet c = selfIssued(jwt, audience);
        String did = c.getSubject();
        byte[] decoded = Base58.decode(did.substring("did:key:z".length()));
        if (decoded.length == 35 && (decoded[0] & 0xff) == 0x80 && decoded[1] == 0x24) {
            ECKey key = decompressP256(Arrays.copyOfRange(decoded, 2, 35));
            requireAlgorithm(jwt, JWSAlgorithm.ES256);
            verifyEc(jwt, key);
        } else if (decoded.length == 34 && (decoded[0] & 0xff) == 0xed && decoded[1] == 0x01) {
            SelfIssuedJwt.Parsed parsed = SelfIssuedJwt.parse(token);
            if (!SelfIssuedJwt.verifyEdDsa(parsed, DidKey.publicKeyFromDid(did))) {
                throw new Invalid("the signature does not verify with the key the did:key names");
            }
        } else {
            throw new Invalid("the did:key names neither a P-256 nor an Ed25519 key");
        }
        return new Subject(did, did);
    }

    /** SEC 1 point decompression on P-256: y from y^2 = x^3 - 3x + b, with the parity the prefix gives. */
    static ECKey decompressP256(byte[] compressed) throws Invalid {
        if (compressed.length != 33 || (compressed[0] != 2 && compressed[0] != 3)) {
            throw new Invalid("not a compressed P-256 point");
        }
        BigInteger p = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
        BigInteger b = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(compressed, 1, 33));
        BigInteger rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(b).mod(p);
        BigInteger y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p);
        if (!y.modPow(BigInteger.TWO, p).equals(rhs)) {
            throw new Invalid("the point is not on P-256");
        }
        if (y.testBit(0) != (compressed[0] == 3)) {
            y = p.subtract(y);
        }
        return new ECKey.Builder(Curve.P_256, Base64URL.encode(unsigned(x)), Base64URL.encode(unsigned(y))).build();
    }

    private static byte[] unsigned(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    // ------------------------------------------------------------------ CID

    private Subject cid(SignedJWT jwt, String audience) throws Invalid {
        JWTClaimsSet c = selfIssued(jwt, audience);
        String subject = c.getSubject();
        String kid = jwt.getHeader().getKeyID();
        if (kid == null) {
            throw new Invalid("a CID credential names its verification method in kid, and this one has none");
        }
        String methodId = kid.contains(":") ? kid : subject + "#" + kid;
        JsonNode doc = fetch(subject, "application/ld+json, application/cid+json, application/json");
        if (!subject.equals(doc.path("id").asText())) {
            throw new Invalid("the controlled identifier document at " + subject + " names another id");
        }
        JsonNode method = method(doc, methodId);
        if (method == null) {
            throw new Invalid("the controlled identifier document names no verification method " + methodId);
        }
        JWK key;
        try {
            key = JWK.parse(method.path("publicKeyJwk").toString());
        } catch (Exception e) {
            throw new Invalid("verification method " + methodId + " carries no usable publicKeyJwk");
        }
        if (!(key instanceof ECKey ec)) {
            throw new Invalid("verification method " + methodId + " is not a P-256 key");
        }
        requireAlgorithm(jwt, JWSAlgorithm.ES256);
        verifyEc(jwt, ec);
        return new Subject(subject, subject);
    }

    private static JsonNode method(JsonNode doc, String id) {
        for (String relation : List.of("authentication", "verificationMethod")) {
            for (JsonNode m : doc.path(relation)) {
                if (m.isObject() && id.equals(m.path("id").asText())) {
                    return m;
                }
            }
        }
        return null;
    }

    /** sub = iss = client_id, aud includes this server, exp in the future, iat present and not ahead. */
    private static JWTClaimsSet selfIssued(SignedJWT jwt, String audience) throws Invalid {
        JWTClaimsSet c = claims(jwt);
        String sub = c.getSubject();
        Object clientId = c.getClaim("client_id");
        if (sub == null || !sub.equals(c.getIssuer()) || !sub.equals(clientId)) {
            throw new Invalid("sub, iss and client_id must be the same identifier");
        }
        times(c);
        if (c.getAudience() == null || !c.getAudience().contains(audience)) {
            throw new Invalid("aud does not include this authorization server");
        }
        return c;
    }

    // ------------------------------------------------------------------ OpenID Connect

    private Subject oidc(SignedJWT jwt, String audience) throws Invalid {
        JWTClaimsSet c = claims(jwt);
        String issuer = c.getIssuer();
        String subject = c.getSubject();
        if (issuer == null || subject == null) {
            throw new Invalid("an ID Token needs iss and sub");
        }
        Object azp = c.getClaim("azp");
        if (!(azp instanceof String)) {
            throw new Invalid("the ID Token names no azp, so the client it was issued to is unknown");
        }
        times(c);
        if (c.getAudience() == null || !c.getAudience().contains(audience)) {
            throw new Invalid("aud does not include this authorization server");
        }
        JsonNode doc = fetch(subject, "application/ld+json, application/cid+json, application/json");
        boolean named = false;
        for (JsonNode service : doc.path("service")) {
            if (hasType(service.path("type"), "OpenIdProvider") && issuer.equals(service.path("serviceEndpoint").asText())) {
                named = true;
            }
        }
        if (!named) {
            throw new Invalid("the subject's controlled identifier document names no OpenID Provider " + issuer);
        }
        JsonNode discovery = fetch(issuer.replaceAll("/$", "") + "/.well-known/openid-configuration", "application/json");
        if (!issuer.equals(discovery.path("issuer").asText())) {
            throw new Invalid("the OpenID Provider's discovery document names another issuer");
        }
        JWKSet keys;
        try {
            keys = JWKSet.parse(fetchText(discovery.path("jwks_uri").asText(), "application/json"));
        } catch (java.text.ParseException e) {
            throw new Invalid("the OpenID Provider's JWKS does not parse");
        }
        JWK key = keys.getKeyByKeyId(jwt.getHeader().getKeyID());
        if (key == null) {
            throw new Invalid("the OpenID Provider publishes no key " + jwt.getHeader().getKeyID());
        }
        if (key instanceof ECKey ec) {
            verifyEc(jwt, ec);
        } else if (key instanceof RSAKey rsa) {
            try {
                if (!jwt.verify(new RSASSAVerifier(rsa))) {
                    throw new Invalid("the ID Token's signature does not verify");
                }
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new Invalid("the ID Token's signature cannot be checked: " + e.getMessage());
            }
        } else {
            throw new Invalid("unsupported key type " + key.getKeyType());
        }
        return new Subject(subject, (String) azp);
    }

    private static boolean hasType(JsonNode type, String wanted) {
        for (JsonNode t : type.isArray() ? type : List.of(type)) {
            if (t.asText().equals(wanted) || t.asText().equals(LWS + wanted)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ SAML 2.0

    private Subject saml(String token, String audience) throws Invalid {
        Document doc;
        try {
            doc = xml(token);
        } catch (Exception e) {
            throw new Invalid("the subject token is not a base64url-encoded XML document");
        }
        Element assertion = doc.getDocumentElement();
        if (!SAML.equals(assertion.getNamespaceURI()) || !"Assertion".equals(assertion.getLocalName())) {
            throw new Invalid("the subject token is not a saml:Assertion");
        }
        String issuer = text(assertion, "Issuer");
        PublicKey key = issuer == null ? null : samlIdps.get(issuer);
        if (key == null) {
            throw new Invalid("the assertion's issuer " + issuer + " is not a trusted identity provider");
        }
        Element signature = null;
        for (Node n = assertion.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && XMLSignature.XMLNS.equals(e.getNamespaceURI()) && "Signature".equals(e.getLocalName())) {
                signature = e;
            }
        }
        if (signature == null) {
            throw new Invalid("the assertion is not signed");
        }
        String id = assertion.getAttribute("ID");
        assertion.setIdAttribute("ID", true);
        try {
            DOMValidateContext context = new DOMValidateContext(key, signature);
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature xmlSignature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            List<?> references = xmlSignature.getSignedInfo().getReferences();
            // The signature must cover this assertion and nothing else, or a signed element
            // could be wrapped around an unsigned one.
            if (references.size() != 1 || !("#" + id).equals(((Reference) references.getFirst()).getURI())) {
                throw new Invalid("the signature does not reference the assertion");
            }
            if (!xmlSignature.validate(context)) {
                throw new Invalid("the assertion's signature does not verify");
            }
        } catch (Invalid e) {
            throw e;
        } catch (Exception e) {
            throw new Invalid("the assertion's signature cannot be checked: " + e.getMessage());
        }
        Element conditions = child(assertion, "Conditions");
        long now = Instant.now().getEpochSecond();
        if (conditions == null) {
            throw new Invalid("the assertion has no Conditions");
        }
        if (instant(conditions.getAttribute("NotBefore")) > now + SKEW
                || instant(conditions.getAttribute("NotOnOrAfter")) <= now - SKEW) {
            throw new Invalid("the assertion is outside its validity period");
        }
        List<String> audiences = new ArrayList<>();
        NodeList list = conditions.getElementsByTagNameNS(SAML, "Audience");
        for (int i = 0; i < list.getLength(); i++) {
            audiences.add(list.item(i).getTextContent().trim());
        }
        if (!audiences.contains(audience)) {
            throw new Invalid("the assertion's audience does not include this authorization server");
        }
        Element subject = child(assertion, "Subject");
        String nameId = subject == null ? null : text(subject, "NameID");
        if (nameId == null || nameId.isEmpty()) {
            throw new Invalid("the assertion names no subject");
        }
        NodeList data = assertion.getElementsByTagNameNS(SAML, "SubjectConfirmationData");
        String recipient = data.getLength() == 0 ? issuer : ((Element) data.item(0)).getAttribute("Recipient");
        return new Subject(nameId, recipient.isEmpty() ? issuer : recipient);
    }

    private static Document xml(String token) throws Exception {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            bytes = Base64.getDecoder().decode(token);
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && SAML.equals(e.getNamespaceURI()) && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element parent, String localName) {
        Element e = child(parent, localName);
        return e == null ? null : e.getTextContent().trim();
    }

    private static long instant(String value) throws Invalid {
        try {
            return Instant.parse(value).getEpochSecond();
        } catch (Exception e) {
            throw new Invalid("not an xsd:dateTime: " + value);
        }
    }

    // ------------------------------------------------------------------ shared

    private static SignedJWT parse(String token) throws Invalid {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (JWSAlgorithm.NONE.getName().equals(jwt.getHeader().getAlgorithm().getName())) {
                throw new Invalid("alg none is refused");
            }
            return jwt;
        } catch (java.text.ParseException e) {
            // An unsecured JWT (alg none) does not parse as a signed one.
            throw new Invalid("not a signed JWT: " + e.getMessage());
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) throws Invalid {
        try {
            return jwt.getJWTClaimsSet();
        } catch (java.text.ParseException e) {
            throw new Invalid("the JWT's claims do not parse");
        }
    }

    /** exp present and in the future; iat present and not ahead of now. */
    private static void times(JWTClaimsSet c) throws Invalid {
        Instant now = Instant.now();
        if (c.getExpirationTime() == null) {
            throw new Invalid("exp is required");
        }
        if (!c.getExpirationTime().toInstant().isAfter(now.minusSeconds(SKEW))) {
            throw new Invalid("the credential has expired");
        }
        if (c.getIssueTime() == null) {
            throw new Invalid("iat is required");
        }
        if (c.getIssueTime().toInstant().isAfter(now.plusSeconds(SKEW))) {
            throw new Invalid("iat lies in the future");
        }
        if (c.getNotBeforeTime() != null && c.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(SKEW))) {
            throw new Invalid("the credential is not valid yet");
        }
    }

    private static void requireAlgorithm(SignedJWT jwt, JWSAlgorithm alg) throws Invalid {
        if (!alg.equals(jwt.getHeader().getAlgorithm())) {
            throw new Invalid("the credential is signed with " + jwt.getHeader().getAlgorithm() + ", not " + alg);
        }
    }

    private static void verifyEc(SignedJWT jwt, ECKey key) throws Invalid {
        try {
            if (!jwt.verify(new ECDSAVerifier(key))) {
                throw new Invalid("the signature does not verify");
            }
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new Invalid("the signature cannot be checked: " + e.getMessage());
        }
    }

    private JsonNode fetch(String url, String accept) throws Invalid {
        try {
            return JSON.readTree(fetchText(url, accept));
        } catch (Invalid e) {
            throw e;
        } catch (Exception e) {
            throw new Invalid(url + " is not JSON");
        }
    }

    private String fetchText(String url, String accept) throws Invalid {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new Invalid("not a URL: " + url);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new Invalid("not an http(s) URL: " + url);
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).header("Accept", accept)
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new Invalid("GET " + url + " answered " + r.statusCode());
            }
            return r.body();
        } catch (Invalid e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Invalid("interrupted");
        } catch (Exception e) {
            throw new Invalid("cannot dereference " + url + ": " + e.getMessage());
        }
    }
}
