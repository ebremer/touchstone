package com.ebremer.touchstone.fixtures.client;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * The reference client's inbox (CLIENT-TESTING.md section 9): it takes webhook notifications at
 * {@code /inbox/{name}} and verifies each as lws10-notifications-webhook section 5.2 says, before
 * it acknowledges it with a 204:
 * <ol>
 *   <li>the keyid from Signature-Input is a URL with a fragment;</li>
 *   <li>without the fragment, it is the storage identifier;</li>
 *   <li>the document it dereferences to has that identifier as its top-level id;</li>
 *   <li>its verificationMethod with the keyid, or the fragment, as id is the key;</li>
 *   <li>the HTTP Message Signature verifies with that key, over a base rebuilt from the inbox URL
 *       as subscribed, and the body has the Content-Digest the signature covers (RFC 9530).</li>
 * </ol>
 * Anything else is refused with a 401. With an inbox flaw it is a broken twin that skips one
 * check, accepts everything, or refuses everything. It is written apart from the storage's signer
 * and from the harness's own verifier, so that a bug in either shows.
 */
public final class RefInbox implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Flaw flaw;
    private final Server server;
    private final ServerConnector connector;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private int received;

    private RefInbox(Flaw flaw) {
        this.flaw = flaw;
        this.server = new Server();
        this.connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        server.setHandler(new Receiver());
    }

    /** An inbox on a free loopback port. */
    public static RefInbox start(Flaw flaw) {
        RefInbox inbox = new RefInbox(flaw);
        try {
            inbox.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the reference inbox", e);
        }
        return inbox;
    }

    /** The URL of the inbox named {@code name}, as a client gives it in a subscription. */
    public URI uri(String name) {
        return URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/inbox/" + name);
    }

    /** Notifications received so far, accepted or refused. */
    public synchronized int received() {
        return received;
    }

    /** Waits until {@code count} notifications have arrived; false when {@code timeout} passes first. */
    public synchronized boolean awaitReceived(int count, Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (received < count) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            wait(Math.max(1, left / 1_000_000));
        }
        return true;
    }

    private synchronized void arrived() {
        received++;
        notifyAll();
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class Receiver extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getCanonicalPath();
            if (path == null || !path.startsWith("/inbox/") || !request.getMethod().equals("POST")) {
                response.setStatus(404);
                callback.succeeded();
                return true;
            }
            byte[] body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readNBytes(1 << 20);
            }
            String problem;
            try {
                problem = flaw == Flaw.INBOX_ACCEPTS_EVERYTHING ? null
                        : flaw == Flaw.INBOX_REFUSES_EVERYTHING ? "this inbox refuses everything"
                        : verify(uri(path.substring("/inbox/".length())), request, body);
            } catch (Exception e) {
                problem = "cannot verify: " + e.getMessage();
            }
            arrived();
            if (problem == null) {
                response.setStatus(204);
                callback.succeeded();
            } else {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                response.write(true, ByteBuffer.wrap(problem.getBytes(StandardCharsets.UTF_8)), callback);
            }
            return true;
        }
    }

    /** Why the notification fails verification, or null when it passes. */
    private String verify(URI inbox, Request request, byte[] body) throws Exception {
        String input = request.getHeaders().get("Signature-Input");
        String signature = request.getHeaders().get("Signature");
        if (input == null || signature == null) {
            return "the notification is not signed";
        }
        // One signature, labelled alike in both fields: label=("c1" "c2" ...);params and label=:base64:
        String label = input.substring(0, input.indexOf('=')).trim();
        String params = input.substring(input.indexOf('=') + 1).trim();
        if (!signature.trim().startsWith(label + "=:")) {
            return "Signature and Signature-Input do not share a label";
        }
        String sig = signature.trim().substring(label.length() + 2);
        sig = sig.substring(0, sig.indexOf(':'));
        String keyid = parameter(params, "keyid");
        if (keyid == null) {
            return "no keyid";
        }
        // Step 1: a URL with a fragment.
        int hash = keyid.indexOf('#');
        if (hash < 0 && flaw != Flaw.INBOX_ACCEPTS_KEYID_WITHOUT_FRAGMENT) {
            return "the keyid is not a URL with a fragment";
        }
        // Step 2: without it, the storage identifier.
        String storage = hash < 0 ? keyid : keyid.substring(0, hash);
        // Step 3: its document names itself.
        HttpResponse<String> got = http.send(HttpRequest.newBuilder(URI.create(storage))
                .header("Accept", "application/lws+cid").timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (got.statusCode() != 200) {
            return "the storage description answered " + got.statusCode();
        }
        JsonNode description = JSON.readTree(got.body());
        if (!storage.equals(description.path("id").asText()) && flaw != Flaw.INBOX_SKIPS_STORAGE_ID_CHECK) {
            return "the document at " + storage + " names another id";
        }
        // Step 4: the verification method.
        JsonNode method = null;
        for (JsonNode m : description.path("verificationMethod")) {
            String id = m.path("id").asText();
            if (id.equals(keyid) || (hash >= 0 && (id.equals(keyid.substring(hash)) || id.equals(keyid.substring(hash + 1))))) {
                method = m;
            }
        }
        if (method == null && hash < 0 && description.path("verificationMethod").size() == 1) {
            // The lenient twin: no fragment, so it takes the only key there is.
            method = description.path("verificationMethod").get(0);
        }
        if (method == null) {
            return "no verification method " + keyid;
        }
        // The body has the digest the signature covers.
        String digest = request.getHeaders().get("Content-Digest");
        String expected = "sha-256=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":";
        if (!expected.equals(digest == null ? null : digest.trim()) && flaw != Flaw.INBOX_SKIPS_DIGEST_CHECK) {
            return "the body does not have the Content-Digest the signature covers";
        }
        // Step 5: the signature, over the base rebuilt from the inbox URL as subscribed.
        if (flaw == Flaw.INBOX_SKIPS_SIGNATURE_CHECK) {
            return null;
        }
        StringBuilder base = new StringBuilder();
        for (String component : components(params)) {
            String value = switch (component) {
                case "@method" -> request.getMethod();
                case "@scheme" -> inbox.getScheme();
                case "@authority" -> inbox.getRawAuthority().toLowerCase(Locale.ROOT);
                case "@path" -> inbox.getRawPath();
                default -> {
                    String header = request.getHeaders().get(component);
                    if (header == null) {
                        throw new IllegalArgumentException("the covered field " + component + " is missing");
                    }
                    yield header.trim();
                }
            };
            base.append('"').append(component).append("\": ").append(value).append('\n');
        }
        base.append("\"@signature-params\": ").append(params);
        ECKey key = (ECKey) JWK.parse(method.path("publicKeyJwk").toString());
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(key.toECPublicKey());
        verifier.update(base.toString().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(sig)) ? null : "the signature does not verify";
    }

    /** The covered components of an inner list: ("@method" "content-type" ...). */
    private static List<String> components(String params) {
        List<String> out = new ArrayList<>();
        String list = params.substring(params.indexOf('(') + 1, params.indexOf(')'));
        for (String item : list.trim().split("\\s+")) {
            if (!item.isEmpty()) {
                out.add(item.replace("\"", ""));
            }
        }
        return out;
    }

    /** A quoted parameter after the inner list, such as keyid. */
    private static String parameter(String params, String name) {
        int at = params.indexOf(";" + name + "=\"", params.indexOf(')'));
        if (at < 0) {
            return null;
        }
        int start = at + name.length() + 3;
        return params.substring(start, params.indexOf('"', start));
    }
}
