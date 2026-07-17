package com.ebremer.touchstone.fixtures.ssi;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * Hosts controlled identifier documents for the CID suite (DESIGN.md paragraph 1: the
 * harness owns the identity documents the SUT dereferences). A subject URL served here
 * resolves to a CID document that names the verification key; the CID verifier fetches it.
 */
public final class IdentityDocumentHost implements AutoCloseable {

    private final Server server;
    private final ServerConnector connector;
    private final ConcurrentMap<String, String> documents = new ConcurrentHashMap<>();

    private IdentityDocumentHost() {
        this.server = new Server();
        this.connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new DocumentHandler());
    }

    public static IdentityDocumentHost start(int port) {
        IdentityDocumentHost host = new IdentityDocumentHost();
        host.connector.setPort(port);
        try {
            host.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start identity-document host", e);
        }
        return host;
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + connector.getLocalPort() + "/");
    }

    /**
     * Registers a subject that resolves to a CID document naming {@code key} as its
     * verification method, and returns the subject URL (also the document's {@code id}).
     */
    public String hostControlledIdentifier(String path, DidKey key) {
        String subject = baseUri().resolve(path).toString();
        String vmId = subject + "#key-1";
        String doc = "{"
                + "\"@context\":[\"https://www.w3.org/ns/cid/v1\"],"
                + "\"id\":\"" + subject + "\","
                + "\"verificationMethod\":[{"
                + "\"id\":\"" + vmId + "\","
                + "\"type\":\"Multikey\","
                + "\"controller\":\"" + subject + "\","
                + "\"publicKeyMultibase\":\"" + DidKey.multibaseKey(key.publicKey().getEncoded()) + "\""
                + "}]}";
        documents.put(URI.create(subject).getPath(), doc);
        return subject;
    }

    /** The verification-method id (kid) a credential must reference for {@code subject}. */
    public static String verificationMethodId(String subject) {
        return subject + "#key-1";
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class DocumentHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            String doc = documents.get(request.getHttpURI().getCanonicalPath());
            if (doc == null) {
                response.setStatus(404);
                callback.succeeded();
                return true;
            }
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/cid+json");
            response.write(true, ByteBuffer.wrap(doc.getBytes(StandardCharsets.UTF_8)), callback);
            return true;
        }
    }
}
