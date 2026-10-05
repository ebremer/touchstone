package com.ebremer.touchstone.clients;

import java.net.URI;
import java.nio.file.Path;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.InvalidDefinitionsException;

/**
 * Runs the client-session service:
 * {@code java -jar touchstone-clients.jar --public-base https://host/touchstone/clients [--bind 127.0.0.1]
 * [--port 18090] [--trust-forwarded-for] [--definitions definitions] [--catalog catalog]}. The client
 * rules are read from the definitions directory, and checked against the catalog, at start; a
 * rule that fails the checks stops the service (exit code 2). Behind a reverse proxy, proxy the public base path
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
        Path definitions = Path.of("definitions");
        Path catalog = Path.of("catalog");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--public-base" -> publicBase = args[++i];
                case "--bind" -> bind = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--trust-forwarded-for" -> trust = true;
                case "--definitions" -> definitions = Path.of(args[++i]);
                case "--catalog" -> catalog = Path.of(args[++i]);
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
        ClientRules rules;
        try {
            rules = DefinitionLoader.loadClientRules(definitions, CatalogRepository.load(catalog));
        } catch (InvalidDefinitionsException | IllegalStateException e) {
            System.err.println("cannot load the client rules: " + e.getMessage());
            System.exit(2);
            return;
        }
        try (ClientLab lab = ClientLab.start(config, rules)) {
            lab.join();
        }
    }
}
