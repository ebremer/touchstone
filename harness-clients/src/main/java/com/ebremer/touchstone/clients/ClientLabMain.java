package com.ebremer.touchstone.clients;

import java.net.URI;

/**
 * Runs the client-session service:
 * {@code java -jar touchstone-clients.jar --public-base https://host/touchstone/clients [--bind 127.0.0.1]
 * [--port 18090] [--trust-forwarded-for]}. Behind a reverse proxy, proxy the public base path
 * and {@code /.well-known/lws-configuration} followed by it, keeping the paths, and pass
 * {@code --trust-forwarded-for} when the proxy sets X-Forwarded-For.
 */
public final class ClientLabMain {

    private ClientLabMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String publicBase = null;
        String bind = "127.0.0.1";
        int port = 18090;
        boolean trust = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--public-base" -> publicBase = args[++i];
                case "--bind" -> bind = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--trust-forwarded-for" -> trust = true;
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (publicBase == null) {
            publicBase = "http://localhost:" + port + "/touchstone/clients";
        }
        ClientLabConfig config = ClientLabConfig.defaults(URI.create(publicBase), bind, port).withTrustForwardedFor(trust);
        try (ClientLab lab = ClientLab.start(config)) {
            lab.join();
        }
    }
}
