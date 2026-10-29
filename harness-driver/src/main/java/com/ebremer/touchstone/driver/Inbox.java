package com.ebremer.touchstone.driver;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.BrokenAdapterException;
import com.ebremer.touchstone.driver.protocol.Reply;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

/**
 * The inbox the driver hosts for the client it drives (driver/PROTOCOL.md section 5.2): every path
 * under {@code /inbox/} takes webhook notifications. It passes each delivery unchanged to the
 * adapter's {@code verify_notification}, and answers 202 when the client verified it and 401 when
 * it did not, so the session sees the client's own verdict as the inbox's answer.
 *
 * <p>It binds loopback by default (CLIENT-TESTING.md section 14.3); a session on a public service
 * needs it at a public https URL, which the operator gives as {@code publicBase}.
 */
public final class Inbox implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "/inbox/";
    private static final int MAX_BODY = 1 << 20;

    private final Server server = new Server();
    private final ServerConnector connector = new ServerConnector(server);
    private final AdapterConnection verifier;
    private final Duration timeout;
    private final URI publicBase;
    private final AtomicInteger received = new AtomicInteger();

    /**
     * @param host       the address to bind, such as 127.0.0.1
     * @param port       the port, or 0 for a free one
     * @param publicBase the inbox base as the session reaches it, ending in {@code /inbox/}; null for
     *                   {@code http://host:port/inbox/}
     * @param verifier   the adapter that verifies deliveries, apart from the one that runs the drives
     */
    public Inbox(String host, int port, URI publicBase, AdapterConnection verifier, Duration timeout) {
        this.verifier = verifier;
        this.timeout = timeout;
        connector.setHost(host);
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new Receiver());
        try {
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the driver's inbox on " + host + ":" + port, e);
        }
        this.publicBase = publicBase != null ? publicBase
                : URI.create("http://" + host + ":" + connector.getLocalPort() + PREFIX);
    }

    /** The inbox base the session reaches, ending in {@code /inbox/}. */
    public URI base() {
        return publicBase;
    }

    /** Deliveries received so far. */
    public int received() {
        return received.get();
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
            String path = request.getHttpURI().getPath();
            if (path == null || !path.startsWith(PREFIX) || !request.getMethod().equals("POST")) {
                response.setStatus(404);
                callback.succeeded();
                return true;
            }
            byte[] body;
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readNBytes(MAX_BODY);
            }
            received.incrementAndGet();
            ObjectNode args = JSON.createObjectNode();
            args.put("method", request.getMethod());
            args.put("url", publicBase.resolve(path.substring(PREFIX.length())).toString());
            ObjectNode headers = args.putObject("headers");
            for (HttpField field : request.getHeaders()) {
                ArrayNode values = headers.has(field.getName()) ? (ArrayNode) headers.get(field.getName())
                        : headers.putArray(field.getName());
                values.add(field.getValue());
            }
            args.put("bodyBase64", Base64.getEncoder().encodeToString(body));
            int status;
            try {
                Reply verdict;
                synchronized (verifier) {
                    verdict = verifier.call("verify_notification", args, timeout);
                }
                status = verdict.ok() ? 202 : 401;
            } catch (BrokenAdapterException e) {
                status = 503;
            }
            response.setStatus(status);
            callback.succeeded();
            return true;
        }
    }
}
