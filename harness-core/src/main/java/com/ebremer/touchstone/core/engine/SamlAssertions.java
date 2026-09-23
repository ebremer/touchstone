package com.ebremer.touchstone.core.engine;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * SAML 2.0 assertions from the harness identity provider (EXECUTION.md section 5.3): built
 * from an identity's {@code samlAssertion} fields, signed with an enveloped XML-DSig
 * signature, and base64url-encoded as RFC 8693 section 3 requires of the saml2 token type.
 *
 * <p>The JDK's own XML Signature API (JSR 105) builds and signs them; OpenSAML would add a
 * dependency from outside Maven Central to do the same (DECISIONS.md D-0024, D-0054).
 */
final class SamlAssertions {

    static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    private SamlAssertions() {
    }

    /**
     * @param fields the expanded {@code samlAssertion} fields; instants are epoch seconds
     * @param fault  null, or SignatureCorrupted, Unsigned or Expired
     */
    static String mint(JsonNode fields, String algorithm, PrivateKey key, PublicKey publicKey,
                       X509Certificate certificate, String fault) {
        if (!RSA_SHA256.equals(algorithm)) {
            throw Unresolvable.inapplicable("the harness identity provider signs only with " + RSA_SHA256
                    + ", not " + algorithm);
        }
        try {
            long now = Instant.now().getEpochSecond();
            long notBefore = fields.path("notBefore").asLong(now);
            long notOnOrAfter = fields.path("notOnOrAfter").asLong(now + 300);
            if ("Expired".equals(fault)) {
                notBefore = now - 3900;
                notOnOrAfter = now - 3600;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = factory.newDocumentBuilder().newDocument();

            Element assertion = doc.createElementNS(SAML, "saml:Assertion");
            assertion.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:saml", SAML);
            String id = "_" + UUID.randomUUID();
            assertion.setAttribute("ID", id);
            assertion.setIdAttribute("ID", true);
            assertion.setAttribute("Version", "2.0");
            assertion.setAttribute("IssueInstant", instant(notBefore));
            doc.appendChild(assertion);

            child(assertion, "saml:Issuer").setTextContent(fields.path("issuer").asText());
            Element subject = child(assertion, "saml:Subject");
            Element nameId = child(subject, "saml:NameID");
            nameId.setAttribute("Format", fields.path("nameIdFormat").asText());
            nameId.setTextContent(fields.path("nameId").asText());
            Element confirmation = child(subject, "saml:SubjectConfirmation");
            confirmation.setAttribute("Method", "urn:oasis:names:tc:SAML:2.0:cm:bearer");
            Element data = child(confirmation, "saml:SubjectConfirmationData");
            data.setAttribute("NotOnOrAfter", instant(notOnOrAfter));
            data.setAttribute("Recipient", fields.path("recipient").asText());

            Element conditions = child(assertion, "saml:Conditions");
            conditions.setAttribute("NotBefore", instant(notBefore));
            conditions.setAttribute("NotOnOrAfter", instant(notOnOrAfter));
            Element restriction = child(conditions, "saml:AudienceRestriction");
            JsonNode audiences = fields.path("audience");
            for (JsonNode audience : audiences.isArray() ? audiences : List.of(audiences)) {
                child(restriction, "saml:Audience").setTextContent(audience.asText());
            }
            Element statement = child(assertion, "saml:AuthnStatement");
            statement.setAttribute("AuthnInstant", instant(notBefore));
            child(child(statement, "saml:AuthnContext"), "saml:AuthnContextClassRef")
                    .setTextContent("urn:oasis:names:tc:SAML:2.0:ac:classes:unspecified");

            if (!"Unsigned".equals(fault)) {
                sign(assertion, id, subject, key, publicKey, certificate);
            }
            if ("SignatureCorrupted".equals(fault)) {
                // Altered after signing, so the reference digest no longer matches.
                nameId.setTextContent(nameId.getTextContent() + "#altered");
            }
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(serialize(doc).getBytes(StandardCharsets.UTF_8));
        } catch (Unresolvable e) {
            throw e;
        } catch (Exception e) {
            throw Unresolvable.cantTell("cannot build a SAML assertion: " + e);
        }
    }

    private static void sign(Element assertion, String id, Element before, PrivateKey key, PublicKey publicKey,
                             X509Certificate certificate) throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        Reference ref = fac.newReference("#" + id,
                fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = fac.newSignedInfo(
                fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(ref));
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        KeyInfo keyInfo = certificate != null
                ? kif.newKeyInfo(List.of(kif.newX509Data(List.of(certificate))))
                : kif.newKeyInfo(List.of(kif.newKeyValue(publicKey)));
        // SAML's schema puts the signature right after the Issuer, before the Subject.
        DOMSignContext context = new DOMSignContext(key, assertion, before);
        context.setDefaultNamespacePrefix("ds");
        fac.newXMLSignature(signedInfo, keyInfo).sign(context);
    }

    private static Element child(Element parent, String name) {
        Element e = parent.getOwnerDocument().createElementNS(SAML, name);
        parent.appendChild(e);
        return e;
    }

    private static String instant(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).toString();
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }
}
