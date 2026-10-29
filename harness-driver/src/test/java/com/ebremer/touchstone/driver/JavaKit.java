package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.ProtocolSchema;

/**
 * The Java starter kit (driver/kits/java, CLIENT-TESTING.md section 14.4), built as a client's
 * developers build it: its files compiled as they are, with an adapter of their own that wraps their
 * client, here the reference client. Nothing of the kit is copied into Touchstone's own code.
 */
final class JavaKit {

    static final Path KIT = Path.of("..", "driver", "kits", "java", "src", "main", "java");

    /** The kit's adapter over the reference client: each operation it has, one call each. */
    private static final String REFERENCE_OVER_KIT = """
            package kitref;

            import java.util.Set;

            import com.ebremer.touchstone.fixtures.client.RefAdapter;
            import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
            import com.example.lwsdriver.AdapterError;
            import com.example.lwsdriver.ClientInfo;
            import com.example.lwsdriver.LwsDriverAdapter;
            import com.fasterxml.jackson.databind.JsonNode;

            public final class ReferenceAdapter implements LwsDriverAdapter {
                private final RefAdapter client = new RefAdapter(Flaw.NONE);

                public ClientInfo client() { return new ClientInfo("Touchstone reference client, through the Java kit", null, null); }
                public Set<String> auth() { return Set.of("none", "bearer", "cid", "openid", "saml"); }

                private JsonNode call(String op, JsonNode args) throws AdapterError {
                    try {
                        return client.call(op, args);
                    } catch (RefAdapter.Failure f) {
                        throw new AdapterError(f.kind(), f.status(), f.getMessage());
                    }
                }

                public JsonNode configure(JsonNode a) throws AdapterError { return call("configure", a); }
                public JsonNode discoverStorage(JsonNode a) throws AdapterError { return call("discover_storage", a); }
                public JsonNode head(JsonNode a) throws AdapterError { return call("head", a); }
                public JsonNode read(JsonNode a) throws AdapterError { return call("read", a); }
                public JsonNode listContainer(JsonNode a) throws AdapterError { return call("list_container", a); }
                public JsonNode create(JsonNode a) throws AdapterError { return call("create", a); }
                public JsonNode createContainer(JsonNode a) throws AdapterError { return call("create_container", a); }
                public JsonNode update(JsonNode a) throws AdapterError { return call("update", a); }
                public JsonNode patch(JsonNode a) throws AdapterError { return call("patch", a); }
                public JsonNode delete(JsonNode a) throws AdapterError { return call("delete", a); }
                public JsonNode readLinkset(JsonNode a) throws AdapterError { return call("read_linkset", a); }
                public JsonNode updateLinkset(JsonNode a) throws AdapterError { return call("update_linkset", a); }
                public JsonNode patchLinkset(JsonNode a) throws AdapterError { return call("patch_linkset", a); }
                public JsonNode requestAccess(JsonNode a) throws AdapterError { return call("request_access", a); }
                public JsonNode getAccessRequest(JsonNode a) throws AdapterError { return call("get_access_request", a); }
                public JsonNode listAccessRequests(JsonNode a) throws AdapterError { return call("list_access_requests", a); }
                public JsonNode cancelAccessRequest(JsonNode a) throws AdapterError { return call("cancel_access_request", a); }
                public JsonNode grantAccess(JsonNode a) throws AdapterError { return call("grant_access", a); }
                public JsonNode getAccessGrant(JsonNode a) throws AdapterError { return call("get_access_grant", a); }
                public JsonNode listAccessGrants(JsonNode a) throws AdapterError { return call("list_access_grants", a); }
                public JsonNode revokeAccessGrant(JsonNode a) throws AdapterError { return call("revoke_access_grant", a); }
                public JsonNode subscribe(JsonNode a) throws AdapterError { return call("subscribe", a); }
                public JsonNode listSubscriptions(JsonNode a) throws AdapterError { return call("list_subscriptions", a); }
                public JsonNode getSubscription(JsonNode a) throws AdapterError { return call("get_subscription", a); }
                public JsonNode unsubscribe(JsonNode a) throws AdapterError { return call("unsubscribe", a); }
                public JsonNode verifyNotification(JsonNode a) throws AdapterError { return call("verify_notification", a); }
                public JsonNode search(JsonNode a) throws AdapterError { return call("search", a); }
            }
            """;

    private JavaKit() {
    }

    /** Compiles the kit and the reference client's adapter over it; returns where the classes are. */
    static Path compile(Path work) throws IOException {
        Path sources = work.resolve("src");
        Path classes = work.resolve("classes");
        Files.createDirectories(sources.resolve("kitref"));
        Files.createDirectories(classes);
        Files.writeString(sources.resolve("kitref").resolve("ReferenceAdapter.java"), REFERENCE_OVER_KIT);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", System.getProperty("java.class.path"),
                "--release", "17"));
        try (Stream<Path> kit = Files.walk(KIT)) {
            kit.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        args.add(sources.resolve("kitref").resolve("ReferenceAdapter.java").toString());
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int code = javac.run(null, null, null, args.toArray(String[]::new));
        if (code != 0) {
            throw new IllegalStateException("the Java kit did not compile (" + code + ")");
        }
        return classes;
    }

    /** The compiled adapter on a thread of its own, served by the kit's own runner over pipes. */
    static AdapterLauncher launcher(Path classes) throws IOException {
        URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, JavaKit.class.getClassLoader());
        return () -> {
            try {
                Class<?> runner = loader.loadClass("com.example.lwsdriver.AdapterRunner");
                Class<?> api = loader.loadClass("com.example.lwsdriver.LwsDriverAdapter");
                Object adapter = loader.loadClass("kitref.ReferenceAdapter").getConstructor().newInstance();
                Method serve = runner.getMethod("serve", api, java.io.InputStream.class, java.io.OutputStream.class);
                PipedInputStream adapterIn = new PipedInputStream(1 << 16);
                PipedOutputStream toAdapter = new PipedOutputStream(adapterIn);
                PipedInputStream fromAdapter = new PipedInputStream(1 << 16);
                PipedOutputStream adapterOut = new PipedOutputStream(fromAdapter);
                Thread thread = Thread.ofPlatform().daemon().name("java-kit-adapter").start(() -> {
                    try (adapterOut) {
                        serve.invoke(null, adapter, adapterIn, adapterOut);
                    } catch (ReflectiveOperationException | IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                AdapterConnection c = new AdapterConnection(fromAdapter, toAdapter, ProtocolSchema.instance(),
                        Duration.ofSeconds(10));
                return new AdapterLauncher.Adapter() {
                    @Override
                    public AdapterConnection connection() {
                        return c;
                    }

                    @Override
                    public void close() {
                        AdapterLauncher.shutdown(c);
                        try {
                            thread.join(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
