package com.ebremer.touchstone.fixtures.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

import com.ebremer.touchstone.fixtures.client.RefHttp.Reply;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Agent;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The reference client behind the driver protocol, {@code touchstone-driver/1} (driver/PROTOCOL.md,
 * CLIENT-TESTING.md section 14.5): an adapter that runs each operation it is sent with the
 * reference client's HTTP and authentication ({@link RefHttp}), and reports what happened. With a
 * {@link Flaw} it is a broken twin behind the same adapter, which makes its one mistake inside the
 * operation the mistake concerns.
 *
 * <p>Where the protocol leaves a choice to the client, it chooses as a careful library would: a
 * write it is given no {@code If-Match} for is made conditional on what it reads first; a linkset
 * that does not allow PUT is updated with JSON Patch; a search tries a richer query format first
 * and falls back to the baseline when refused; a search whose page is refused is started again;
 * and a lost create is never sent again.
 *
 * <p>{@code java ... RefAdapter [FLAW]} runs it on standard input and output.
 */
public final class RefAdapter {

    static final String PROTOCOL = "touchstone-driver/1";
    static final List<String> OPERATIONS = List.of("configure", "discover_storage", "head", "read", "list_container",
            "create", "create_container", "update", "patch", "delete", "read_linkset", "update_linkset", "patch_linkset",
            "request_access", "get_access_request", "list_access_requests", "cancel_access_request", "grant_access",
            "get_access_grant", "list_access_grants", "revoke_access_grant", "subscribe", "list_subscriptions",
            "get_subscription", "unsubscribe", "verify_notification", "search", "shutdown");

    private static final String LWS = RefHttp.LWS;
    private static final String LWS_JSON = "application/lws+json";
    private static final String LWS_CID = "application/lws+cid";
    private static final String JSON_PATCH = "application/json-patch+json";
    private static final String LINKSET_JSON = "application/linkset+json";
    private static final String BASELINE_QUERY = "application/lws-query+json";
    private static final String RICHER_QUERY = "application/sparql-query";
    private static final String FORGED_LINKSET = "<https://linkset.invalid/forged>; rel=\"linkset\"";

    /** An operation's outcome that is not a result: an error of driver/PROTOCOL.md section 4.2. */
    static final class Failure extends Exception {
        final String kind;
        final Integer status;

        Failure(String kind, Integer status, String message) {
            super(message);
            this.kind = kind;
            this.status = status;
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Flaw flaw;
    private final HttpClient verifierHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private RefHttp web;
    private Agent agent;
    /** What each linkset last said about itself, by URL: its Allow, Accept-Patch and ETag. */
    private final Map<String, Reply> linksets = new HashMap<>();
    /** The search services that refused the richer query format. */
    private final Set<String> refusedRicher = new HashSet<>();
    /** The inbox of the first subscription, which the twin that shares inboxes gives every later one. */
    private String firstInbox;
    private boolean queryFlawShown;

    public RefAdapter(Flaw flaw) {
        this.flaw = flaw;
        this.web = new RefHttp(flaw);
    }

    public static void main(String[] args) {
        serve(System.in, System.out, args.length == 0 ? Flaw.NONE : Flaw.valueOf(args[0]));
    }

    /** Runs the adapter on these streams until end of input or {@code shutdown} (PROTOCOL.md section 2). */
    public static void serve(InputStream in, OutputStream out, Flaw flaw) {
        new RefAdapter(flaw).loop(in, out);
    }

    private void loop(InputStream in, OutputStream out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            write(out, hello());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode request;
                try {
                    request = json.readTree(line);
                } catch (IOException e) {
                    request = null;
                }
                if (request == null || !request.isObject()) {
                    write(out, error(null, new Failure("invalidArguments", null, "a request is a JSON object")));
                    continue;
                }
                JsonNode id = request.path("id");
                String op = request.path("op").asText();
                try {
                    JsonNode result = call(op, request.path("args"));
                    ObjectNode ok = json.createObjectNode();
                    ok.set("id", id);
                    ok.put("ok", true);
                    ok.set("result", result);
                    write(out, ok);
                } catch (Failure f) {
                    write(out, error(id, f));
                }
                if (op.equals("shutdown")) {
                    return;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ObjectNode hello() {
        ObjectNode hello = json.createObjectNode();
        ObjectNode h = hello.putObject("hello");
        h.put("protocol", PROTOCOL);
        h.putObject("client").put("name", "Touchstone reference client" + (flaw == Flaw.NONE ? "" : " (twin " + flaw + ")"));
        h.put("language", "java");
        ArrayNode auth = h.putArray("auth");
        List.of("none", "bearer", "cid", "openid", "saml").forEach(auth::add);
        ArrayNode ops = h.putArray("operations");
        OPERATIONS.forEach(ops::add);
        return hello;
    }

    private ObjectNode error(JsonNode id, Failure f) {
        ObjectNode e = json.createObjectNode();
        e.set("id", id == null ? json.nullNode() : id);
        e.put("ok", false);
        ObjectNode error = e.putObject("error");
        error.put("kind", f.kind);
        if (f.status != null) {
            error.put("status", f.status);
        }
        error.put("message", f.getMessage() == null ? f.kind : f.getMessage());
        return e;
    }

    private static void write(OutputStream out, JsonNode line) throws IOException {
        out.write((line.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Runs one operation; an error the client reaches is a {@link Failure}, never a breakdown. */
    JsonNode call(String op, JsonNode args) throws Failure {
        try {
            return switch (op) {
                case "configure" -> configure(args);
                case "discover_storage" -> discoverStorage(url(args, "url"));
                case "head" -> head(url(args, "url"));
                case "read" -> read(args);
                case "list_container" -> listContainer(url(args, "url"), limit(args));
                case "create" -> create(args);
                case "create_container" -> createContainer(url(args, "parent"), text(args, "slug"));
                case "update" -> update(args);
                case "patch" -> patch(args);
                case "delete" -> delete(args);
                case "read_linkset" -> readLinkset(url(args, "url"));
                case "update_linkset" -> updateLinkset(args);
                case "patch_linkset" -> patchLinkset(args);
                case "request_access" -> post(url(args, "serviceUrl"), accessRequest(document(args, "request")));
                case "grant_access" -> post(url(args, "serviceUrl"), accessGrant(document(args, "grant")));
                case "get_access_request", "get_access_grant" -> getDocument(url(args, "url"));
                case "list_access_requests", "list_access_grants", "list_subscriptions" ->
                        listContainer(url(args, "serviceUrl"), limit(args));
                case "cancel_access_request", "revoke_access_grant", "unsubscribe" -> remove(url(args, "url"));
                case "subscribe" -> subscribe(args);
                case "get_subscription" -> getSubscription(url(args, "url"));
                case "verify_notification" -> verifyNotification(args);
                case "search" -> search(url(args, "serviceUrl"), args.path("query"), limit(args));
                case "shutdown" -> json.createObjectNode();
                default -> throw new Failure("unsupported", null, op + " is not an operation of this client");
            };
        } catch (IOException e) {
            throw new Failure("transport", null, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("transport", null, "interrupted");
        } catch (IllegalArgumentException e) {
            throw new Failure("invalidArguments", null, e.getMessage());
        } catch (RuntimeException e) {
            throw new Failure("internal", null, String.valueOf(e));
        }
    }

    // ---- configure ----

    private JsonNode configure(JsonNode args) throws Failure, IOException {
        JsonNode auth = args.path("auth");
        web = new RefHttp(flaw);
        linksets.clear();
        ObjectNode result = json.createObjectNode();
        switch (auth.path("type").asText()) {
            case "none" -> agent = null;
            case "bearer" -> {
                agent = new Agent("urn:touchstone:bearer", null, null, null);
                web.token(agent, auth.path("token").asText());
            }
            case "cid" -> {
                ObjectNode key = (ObjectNode) auth.path("privateJwk").deepCopy();
                if (auth.hasNonNull("kid")) {
                    key.put("kid", auth.path("kid").asText());
                }
                agent = new Agent(auth.path("agent").asText(), null, json.writeValueAsString(key), null);
                result.put("agent", agent.cidUrl());
            }
            case "openid" -> {
                agent = new Agent("urn:touchstone:openid", null, null, null);
                web.idToken(agent, auth.path("idToken").asText());
            }
            case "saml" -> {
                String assertion = auth.path("assertion").asText();
                agent = new Agent("urn:touchstone:saml", null, null, null, () -> assertion);
            }
            default -> throw new Failure("unsupported", null, "no authentication " + auth.path("type").asText());
        }
        return result;
    }

    // ---- core ----

    private JsonNode discoverStorage(URI url) throws Failure, IOException, InterruptedException {
        Reply r = send("GET", url, null, null, LWS_CID, Map.of());
        if (!describesStorage(r)) {
            URI linked = r.link(LWS + "storage");
            if (linked == null) {
                throw failure(r);
            }
            r = send("GET", linked, null, null, LWS_CID, Map.of());
        }
        if (r.status() != 200) {
            throw failure(r);
        }
        JsonNode doc = json.readTree(r.body());
        ObjectNode out = json.createObjectNode();
        out.put("id", r.url().resolve(doc.path("id").asText(r.url().toString())).toString());
        ArrayNode services = out.putArray("services");
        for (JsonNode s : doc.path("service")) {
            ObjectNode service = services.addObject();
            if (s.hasNonNull("id")) {
                service.put("id", s.path("id").asText());
            }
            ArrayNode types = service.putArray("types");
            JsonNode t = s.path("type");
            (t.isArray() ? t : json.createArrayNode().add(t)).forEach(n -> types.add(n.asText()));
            service.put("serviceEndpoint", r.url().resolve(s.path("serviceEndpoint").asText()).toString());
        }
        URI root = web.services(r).get("StorageRoot");
        if (root != null) {
            out.put("storageRoot", root.toString());
        }
        out.set("raw", doc);
        return out;
    }

    private boolean describesStorage(Reply r) {
        try {
            return r.status() == 200 && json.readTree(r.body()).has("service");
        } catch (IOException e) {
            return false;
        }
    }

    private JsonNode head(URI url) throws Failure, IOException, InterruptedException {
        Reply r = send("HEAD", url, null, null, null, Map.of());
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        return metadata(r);
    }

    private JsonNode read(JsonNode args) throws Failure, IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (args.hasNonNull("ifNoneMatch")) {
            headers.put("If-None-Match", args.path("ifNoneMatch").asText());
        }
        if (args.hasNonNull("rangeStart") || args.hasNonNull("rangeEnd")) {
            headers.put("Range", "bytes=" + args.path("rangeStart").asText("") + "-" + args.path("rangeEnd").asText(""));
        }
        Reply r = send("GET", url(args, "url"), null, null, text(args, "accept"), headers);
        ObjectNode out = json.createObjectNode();
        if (r.status() == 304) {
            out.set("metadata", metadata(r));
            out.put("notModified", true);
            return out;
        }
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        out.set("metadata", metadata(r));
        out.put("notModified", false);
        String type = r.header("Content-Type");
        if (type == null || !textual(type)) {
            out.putObject("body").put("base64", Base64.getEncoder().encodeToString(r.body().getBytes(StandardCharsets.UTF_8)));
        } else {
            out.putObject("body").put("text", r.body());
        }
        return out;
    }

    private JsonNode listContainer(URI url, int limit) throws Failure, IOException, InterruptedException {
        Reply page = send("GET", url, null, null, LWS_JSON, Map.of());
        if (page.status() / 100 != 2) {
            throw failure(page);
        }
        List<String> items = new ArrayList<>();
        collect(page, items);
        if (flaw == Flaw.BUILDS_PAGE_URL) {
            Reply second = send("GET", URI.create(url + "?page=2"), null, null, LWS_JSON, Map.of());
            if (second.status() / 100 == 2) {
                collect(second, items);
            }
        } else {
            for (URI next = page.link("next"); next != null && items.size() <= limit; ) {
                page = send("GET", next, null, null, LWS_JSON, Map.of());
                if (page.status() / 100 != 2) {
                    throw failure(page);
                }
                collect(page, items);
                next = page.link("next");
            }
        }
        return items(items, limit);
    }

    private JsonNode create(JsonNode args) throws Failure, IOException, InterruptedException {
        URI container = url(args, "container");
        Map<String, String> headers = new LinkedHashMap<>();
        String slug = text(args, "slug");
        if (slug != null) {
            headers.put("Slug", slug);
        }
        List<String> links = new ArrayList<>();
        args.path("types").forEach(t -> links.add("<" + t.asText() + ">; rel=\"type\""));
        args.path("links").forEach(l -> links.add("<" + l.path("href").asText() + ">; rel=\"" + l.path("rel").asText() + "\""));
        if (!links.isEmpty()) {
            if (flaw == Flaw.CREATES_WITH_LINKSET_LINK) {
                links.add(FORGED_LINKSET);
            }
            headers.put("Link", String.join(", ", links));
        }
        String[] body = body(args);
        Reply r = send("POST", container, body[0], body[1], null, headers);
        if (r.status() / 100 == 5 && flaw == Flaw.RETRIES_LOST_CREATE_BLINDLY) {
            r = send("POST", container, body[0], body[1], null, headers);
        }
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        if (slug != null && flaw == Flaw.BUILDS_MEMBER_URL) {
            send("GET", container.resolve(slug), null, null, null, Map.of());
        }
        return created(r);
    }

    private JsonNode createContainer(URI parent, String slug) throws Failure, IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (slug != null) {
            headers.put("Slug", slug);
        }
        if (flaw == Flaw.CREATES_CONTAINER_WITHOUT_TYPE_LINK) {
            send("POST", parent, null, null, null, headers);
        }
        headers.put("Link", "<" + LWS + "Container>; rel=\"type\"" + (flaw == Flaw.CREATES_WITH_LINKSET_LINK ? ", " + FORGED_LINKSET : ""));
        Reply r = send("POST", parent, null, null, null, headers);
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        return created(r);
    }

    private JsonNode update(JsonNode args) throws Failure, IOException, InterruptedException {
        URI url = url(args, "url");
        Map<String, String> headers = new LinkedHashMap<>();
        if (flaw != Flaw.PUT_UNCONDITIONAL) {
            if (args.hasNonNull("ifMatch")) {
                headers.put("If-Match", args.path("ifMatch").asText());
            } else if (!args.hasNonNull("ifNoneMatch")) {
                headers.put("If-Match", etag(url, null));
            }
        }
        if (args.hasNonNull("ifNoneMatch")) {
            headers.put("If-None-Match", args.path("ifNoneMatch").asText());
        }
        List<String> links = new ArrayList<>();
        args.path("links").forEach(l -> links.add("<" + l.path("href").asText() + ">; rel=\"" + l.path("rel").asText() + "\""));
        if (!links.isEmpty()) {
            headers.put("Link", String.join(", ", links));
            if (flaw != Flaw.UPDATES_LINKS_WITHOUT_PREFER) {
                headers.put("Prefer", "set-linkset");
            }
        }
        String[] body = body(args);
        return update(send("PUT", url, body[0], body[1], null, headers));
    }

    private JsonNode patch(JsonNode args) throws Failure, IOException, InterruptedException {
        URI url = url(args, "url");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("If-Match", args.hasNonNull("ifMatch") ? args.path("ifMatch").asText() : etag(url, null));
        return update(send("PATCH", url, JSON_PATCH, args.path("patch").toString(), null, headers));
    }

    private JsonNode delete(JsonNode args) throws Failure, IOException, InterruptedException {
        URI url = url(args, "url");
        boolean recursive = args.path("recursive").asBoolean(false);
        Map<String, String> headers = new LinkedHashMap<>();
        if (!(flaw == Flaw.DELETE_UNCONDITIONAL && !recursive)) {
            headers.put("If-Match", args.hasNonNull("ifMatch") ? args.path("ifMatch").asText()
                    : etag(url, recursive ? LWS_JSON : null));
        }
        if (recursive && flaw != Flaw.DELETES_CONTAINER_WITHOUT_DEPTH) {
            headers.put("Depth", "infinity");
        }
        return remove(send("DELETE", url, null, null, null, headers));
    }

    // ---- linksets ----

    private JsonNode readLinkset(URI resource) throws Failure, IOException, InterruptedException {
        Reply r = send("HEAD", resource, null, null, null, Map.of());
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        URI linkset = r.link("linkset");
        if (linkset == null) {
            throw new Failure("protocol", null, resource + " links to no linkset");
        }
        Reply ls = linkset(linkset);
        if (ls.status() / 100 != 2) {
            throw failure(ls);
        }
        ObjectNode out = json.createObjectNode();
        out.put("url", linkset.toString());
        if (ls.header("ETag") != null) {
            out.put("etag", ls.header("ETag"));
        }
        out.set("linkset", json.readTree(ls.body()));
        out.set("allow", list(ls.header("Allow")));
        out.set("acceptPatch", list(ls.header("Accept-Patch")));
        return out;
    }

    private JsonNode updateLinkset(JsonNode args) throws Failure, IOException, InterruptedException {
        URI url = url(args, "linksetUrl");
        Reply known = linksets.containsKey(url.toString()) ? linksets.get(url.toString()) : linkset(url);
        if (!known.allows("PUT") && flaw != Flaw.LINKSET_PUT_UNADVERTISED) {
            // A careful client does not assume PUT: the whole linkset, replaced with JSON Patch.
            if (!known.listed("Accept-Patch", JSON_PATCH)) {
                throw new Failure("protocol", null, url + " allows neither PUT nor JSON Patch");
            }
            ArrayNode replace = json.createArrayNode();
            replace.addObject().put("op", "replace").put("path", "/linkset").set("value", args.path("linkset").path("linkset"));
            return update(linksetWrite("PATCH", url, JSON_PATCH, replace.toString(), args, known));
        }
        Reply r = linksetWrite("PUT", url, LINKSET_JSON, args.path("linkset").toString(), args, known);
        if (r.status() == 405 && flaw == Flaw.REPEATS_REFUSED_PUT) {
            linksetWrite("PUT", url, LINKSET_JSON, args.path("linkset").toString(), args, known);
        } else if (r.status() == 405) {
            // Refused after all: read what the linkset supports now, and go by that next time.
            linkset(url);
        }
        return update(r);
    }

    private JsonNode patchLinkset(JsonNode args) throws Failure, IOException, InterruptedException {
        URI url = url(args, "linksetUrl");
        Reply known = linksets.containsKey(url.toString()) ? linksets.get(url.toString()) : linkset(url);
        if (flaw == Flaw.LINKSET_PATCH_FORMAT_UNADVERTISED) {
            return update(linksetWrite("PATCH", url, "application/merge-patch+json", "{}", args, known));
        }
        if (!known.listed("Accept-Patch", JSON_PATCH)) {
            throw new Failure("protocol", null, url + " does not take JSON Patch");
        }
        return update(linksetWrite("PATCH", url, JSON_PATCH, args.path("patch").toString(), args, known));
    }

    /** A conditional write of a linkset; afterwards its ETag is stale, so it is read again before the next. */
    private Reply linksetWrite(String method, URI url, String type, String body, JsonNode args, Reply known)
            throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (flaw != Flaw.LINKSET_WRITE_UNCONDITIONAL) {
            String etag = args.hasNonNull("ifMatch") ? args.path("ifMatch").asText() : known.header("ETag");
            if (etag != null) {
                headers.put("If-Match", etag);
            }
        }
        Reply r = send(method, url, type, body, null, headers);
        linksets.remove(url.toString());
        return r;
    }

    private Reply linkset(URI url) throws IOException, InterruptedException {
        Reply ls = send("GET", url, null, null, LINKSET_JSON, Map.of());
        if (ls.status() / 100 == 2 || ls.status() == 405) {
            linksets.put(url.toString(), ls);
        }
        return ls;
    }

    // ---- access requests and grants ----

    private ObjectNode accessRequest(ObjectNode doc) {
        if (flaw == Flaw.ACCESS_REQUEST_WRONG_TYPE) {
            doc.putArray("type").add("AccessGrant");
        }
        return doc;
    }

    private ObjectNode accessGrant(ObjectNode doc) {
        switch (flaw) {
            case ACCESS_CONTEXT_NOT_ARRAY -> doc.put("@context", "https://www.w3.org/ns/lws/v1");
            case ACCESS_GRANT_WITHOUT_TYPE -> doc.remove("type");
            case ACCESS_WITHOUT_STORAGE -> doc.remove("storage");
            case ACCESS_INBOX_NOT_URI -> doc.put("inbox", "the inbox");
            case ACCESS_NOT_ARRAY -> doc.set("access", doc.path("access").path(0));
            default -> { }
        }
        JsonNode first = doc.path("access").isArray() ? doc.path("access").path(0) : null;
        if (first instanceof ObjectNode policy) {
            switch (flaw) {
                case POLICY_WITHOUT_TYPE -> policy.remove("type");
                case POLICY_UNKNOWN_ACTION -> policy.putArray("action").add("write");
                case POLICY_ASSIGNEE_NOT_URI -> policy.put("assignee", "bob");
                case POLICY_TARGET_NOT_OBJECT -> policy.put("target", policy.path("target").path("value").path(0).asText());
                case CONSTRAINT_WITHOUT_OPERATOR -> policy.path("constraint").forEach(c -> ((ObjectNode) c).remove("operator"));
                default -> { }
            }
        }
        return doc;
    }

    private JsonNode post(URI service, ObjectNode doc) throws Failure, IOException, InterruptedException {
        Reply r = send("POST", service, LWS_JSON, json.writeValueAsString(doc), null, Map.of());
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        return json.createObjectNode().put("location", r.location().toString());
    }

    private JsonNode getDocument(URI url) throws Failure, IOException, InterruptedException {
        Reply r = send("GET", url, null, null, LWS_JSON, Map.of());
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        ObjectNode out = json.createObjectNode();
        out.set("document", json.readTree(r.body()));
        return out;
    }

    // ---- notifications ----

    private JsonNode subscribe(JsonNode args) throws Failure, IOException, InterruptedException {
        String inbox = args.path("inbox").asText();
        if (firstInbox == null) {
            firstInbox = inbox;
        } else if (flaw == Flaw.SHARES_INBOX) {
            inbox = firstInbox;
        }
        ObjectNode doc = json.createObjectNode();
        doc.put("type", flaw == Flaw.SUBSCRIPTION_UNADVERTISED_TYPE ? "Webhook" : "WebhookSubscription");
        if (flaw == Flaw.SUBSCRIPTION_TOPIC_NOT_ARRAY) {
            doc.put("topic", args.path("topics").path(0).asText());
        } else {
            doc.set("topic", args.path("topics").deepCopy());
        }
        if (flaw != Flaw.SUBSCRIPTION_WITHOUT_INBOX) {
            doc.put("inbox", inbox);
        }
        if (args.hasNonNull("expires")) {
            doc.put("expires", args.path("expires").asText());
        }
        String type = flaw == Flaw.SUBSCRIPTION_AS_PLAIN_JSON ? "application/json" : LWS_JSON;
        Reply r = send("POST", url(args, "serviceUrl"), type, json.writeValueAsString(doc), null, Map.of());
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        return json.createObjectNode().put("subscription", r.location().toString());
    }

    private JsonNode getSubscription(URI url) throws Failure, IOException, InterruptedException {
        Reply r = send("GET", url, null, null, LWS_JSON, Map.of());
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        ObjectNode out = json.createObjectNode().put("subscription", url.toString());
        out.set("raw", json.readTree(r.body()));
        return out;
    }

    /** A delivery to the inbox the driver hosts, judged as the reference inbox judges it. */
    private JsonNode verifyNotification(JsonNode args) throws Failure, IOException {
        JsonNode fields = args.path("headers");
        Function<String, String> header = name -> {
            for (Map.Entry<String, JsonNode> e : fields.properties()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    JsonNode v = e.getValue();
                    return v.isArray() ? (v.isEmpty() ? null : v.get(0).asText()) : v.asText();
                }
            }
            return null;
        };
        byte[] body = Base64.getDecoder().decode(args.path("bodyBase64").asText());
        String problem = RefInbox.verify(url(args, "url"), args.path("method").asText(), header, body, flaw, verifierHttp);
        if (problem != null) {
            throw new Failure("verification", null, problem);
        }
        String input = header.apply("Signature-Input");
        Matcher keyid = input == null ? null : java.util.regex.Pattern.compile(";keyid=\"([^\"]*)\"").matcher(input);
        String id = keyid != null && keyid.find() ? keyid.group(1) : "";
        ObjectNode out = json.createObjectNode();
        out.put("storage", id.contains("#") ? id.substring(0, id.indexOf('#')) : id);
        out.put("keyid", id);
        ArrayNode activities = out.putArray("activities");
        JsonNode note = json.readTree(body);
        if (note != null && note.has("type") && note.has("object")) {
            ObjectNode activity = activities.addObject();
            if (note.hasNonNull("id")) {
                activity.put("id", note.path("id").asText());
            }
            activity.set("types", strings(note.path("type")));
            JsonNode object = note.path("object");
            activity.put("object", object.isObject() ? object.path("id").asText() : object.asText());
            activity.set("objectTypes", strings(object.path("type")));
        }
        return out;
    }

    // ---- search ----

    private JsonNode search(URI service, JsonNode query, int limit) throws Failure, IOException, InterruptedException {
        if (!refusedRicher.contains(service.toString())) {
            if (flaw == Flaw.QUERY_WITHOUT_CONTENT_TYPE && !queryFlawShown) {
                queryFlawShown = true;
                send("QUERY", service, null, query.toString(), null, Map.of());
            } else {
                Reply richer = send("QUERY", service, RICHER_QUERY, "SELECT * WHERE { ?s ?p ?o }", null, Map.of());
                if (richer.status() == 415) {
                    refusedRicher.add(service.toString());
                    if (flaw == Flaw.QUERY_KEEPS_REFUSED_FORMAT) {
                        send("QUERY", service, RICHER_QUERY, "SELECT * WHERE { ?s ?p ?o }", null, Map.of());
                    }
                }
            }
        }
        Reply page = send("QUERY", service, BASELINE_QUERY, query.toString(), null, Map.of());
        if (page.status() / 100 != 2) {
            throw failure(page);
        }
        List<String> items = new ArrayList<>();
        collect(page, items);
        boolean restarted = false;
        for (URI next = page.link("next"); next != null && items.size() <= limit; ) {
            Reply got = send("GET", next, null, null, LWS_JSON, Map.of());
            if ((got.status() == 404 || got.status() == 410) && !restarted) {
                restarted = true;
                if (flaw == Flaw.DOES_NOT_RESTART_SEARCH) {
                    send("GET", next, null, null, LWS_JSON, Map.of());
                    break;
                }
                // The page expired: start the search again.
                page = send("QUERY", service, BASELINE_QUERY, query.toString(), null, Map.of());
                if (page.status() / 100 != 2) {
                    throw failure(page);
                }
                items.clear();
                collect(page, items);
                next = page.link("next");
                continue;
            }
            if (got.status() / 100 != 2) {
                throw failure(got);
            }
            collect(got, items);
            next = got.link("next");
        }
        return items(items, limit);
    }

    // ---- shared ----

    private Reply send(String method, URI url, String contentType, String body, String accept, Map<String, String> headers)
            throws IOException, InterruptedException {
        return web.send(method, url, agent, contentType, body, accept, headers);
    }

    private String etag(URI url, String accept) throws Failure, IOException, InterruptedException {
        Reply r = send("GET", url, null, null, accept, Map.of());
        if (r.status() / 100 != 2 || r.header("ETag") == null) {
            throw failure(r);
        }
        return r.header("ETag");
    }

    private JsonNode remove(URI url) throws Failure, IOException, InterruptedException {
        return remove(send("DELETE", url, null, null, null, Map.of()));
    }

    private JsonNode remove(Reply r) throws Failure {
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        return json.createObjectNode();
    }

    private JsonNode created(Reply r) {
        ObjectNode out = json.createObjectNode();
        out.put("location", r.location().toString());
        out.set("metadata", metadata(r));
        return out;
    }

    private JsonNode update(Reply r) throws Failure {
        if (r.status() / 100 != 2) {
            throw failure(r);
        }
        ObjectNode out = json.createObjectNode();
        out.put("status", r.status());
        if (r.header("ETag") != null) {
            out.put("etag", r.header("ETag"));
        }
        out.set("metadata", metadata(r));
        return out;
    }

    private void collect(Reply page, List<String> items) throws IOException {
        JsonNode doc = page.body() == null || page.body().isBlank() ? json.nullNode() : json.readTree(page.body());
        for (JsonNode item : doc.path("items")) {
            String id = item.isTextual() ? item.asText() : item.path("id").asText(null);
            if (id != null) {
                items.add(page.url().resolve(id).toString());
            }
        }
    }

    private JsonNode items(List<String> items, int limit) {
        ObjectNode out = json.createObjectNode();
        ArrayNode list = out.putArray("items");
        items.stream().limit(limit).forEach(id -> list.addObject().put("id", id));
        out.put("truncated", items.size() > limit);
        return out;
    }

    /** What a response's headers say (PROTOCOL.md section 4.1, Metadata). */
    private ObjectNode metadata(Reply r) {
        ObjectNode m = json.createObjectNode();
        m.put("url", r.url().toString());
        m.put("status", r.status());
        for (String[] h : new String[][] {{"ETag", "etag"}, {"Last-Modified", "lastModified"}, {"Content-Type", "contentType"}}) {
            if (r.header(h[0]) != null) {
                m.put(h[1], r.header(h[0]));
            }
        }
        if (r.header("Content-Length") != null) {
            m.put("contentLength", Long.parseLong(r.header("Content-Length").trim()));
        }
        ArrayNode links = m.putArray("links");
        ArrayNode types = m.putArray("types");
        for (Map.Entry<String, List<String>> e : r.headers().entrySet()) {
            if (!e.getKey().equalsIgnoreCase("Link")) {
                continue;
            }
            for (String field : e.getValue()) {
                Matcher l = RefHttp.LINK.matcher(field);
                while (l.find()) {
                    Matcher rel = RefHttp.REL.matcher(l.group(2));
                    if (!rel.find()) {
                        continue;
                    }
                    String href = r.url().resolve(l.group(1)).toString();
                    ObjectNode params = json.createObjectNode();
                    for (String p : l.group(2).split(";")) {
                        int eq = p.indexOf('=');
                        String name = eq < 0 ? "" : p.substring(0, eq).trim();
                        if (!name.isEmpty() && !name.equalsIgnoreCase("rel")) {
                            params.put(name, p.substring(eq + 1).trim().replace("\"", ""));
                        }
                    }
                    for (String each : rel.group(1).trim().split("\\s+")) {
                        ObjectNode link = links.addObject().put("href", href).put("rel", each);
                        link.set("params", params);
                        if (each.equals("type")) {
                            types.add(href);
                        }
                    }
                }
            }
        }
        m.set("allow", list(r.header("Allow")));
        m.set("acceptPatch", list(r.header("Accept-Patch")));
        return m;
    }

    private ArrayNode list(String header) {
        ArrayNode out = json.createArrayNode();
        if (header != null) {
            for (String v : header.split(",")) {
                if (!v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private ArrayNode strings(JsonNode value) {
        ArrayNode out = json.createArrayNode();
        (value.isArray() ? value : value.isMissingNode() ? json.createArrayNode() : json.createArrayNode().add(value))
                .forEach(n -> out.add(n.asText()));
        return out;
    }

    private Failure failure(Reply r) {
        return new Failure("http", r.status(), r.url() + " answered " + r.status());
    }

    private static boolean textual(String contentType) {
        String t = contentType.toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("text/") || t.startsWith("application/json") || t.startsWith("application/xml")
                || t.contains("+json") || t.contains("+xml");
    }

    /** The request body and its content type, from a body argument (PROTOCOL.md section 4). */
    private String[] body(JsonNode args) throws Failure, IOException {
        JsonNode body = args.path("body");
        String type = args.hasNonNull("contentType") ? args.path("contentType").asText() : null;
        if (body.isMissingNode() || body.isNull()) {
            return new String[] {type, null};
        }
        if (body.has("text")) {
            return new String[] {type == null ? "text/plain" : type, body.path("text").asText()};
        }
        if (body.has("json")) {
            return new String[] {type == null ? "application/json" : type, json.writeValueAsString(body.path("json"))};
        }
        throw new Failure("unsupported", null, "this client sends text and JSON bodies only");
    }

    private ObjectNode document(JsonNode args, String name) {
        JsonNode doc = args.path(name);
        if (!doc.isObject()) {
            throw new IllegalArgumentException(name + " is not an object");
        }
        return (ObjectNode) doc.deepCopy();
    }

    private static URI url(JsonNode args, String name) {
        String s = args.path(name).asText(null);
        if (s == null || !URI.create(s).isAbsolute()) {
            throw new IllegalArgumentException(name + " is not an absolute URL");
        }
        return URI.create(s);
    }

    private static String text(JsonNode args, String name) {
        return args.hasNonNull(name) ? args.path(name).asText() : null;
    }

    private static int limit(JsonNode args) {
        return args.hasNonNull("limit") ? args.path("limit").asInt() : 1000;
    }
}
