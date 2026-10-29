package com.example.lwsdriver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
 * writes the hello, reads one request a line from stdin, calls the method of {@link LwsDriverAdapter}
 * for its operation, and writes one response a line to stdout. stdout carries protocol lines only:
 * log to stderr.
 *
 * <pre>
 * public static void main(String[] args) {
 *     AdapterRunner.serve(new MyAdapter(), System.in, System.out);
 *     System.exit(0);
 * }
 * </pre>
 *
 * <p>Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.
 */
public final class AdapterRunner {

    public static final String PROTOCOL = "touchstone-driver/1";

    /** One operation of the protocol: how the runner calls it. */
    @FunctionalInterface
    private interface Call {
        JsonNode on(LwsDriverAdapter adapter, JsonNode args) throws AdapterError;
    }

    /** Every operation of section 5 but shutdown, with the interface method each calls. */
    private static final Map<String, String> METHODS = new LinkedHashMap<>();
    private static final Map<String, Call> CALLS = new LinkedHashMap<>();

    static {
        add("configure", "configure", LwsDriverAdapter::configure);
        add("discover_storage", "discoverStorage", LwsDriverAdapter::discoverStorage);
        add("head", "head", LwsDriverAdapter::head);
        add("read", "read", LwsDriverAdapter::read);
        add("list_container", "listContainer", LwsDriverAdapter::listContainer);
        add("create", "create", LwsDriverAdapter::create);
        add("create_container", "createContainer", LwsDriverAdapter::createContainer);
        add("update", "update", LwsDriverAdapter::update);
        add("patch", "patch", LwsDriverAdapter::patch);
        add("delete", "delete", LwsDriverAdapter::delete);
        add("read_linkset", "readLinkset", LwsDriverAdapter::readLinkset);
        add("update_linkset", "updateLinkset", LwsDriverAdapter::updateLinkset);
        add("patch_linkset", "patchLinkset", LwsDriverAdapter::patchLinkset);
        add("move", "move", LwsDriverAdapter::move);
        add("request_access", "requestAccess", LwsDriverAdapter::requestAccess);
        add("get_access_request", "getAccessRequest", LwsDriverAdapter::getAccessRequest);
        add("list_access_requests", "listAccessRequests", LwsDriverAdapter::listAccessRequests);
        add("cancel_access_request", "cancelAccessRequest", LwsDriverAdapter::cancelAccessRequest);
        add("grant_access", "grantAccess", LwsDriverAdapter::grantAccess);
        add("get_access_grant", "getAccessGrant", LwsDriverAdapter::getAccessGrant);
        add("list_access_grants", "listAccessGrants", LwsDriverAdapter::listAccessGrants);
        add("revoke_access_grant", "revokeAccessGrant", LwsDriverAdapter::revokeAccessGrant);
        add("subscribe", "subscribe", LwsDriverAdapter::subscribe);
        add("list_subscriptions", "listSubscriptions", LwsDriverAdapter::listSubscriptions);
        add("get_subscription", "getSubscription", LwsDriverAdapter::getSubscription);
        add("unsubscribe", "unsubscribe", LwsDriverAdapter::unsubscribe);
        add("verify_notification", "verifyNotification", LwsDriverAdapter::verifyNotification);
        add("list_types", "listTypes", LwsDriverAdapter::listTypes);
        add("search", "search", LwsDriverAdapter::search);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private AdapterRunner() {
    }

    private static void add(String op, String method, Call call) {
        METHODS.put(op, method);
        CALLS.put(op, call);
    }

    /** Serves the driver on these streams until end of input or {@code shutdown}. */
    public static void serve(LwsDriverAdapter adapter, InputStream in, OutputStream out) {
        PrintStream protocol = new PrintStream(out, false, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            write(protocol, hello(adapter));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode request;
                try {
                    request = JSON.readTree(line);
                } catch (IOException e) {
                    request = null;
                }
                if (request == null || !request.isObject()) {
                    write(protocol, error(null, AdapterError.invalidArguments("a request is a JSON object")));
                    continue;
                }
                JsonNode id = request.path("id");
                String op = request.path("op").asText();
                if (op.equals("shutdown")) {
                    write(protocol, ok(id, JSON.createObjectNode()));
                    return;
                }
                Call call = CALLS.get(op);
                try {
                    if (call == null) {
                        throw AdapterError.unsupported(op);
                    }
                    JsonNode args = request.path("args").isObject() ? request.get("args") : JSON.createObjectNode();
                    JsonNode result = call.on(adapter, args);
                    write(protocol, ok(id, result == null ? JSON.createObjectNode() : result));
                } catch (AdapterError e) {
                    write(protocol, error(id, e));
                } catch (IllegalArgumentException e) {
                    write(protocol, error(id, AdapterError.invalidArguments(String.valueOf(e.getMessage()))));
                } catch (RuntimeException e) {
                    // A bug in the adapter or the client: report it, and keep running.
                    e.printStackTrace(System.err);
                    write(protocol, error(id, new AdapterError("internal", null, e.toString())));
                }
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    /** The hello: the client, its authentication, and every operation the adapter implements. */
    static ObjectNode hello(LwsDriverAdapter adapter) {
        ObjectNode hello = JSON.createObjectNode();
        ObjectNode h = hello.putObject("hello");
        h.put("protocol", PROTOCOL);
        ClientInfo info = adapter.client();
        ObjectNode client = h.putObject("client").put("name", info.name());
        if (info.version() != null) {
            client.put("version", info.version());
        }
        if (info.homepage() != null) {
            client.put("homepage", info.homepage());
        }
        h.put("language", "java");
        ArrayNode auth = h.putArray("auth");
        adapter.auth().forEach(auth::add);
        ArrayNode ops = h.putArray("operations");
        METHODS.forEach((op, method) -> {
            if (implemented(adapter, method)) {
                ops.add(op);
            }
        });
        return hello;
    }

    /** Whether the adapter's class implements a method itself, rather than leaving the interface's default. */
    private static boolean implemented(LwsDriverAdapter adapter, String method) {
        try {
            return adapter.getClass().getMethod(method, JsonNode.class).getDeclaringClass() != LwsDriverAdapter.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode r = JSON.createObjectNode();
        r.set("id", id);
        r.put("ok", true);
        r.set("result", result);
        return r;
    }

    private static ObjectNode error(JsonNode id, AdapterError e) {
        ObjectNode r = JSON.createObjectNode();
        r.set("id", id == null ? JSON.nullNode() : id);
        r.put("ok", false);
        ObjectNode error = r.putObject("error").put("kind", e.kind());
        if (e.status() != null) {
            error.put("status", e.status());
        }
        error.put("message", e.getMessage() == null ? e.kind() : e.getMessage());
        return r;
    }

    private static void write(PrintStream out, JsonNode line) {
        out.print(line.toString());
        out.print('\n');
        out.flush();
    }
}
