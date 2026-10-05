package com.ebremer.touchstone.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

/**
 * The outbound guard of CLIENT-TESTING.md section 8.3: deliveries go only to https URLs of
 * public addresses, checked as the connection is made, and are never redirected.
 */
class OutboundTest {

    @Test
    void onlyPublicUnicastAddressesPass() throws Exception {
        for (String blocked : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.1", "198.51.100.7",
                "203.0.113.9", "198.18.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:10.0.0.1",
                "::ffff:127.0.0.1", "2001:db8::1", "2002:c000:0204::1", "2001:0:4136:e378::1", "64:ff9b::a00:1"}) {
            assertThat(Outbound.publicAddress(InetAddress.getByName(blocked))).as(blocked).isFalse();
        }
        for (String open : new String[] {"8.8.8.8", "1.1.1.1", "93.184.215.14", "172.32.0.1", "100.128.0.1",
                "2606:4700:4700::1111", "2a00:1450:4001:80b::200e"}) {
            assertThat(Outbound.publicAddress(InetAddress.getByName(open))).as(open).isTrue();
        }
    }

    @Test
    void anInboxMustBeHttpsWithAHost() {
        try (Outbound out = new Outbound(false, Duration.ofSeconds(2))) {
            assertThat(out.refusal(URI.create("http://inbox.example/hook"))).contains("https");
            assertThat(out.refusal(URI.create("ftp://inbox.example/hook"))).contains("https");
            assertThat(out.refusal(URI.create("https://user:pw@inbox.example/hook"))).contains("user information");
            assertThat(out.refusal(URI.create("https://inbox.example/hook"))).isNull();
        }
    }

    @Test
    void aPrivateAddressIsRefusedWhenConnecting() throws Exception {
        Server server = server(200, null);
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        try (Outbound out = new Outbound(false, Duration.ofSeconds(5))) {
            // A literal private address, and a name that resolves to one: nothing is sent.
            for (String host : new String[] {"127.0.0.1", "localhost"}) {
                Outbound.Answer a = out.post(URI.create("https://" + host + ":" + port + "/inbox"), Map.of(), new byte[0],
                        1024).get();
                assertThat(a.status()).as(host).isZero();
                assertThat(a.problem()).as(host).contains("no public address");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void aRedirectIsNotFollowedAndA401IsKept() throws Exception {
        Server redirecting = server(302, "http://127.0.0.1:1/elsewhere");
        Server refusing = server(401, null);
        try (Outbound out = new Outbound(true, Duration.ofSeconds(5))) {
            Outbound.Answer moved = out.post(URI.create("http://127.0.0.1:"
                    + ((ServerConnector) redirecting.getConnectors()[0]).getLocalPort() + "/inbox"),
                    Map.of("Content-Type", "application/lws+json"), "{}".getBytes(), 1024).get();
            assertThat(moved.status()).isEqualTo(302);
            Outbound.Answer refused = out.post(URI.create("http://127.0.0.1:"
                    + ((ServerConnector) refusing.getConnectors()[0]).getLocalPort() + "/inbox"),
                    Map.of("Content-Type", "application/lws+json"), "{}".getBytes(), 1024).get();
            assertThat(refused.status()).isEqualTo(401);
            assertThat(new String(refused.body())).isEqualTo("refused");
        } finally {
            redirecting.stop();
            refusing.stop();
        }
    }

    /** A loopback server that answers every request with {@code status}, and a Location when given. */
    private static Server server(int status, String location) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(status);
                if (location != null) {
                    response.getHeaders().put(HttpHeader.LOCATION, location);
                }
                response.write(true, java.nio.ByteBuffer.wrap("refused".getBytes()), callback);
                return true;
            }
        });
        server.start();
        return server;
    }
}
