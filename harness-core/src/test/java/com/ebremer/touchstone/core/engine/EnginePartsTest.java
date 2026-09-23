package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The engine's parsers and matchers, each against the rule of EXECUTION.md it implements. */
class EnginePartsTest {

    // ------------------------------------------------------------------ RFC 9110 challenges (7.6)

    @Test
    void challengeParamsAreFoundInAnyOrder() {
        List<Challenges.Challenge> cs = Challenges.parse(List.of(
                "Bearer realm=\"https://s/\", as_uri=\"https://as/\", error=\"invalid_token\""));
        assertThat(cs).hasSize(1);
        assertThat(cs.getFirst().scheme()).isEqualTo("Bearer");
        assertThat(cs.getFirst().param("AS_URI")).isEqualTo("https://as/");
        assertThat(cs.getFirst().param("realm")).isEqualTo("https://s/");
    }

    @Test
    void severalChallengesInOneFieldAreToldApart() {
        List<Challenges.Challenge> cs = Challenges.parse(List.of(
                "Newauth realm=\"apps\", type=1, title=\"Login to \\\"apps\\\", please\", Basic realm=\"simple\"",
                "Negotiate abc123==, Bearer as_uri=\"https://as/\",realm=r,error=invalid_token"));
        assertThat(cs).extracting(Challenges.Challenge::scheme)
                .containsExactly("Newauth", "Basic", "Negotiate", "Bearer");
        assertThat(cs.get(0).param("title")).isEqualTo("Login to \"apps\", please");
        assertThat(cs.get(0).param("type")).isEqualTo("1");
        assertThat(cs.get(2).token68()).isEqualTo("abc123==");
        assertThat(cs.get(3).param("as_uri")).isEqualTo("https://as/");
        assertThat(cs.get(3).param("realm")).isEqualTo("r");
        assertThat(cs.get(3).param("error")).isEqualTo("invalid_token");
    }

    @Test
    void anUnquotedUriIsNotAnAuthParam() {
        // ':' and '/' are not tchar, so RFC 9110 needs a URI quoted. A server that does not
        // quote one has sent no as_uri parameter at all, and the challenge test says so.
        Challenges.Challenge c = Challenges.parse(List.of("Bearer as_uri=https://as/")).getFirst();
        assertThat(c.param("as_uri")).isNull();
    }

    // ------------------------------------------------------------------ RFC 8288 links (7.4)

    @Test
    void linksAreParsedFromEveryFieldAndResolved() {
        URI base = URI.create("https://s/c/r");
        List<LinkValues.Link> links = LinkValues.parse(List.of(
                "<../>; rel=\"up\", <https://s/>; rel=\"https://www.w3.org/ns/lws#storage\"",
                "<r.meta>; rel=\"linkset alternate\"; type=\"application/linkset+json\"",
                "<https://elsewhere/>; rel=\"up\"; anchor=\"https://other/\""), base);
        assertThat(links).hasSize(3);
        assertThat(links.get(0).target()).isEqualTo("https://s/");
        assertThat(links.get(0).hasRel("UP")).isTrue();
        assertThat(links.get(1).hasRel("https://www.w3.org/ns/lws#storage")).isTrue();
        assertThat(links.get(1).hasRel("https://www.w3.org/ns/LWS#storage")).isFalse();
        assertThat(links.get(2).hasRel("alternate")).isTrue();
        assertThat(links.get(2).param("type")).isEqualTo("application/linkset+json");
        assertThat(links.get(2).target()).isEqualTo("https://s/c/r.meta");
    }

    @Test
    void requestLinksAreSerializedAsSection62Says() {
        List<String[]> links = new ArrayList<>();
        links.add(new String[] {"https://www.w3.org/ns/lws#Container", "type", null});
        links.add(new String[] {"https://x/", "describedby", "text/turtle"});
        assertThat(LinkValues.serialize(links)).isEqualTo(
                "<https://www.w3.org/ns/lws#Container>; rel=\"type\", <https://x/>; rel=\"describedby\"; type=\"text/turtle\"");
    }

    // ------------------------------------------------------------------ templates (3)

    @Test
    void aWholeExpressionTakesItsJsonValue() {
        Templates.Resolver r = e -> e.equals("n") ? LongNode.valueOf(42) : TextNode.valueOf("v:" + e);
        JsonNode out = Templates.expandJson(json("{\"a\":\"${n}\",\"b\":\"x${n}y\",\"c\":[\"${s}\"]}"), r);
        assertThat(out.toString()).isEqualTo("{\"a\":42,\"b\":\"x42y\",\"c\":[\"v:s\"]}");
        assertThat(Templates.expand("${a}/${b}", r)).isEqualTo("v:a/v:b");
    }

    // ------------------------------------------------------------------ json expectations (7.8)

    @Test
    void jsonExpectationsMatchByContent() {
        JsonNode body = json("{\"items\":[{\"id\":\"a\",\"type\":\"DataResource\",\"n\":1},"
                + "{\"id\":\"b/\",\"type\":[\"Container\",\"x\"],\"n\":2.0}],\"total\":2}");
        assertThat(passes(body, "[{\"pointer\":\"/items\",\"some\":[{\"pointer\":\"/id\",\"equals\":\"b/\"},"
                + "{\"pointer\":\"/type\",\"hasValue\":\"Container\"}]}]")).isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/items\",\"every\":[{\"pointer\":\"/n\",\"jsonType\":\"number\"}]}]"))
                .isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/items\",\"none\":[{\"pointer\":\"/id\",\"equals\":\"c\"}]}]")).isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/items/1/n\",\"equals\":2}]")).as("numbers compare numerically").isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/items\",\"count\":2}]")).isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/missing\",\"optional\":true,\"equals\":1}]")).isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/missing\",\"exists\":false}]")).isTrue();
        assertThat(passes(body, "[{\"pointer\":\"/total\",\"exists\":false}]")).isFalse();
        assertThat(passes(body, "[{\"pointer\":\"/missing\",\"equals\":1}]")).isFalse();
        assertThat(passes(body, "[{\"pointer\":\"/items\",\"some\":[{\"pointer\":\"/id\",\"equals\":\"z\"}]}]")).isFalse();
    }

    @Test
    void equalsIgnoresMemberOrderAndCapturesBind() {
        JsonNode body = json("{\"o\":{\"b\":2,\"a\":1},\"s\":\"text\",\"t\":{\"k\":true}}");
        Map<String, String> captured = new HashMap<>();
        List<AssertionResult> results = new ArrayList<>();
        boolean ok = JsonChecks.check("json", body, json("[{\"pointer\":\"/o\",\"equals\":{\"a\":1,\"b\":2}},"
                + "{\"pointer\":\"/s\",\"capture\":\"s\"},{\"pointer\":\"/t\",\"capture\":\"t\"}]"),
                e -> TextNode.valueOf(e), URI.create("https://s/"), captured::put, results);
        assertThat(ok).isTrue();
        assertThat(captured).containsEntry("s", "text").containsEntry("t", "{\"k\":true}");
    }

    // ------------------------------------------------------------------ credentials (5)

    @Test
    void aP256DidKeyIsMulticodecP256PubInBase58btc() {
        ECKey key = Jwts.freshEc("k");
        String did = DidKeys.did(key);
        assertThat(did).startsWith("did:key:zDn");
        assertThat(did.length()).isBetween(55, 60);
    }

    @Test
    void theThreeSignatureFaultsNeedNoSigningKey() {
        ECKey key = Jwts.freshEc("k1");
        String token = Jwts.sign(json("{\"alg\":\"ES256\",\"kid\":\"k1\"}"), json("{\"sub\":\"a\"}"), key);
        assertThat(Jwts.verify(token, key)).isTrue();

        assertThat(Jwts.verify(Jwts.corruptSignature(token), key)).isFalse();
        String none = Jwts.algNone(token);
        assertThat(none).endsWith(".");
        assertThat(Jwts.decode(none).header().path("alg").asText()).isEqualTo("none");
        assertThat(Jwts.decode(none).claims()).isEqualTo(Jwts.decode(token).claims());
        String unknown = Jwts.resignWithUnknownKey(token);
        assertThat(Jwts.decode(unknown).header().path("kid").asText()).isEqualTo(Jwts.UNKNOWN_KID);
        assertThat(Jwts.verify(unknown, key)).isFalse();
    }

    @Test
    void samlAssertionsAreSignedAndTheirFaultsBreakTheSignature() throws Exception {
        RSAKey key = Jwts.freshRsa("idp");
        JsonNode fields = json("{\"issuer\":\"https://idp/\",\"nameId\":\"https://agent/\",\"nameIdFormat\":"
                + "\"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\",\"recipient\":\"https://client/\","
                + "\"audience\":[\"https://client/\",\"https://as/\"],\"notBefore\":1790000000,\"notOnOrAfter\":1790000300}");
        String valid = SamlAssertions.mint(fields, SamlAssertions.RSA_SHA256, key.toRSAPrivateKey(),
                key.toRSAPublicKey(), null, null);
        assertThat(validates(valid, key)).isTrue();
        assertThat(xml(valid)).contains("<saml:Audience>https://as/</saml:Audience>")
                .contains("NotOnOrAfter=\"2026-09-21T");
        assertThat(validates(SamlAssertions.mint(fields, SamlAssertions.RSA_SHA256, key.toRSAPrivateKey(),
                key.toRSAPublicKey(), null, "SignatureCorrupted"), key)).isFalse();
        assertThat(xml(SamlAssertions.mint(fields, SamlAssertions.RSA_SHA256, key.toRSAPrivateKey(),
                key.toRSAPublicKey(), null, "Unsigned"))).doesNotContain("Signature");
    }

    private static boolean validates(String encoded, RSAKey key) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(
                Base64.getUrlDecoder().decode(encoded)));
        Element assertion = doc.getDocumentElement();
        assertion.setIdAttribute("ID", true);
        NodeList sig = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (sig.getLength() != 1) {
            return false;
        }
        DOMValidateContext ctx = new DOMValidateContext(key.toRSAPublicKey(), sig.item(0));
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
    }

    private static String xml(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static boolean passes(JsonNode body, String expectations) {
        return JsonChecks.check("json", body, json(expectations), e -> TextNode.valueOf(e),
                URI.create("https://s/"), (k, v) -> { }, new ArrayList<>());
    }

    private static JsonNode json(String s) {
        try {
            return Templates.JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
