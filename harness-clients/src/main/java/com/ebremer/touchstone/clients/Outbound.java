package com.ebremer.touchstone.clients;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;

/**
 * The service's only outbound requests: notifications to the inboxes clients name
 * (CLIENT-TESTING.md section 8.3). An inbox URL is a stranger's choice, so a delivery goes only to
 * an {@code https} URL whose host resolves to public unicast addresses. The check is made on the
 * addresses the client connects to, as it connects, so a name that resolves differently the
 * second time (DNS rebinding) gains nothing. Redirects are never followed, cookies never kept,
 * no proxy is used, and every exchange is bounded in time and size.
 *
 * <p>For local development and the self-test only, private inboxes may be allowed, over http as
 * well; a public service never allows them.
 */
final class Outbound implements AutoCloseable {

    /** What an inbox answered; status 0 when nothing did, with the reason. */
    record Answer(int status, Map<String, List<String>> headers, byte[] body, String problem) {
        static Answer none(String problem) {
            return new Answer(0, Map.of(), new byte[0], problem);
        }
    }

    private final HttpClient http;
    private final boolean allowPrivate;
    private final Duration timeout;

    Outbound(boolean allowPrivate, Duration timeout) {
        this.allowPrivate = allowPrivate;
        this.timeout = timeout;
        this.http = new HttpClient();
        http.setFollowRedirects(false);
        http.setHttpCookieStore(new HttpCookieStore.Empty());
        http.setConnectTimeout(Math.min(timeout.toMillis(), 5000));
        http.setIdleTimeout(timeout.toMillis());
        http.setMaxConnectionsPerDestination(4);
        http.setMaxRequestsQueuedPerDestination(32);
        http.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Touchstone client sessions (notifications)"));
        SocketAddressResolver dns = new SocketAddressResolver.Sync();
        http.setSocketAddressResolver((host, port, context, promise) -> dns.resolve(host, port, context,
                new Promise<List<InetSocketAddress>>() {
                    @Override
                    public void succeeded(List<InetSocketAddress> addresses) {
                        List<InetSocketAddress> allowed = new ArrayList<>();
                        for (InetSocketAddress a : addresses) {
                            if (allowPrivate || (a.getAddress() != null && publicAddress(a.getAddress()))) {
                                allowed.add(a);
                            }
                        }
                        if (allowed.isEmpty()) {
                            promise.failed(new UnknownHostException(host + " resolves to no public address"));
                        } else {
                            promise.succeeded(allowed);
                        }
                    }

                    @Override
                    public void failed(Throwable x) {
                        promise.failed(x);
                    }
                }));
        try {
            http.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the delivery client", e);
        }
        // The inbox's answer as it is: no handler may act on a 401, a redirect or a 100. The
        // client installs its handlers as it starts, so they go after it.
        http.getProtocolHandlers().clear();
    }

    /**
     * Why a delivery to {@code inbox} is refused before anything is sent, or null when it may be
     * tried. The addresses are checked again, and finally, when connecting.
     */
    String refusal(URI inbox) {
        String scheme = inbox.getScheme() == null ? "" : inbox.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowPrivate && scheme.equals("http"))) {
            return "an inbox URL must use https";
        }
        if (inbox.getHost() == null || inbox.getRawUserInfo() != null) {
            return "an inbox URL needs a host and no user information";
        }
        return null;
    }

    /** POSTs {@code body} to {@code inbox}, keeping at most {@code maxResponseBytes} of the answer. */
    CompletableFuture<Answer> post(URI inbox, Map<String, String> headers, byte[] body, int maxResponseBytes) {
        CompletableFuture<Answer> answer = new CompletableFuture<>();
        String type = headers.getOrDefault("Content-Type", "application/octet-stream");
        Request request = http.newRequest(inbox).method(HttpMethod.POST)
                .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .body(new BytesRequestContent(type, body));
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Content-Type")) {
                request.headers(h -> h.put(name, value));
            }
        });
        new CompletableResponseListener(request, maxResponseBytes).send().whenComplete((response, failure) -> {
            if (response == null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                answer.complete(Answer.none(cause == null ? "no answer" : String.valueOf(cause.getMessage())));
                return;
            }
            Map<String, List<String>> fields = new LinkedHashMap<>();
            for (HttpField f : response.getHeaders()) {
                fields.computeIfAbsent(f.getName(), k -> new ArrayList<>()).add(f.getValue());
            }
            byte[] content = response.getContent();
            answer.complete(new Answer(response.getStatus(), fields, content == null ? new byte[0] : content, null));
        });
        return answer;
    }

    /**
     * Whether {@code a} is a public unicast address: not loopback, private (RFC 1918), link-local
     * (169.254.0.0/16, including the cloud metadata address), shared (CGNAT, 100.64.0.0/10), unique
     * local (fc00::/7), multicast, reserved or for documentation; for IPv6, only global unicast
     * (2000::/3) without the prefixes that embed an IPv4 address.
     */
    static boolean publicAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int x = b[0] & 0xff;
            int y = b[1] & 0xff;
            int z = b[2] & 0xff;
            return !(x == 0 || x == 10 || x == 127 || x >= 224
                    || (x == 100 && y >= 64 && y <= 127)
                    || (x == 169 && y == 254)
                    || (x == 172 && y >= 16 && y <= 31)
                    || (x == 192 && y == 168)
                    || (x == 192 && y == 0 && (z == 0 || z == 2))
                    || (x == 192 && y == 88 && z == 99)
                    || (x == 198 && (y == 18 || y == 19))
                    || (x == 198 && y == 51 && z == 100)
                    || (x == 203 && y == 0 && z == 113));
        }
        if ((b[0] & 0xe0) != 0x20) {
            return false;
        }
        int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        return !(w0 == 0x2002                                   // 6to4
                || (w0 == 0x2001 && w1 == 0x0000)               // Teredo
                || (w0 == 0x2001 && w1 == 0x0db8)               // documentation
                || (w0 == 0x2001 && (w1 & 0xffe0) == 0x0000)    // IETF protocol assignments, 2001::/23
                || (w0 == 0x3fff && (w1 & 0xf000) == 0x0000));  // documentation, 3fff::/20
    }

    @Override
    public void close() {
        try {
            http.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
