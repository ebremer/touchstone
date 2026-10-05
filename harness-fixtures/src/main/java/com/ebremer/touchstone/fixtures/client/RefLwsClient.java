package com.ebremer.touchstone.fixtures.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A scripted LWS client that does what the client rules ask (CLIENT-TESTING.md section 9): it
 * reads before it writes, makes its writes conditional, follows the links it is given, composes
 * access requests, grants and subscriptions as the drafts define them, and falls back to the
 * baseline search format when a richer one is refused. It starts each rule's task before doing
 * what the task asks, and handles the faults the tasks arm: it checks a container before
 * retrying a lost create, re-reads a linkset that refused it, and restarts a search whose page
 * was refused. Its script touches every rule of phases C2 and C3, so a session judging it has a
 * trial for each.
 *
 * <p>It reports nothing itself: the session judges it. With a {@link Flaw} it is a broken twin,
 * which gets exactly one thing wrong and is otherwise the same client.
 */
public final class RefLwsClient {

    /** The one thing a broken twin gets wrong. */
    public enum Flaw {
        /** None: the reference client. */
        NONE,
        /** Every request also carries the token in a header of the client's own. */
        TOKEN_ALSO_IN_OWN_HEADER,
        /** Also replaces a container's linkset, whose Allow does not list PUT. */
        LINKSET_PUT_UNADVERTISED,
        /** Patches the linkset in JSON Patch, which Accept-Patch does not list. */
        LINKSET_PATCH_FORMAT_UNADVERTISED,
        /** Replaces the note without If-Match. */
        PUT_UNCONDITIONAL,
        /** Patches the linkset without If-Match. */
        LINKSET_WRITE_UNCONDITIONAL,
        /** Builds the second page's URL from the container's instead of following next. */
        BUILDS_PAGE_URL,
        /** Also requests a note by joining a name to its container's URL. */
        BUILDS_MEMBER_URL,
        /** Gives the grant's @context as a string. */
        ACCESS_CONTEXT_NOT_ARRAY,
        /** Types the access request AccessGrant. */
        ACCESS_REQUEST_WRONG_TYPE,
        /** Leaves the grant's type out. */
        ACCESS_GRANT_WITHOUT_TYPE,
        /** Leaves the grant's storage out. */
        ACCESS_WITHOUT_STORAGE,
        /** Gives the grant's access as one object, not an array. */
        ACCESS_NOT_ARRAY,
        /** Leaves the grant's policy type out. */
        POLICY_WITHOUT_TYPE,
        /** Grants the action write, which the drafts do not define. */
        POLICY_UNKNOWN_ACTION,
        /** Names the assignee "bob" instead of by URI. */
        POLICY_ASSIGNEE_NOT_URI,
        /** Gives the policy target as a bare URL. */
        POLICY_TARGET_NOT_OBJECT,
        /** Leaves a constraint's operator out. */
        CONSTRAINT_WITHOUT_OPERATOR,
        /** Gives the grant an inbox that is not a URI. */
        ACCESS_INBOX_NOT_URI,
        /** Deletes a note without If-Match. */
        DELETE_UNCONDITIONAL,
        /** Sends the subscription as application/json. */
        SUBSCRIPTION_AS_PLAIN_JSON,
        /** Asks for a subscription type the service does not advertise. */
        SUBSCRIPTION_UNADVERTISED_TYPE,
        /** Gives the topic as one URL, not an array. */
        SUBSCRIPTION_TOPIC_NOT_ARRAY,
        /** Leaves the webhook subscription's inbox out. */
        SUBSCRIPTION_WITHOUT_INBOX,
        /** Sends its first search without a Content-Type. */
        QUERY_WITHOUT_CONTENT_TYPE,
        /** Repeats its search in the refused format before falling back. */
        QUERY_KEEPS_REFUSED_FORMAT,
        /** Answers the task to create a container with a POST that has no Link rel="type", then tries again. */
        CREATES_CONTAINER_WITHOUT_TYPE_LINK,
        /** Deletes the container and its contents without Depth: infinity. */
        DELETES_CONTAINER_WITHOUT_DEPTH,
        /** Sends the refused linkset PUT again, unchanged. */
        REPEATS_REFUSED_PUT,
        /** POSTs the lost create again at once, unchanged, without checking the container. */
        RETRIES_LOST_CREATE_BLINDLY,
        /** Asks for the refused page of search results again instead of restarting the search. */
        DOES_NOT_RESTART_SEARCH
    }

    /** Starts the task of a client rule in the client's session, as a developer would on the session page. */
    @FunctionalInterface
    public interface Tasks {
        /** For a client with no session API to call: tasks are never started, so their rules stay untested. */
        Tasks NONE = rule -> { };

        void start(String rule) throws IOException, InterruptedException;
    }

    /** An agent the client acts for: its access token and its WebID. */
    public record Agent(String token, String webid) {
    }

    private static final String LWS = "https://www.w3.org/ns/lws#";
    private static final String LWS_JSON = "application/lws+json";
    private static final String MERGE_PATCH = "application/merge-patch+json";
    private static final String LINKSET_JSON = "application/linkset+json";
    private static final String BASELINE_QUERY = "application/lws-query+json";
    private static final String RICHER_QUERY = "application/sparql-query";
    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;[^,<]*)*)");
    private static final Pattern REL = Pattern.compile("(?i)\\brel\\s*=\\s*\"?([^\";,]+)\"?");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI storage;
    private final Agent alice;
    private final Agent bob;
    private final URI inbox;
    private final Flaw flaw;
    private final Tasks tasks;

    /**
     * @param storage the storage URL, the only URL the client is given
     * @param alice   the storage's owner
     * @param bob     another agent, who asks alice for access
     * @param inbox   where the client wants notifications, for its subscription and its grant
     * @param tasks   starts a rule's task in the session
     */
    public RefLwsClient(URI storage, Agent alice, Agent bob, URI inbox, Flaw flaw, Tasks tasks) {
        this.storage = storage;
        this.alice = alice;
        this.bob = bob;
        this.inbox = inbox;
        this.flaw = flaw;
        this.tasks = tasks;
    }

    /** Runs the whole script. */
    public void run() throws IOException, InterruptedException {
        // The storage description names the services.
        Map<String, URI> services = services(send("GET", storage, alice, null, null, "application/lws+cid", Map.of()));

        // A container with six notes, so that its listing has two pages.
        tasks.start("client-create-container-type-link");
        if (flaw == Flaw.CREATES_CONTAINER_WITHOUT_TYPE_LINK) {
            send("POST", storage, alice, null, null, null, Map.of());
        }
        Reply made = send("POST", storage, alice, null, null, null,
                Map.of("Link", "<" + LWS + "Container>; rel=\"type\""));
        URI notes = made.location();
        List<URI> created = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            created.add(send("POST", notes, alice, "text/plain", "note " + i, null, Map.of()).location());
        }
        // One more, whose answer the session loses: the listing that follows is the check that
        // shows whether it was created before anything is sent again.
        tasks.start("client-no-blind-retry-of-create");
        Reply lost = send("POST", notes, alice, "text/plain", "note 6", null, Map.of());
        if (lost.status() / 100 == 5 && flaw == Flaw.RETRIES_LOST_CREATE_BLINDLY) {
            send("POST", notes, alice, "text/plain", "note 6", null, Map.of());
        }
        Reply listing = send("GET", notes, alice, null, null, LWS_JSON, Map.of());
        if (flaw == Flaw.BUILDS_PAGE_URL) {
            send("GET", URI.create(notes + "?page=2"), alice, null, null, LWS_JSON, Map.of());
        } else {
            for (URI next = listing.link("next"); next != null; ) {
                next = send("GET", next, alice, null, null, LWS_JSON, Map.of()).link("next");
            }
        }
        if (flaw == Flaw.BUILDS_MEMBER_URL) {
            send("GET", notes.resolve("note-0.txt"), alice, null, null, null, Map.of());
        }

        // Replace a note, conditionally on what was read.
        URI note = created.getFirst();
        Reply read = send("GET", note, alice, null, null, null, Map.of());
        send("PUT", note, alice, "text/plain", "note 0, edited", null,
                flaw == Flaw.PUT_UNCONDITIONAL ? Map.of() : Map.of("If-Match", read.etag()));

        // Its linkset: patched in a format Accept-Patch lists, then replaced, since Allow lists
        // PUT for it; both conditional.
        URI linkset = read.link("linkset");
        Reply ls = send("GET", linkset, alice, null, null, LINKSET_JSON, Map.of());
        boolean jsonPatch = flaw == Flaw.LINKSET_PATCH_FORMAT_UNADVERTISED;
        send("PATCH", linkset, alice, jsonPatch ? "application/json-patch+json" : MERGE_PATCH, jsonPatch ? "[]" : "{}",
                null, flaw == Flaw.LINKSET_WRITE_UNCONDITIONAL ? Map.of() : Map.of("If-Match", ls.etag()));
        Reply again = send("GET", linkset, alice, null, null, LINKSET_JSON, Map.of());
        if (again.allows("PUT")) {
            tasks.start("client-no-repeat-after-405-415");
            Reply put = send("PUT", linkset, alice, LINKSET_JSON, again.body(), null, Map.of("If-Match", again.etag()));
            if (put.status() == 405 && flaw == Flaw.REPEATS_REFUSED_PUT) {
                send("PUT", linkset, alice, LINKSET_JSON, again.body(), null, Map.of("If-Match", again.etag()));
            } else if (put.status() == 405) {
                // Refused after all: read what the linkset supports now, and go by that.
                Reply now = send("GET", linkset, alice, null, null, LINKSET_JSON, Map.of());
                if (now.allows("PUT")) {
                    send("PUT", linkset, alice, LINKSET_JSON, now.body(), null, Map.of("If-Match", now.etag()));
                }
            }
        }
        if (flaw == Flaw.LINKSET_PUT_UNADVERTISED) {
            URI containerLinkset = send("GET", notes, alice, null, null, LWS_JSON, Map.of()).link("linkset");
            Reply cls = send("GET", containerLinkset, alice, null, null, LINKSET_JSON, Map.of());
            send("PUT", containerLinkset, alice, LINKSET_JSON, cls.body(), null, Map.of("If-Match", cls.etag()));
        }

        // alice grants bob read access to the note; bob asks to modify it.
        send("POST", services.get("AccessGrantService"), alice, LWS_JSON, grant(note), null, Map.of());
        send("POST", services.get("AccessRequestService"), bob, LWS_JSON, request(note), null, Map.of());

        // A webhook subscription to the container.
        send("POST", services.get("NotificationService"), alice,
                flaw == Flaw.SUBSCRIPTION_AS_PLAIN_JSON ? "application/json" : LWS_JSON, subscription(notes), null, Map.of());

        // A search: first in a richer format, then, refused, in the baseline every server accepts.
        URI search = services.get("TypeSearchService");
        if (flaw == Flaw.QUERY_WITHOUT_CONTENT_TYPE) {
            send("QUERY", search, alice, null, "{}", null, Map.of());
        } else {
            send("QUERY", search, alice, RICHER_QUERY, "SELECT * WHERE { ?s ?p ?o }", null, Map.of());
        }
        if (flaw == Flaw.QUERY_KEEPS_REFUSED_FORMAT) {
            send("QUERY", search, alice, RICHER_QUERY, "SELECT * WHERE { ?s ?p ?o }", null, Map.of());
        }
        Reply results = send("QUERY", search, alice, BASELINE_QUERY, "{}", null, Map.of());
        // The index may lag behind writes; a client tolerates that and asks again.
        for (int i = 0; i < 10 && results.link("next") == null; i++) {
            Thread.sleep(500);
            results = send("QUERY", search, alice, BASELINE_QUERY, "{}", null, Map.of());
        }
        URI nextPage = results.link("next");
        if (nextPage != null) {
            tasks.start("client-restart-after-refused-page");
            Reply page = send("GET", nextPage, alice, null, null, LWS_JSON, Map.of());
            if (page.status() == 404 || page.status() == 410) {
                if (flaw == Flaw.DOES_NOT_RESTART_SEARCH) {
                    send("GET", nextPage, alice, null, null, LWS_JSON, Map.of());
                } else {
                    send("QUERY", search, alice, BASELINE_QUERY, "{}", null, Map.of());
                }
            }
        }

        // Delete a note, conditionally on what was read.
        URI doomed = created.get(1);
        Reply before = send("GET", doomed, alice, null, null, null, Map.of());
        send("DELETE", doomed, alice, null, null, null,
                flaw == Flaw.DELETE_UNCONDITIONAL ? Map.of() : Map.of("If-Match", before.etag()));

        // Last, the container and everything in it.
        tasks.start("client-delete-container-depth");
        Reply container = send("GET", notes, alice, null, null, LWS_JSON, Map.of());
        Map<String, String> conditions = new LinkedHashMap<>();
        conditions.put("If-Match", container.etag());
        if (flaw != Flaw.DELETES_CONTAINER_WITHOUT_DEPTH) {
            conditions.put("Depth", "infinity");
        }
        send("DELETE", notes, alice, null, null, null, conditions);
    }

    // ---- documents ----

    private String grant(URI note) throws IOException {
        ObjectNode doc = json.createObjectNode();
        if (flaw == Flaw.ACCESS_CONTEXT_NOT_ARRAY) {
            doc.put("@context", "https://www.w3.org/ns/lws/v1");
        } else {
            doc.putArray("@context").add("https://www.w3.org/ns/lws/v1");
        }
        if (flaw != Flaw.ACCESS_GRANT_WITHOUT_TYPE) {
            doc.putArray("type").add("AccessGrant");
        }
        if (flaw != Flaw.ACCESS_WITHOUT_STORAGE) {
            doc.put("storage", storage.toString());
        }
        doc.put("inbox", flaw == Flaw.ACCESS_INBOX_NOT_URI ? "the inbox" : inbox.toString());
        ObjectNode policy = json.createObjectNode();
        if (flaw != Flaw.POLICY_WITHOUT_TYPE) {
            policy.putArray("type").add("AccessPolicy");
        }
        policy.putArray("action").add(flaw == Flaw.POLICY_UNKNOWN_ACTION ? "write" : "read");
        policy.put("assignee", flaw == Flaw.POLICY_ASSIGNEE_NOT_URI ? "bob" : bob.webid());
        if (flaw == Flaw.POLICY_TARGET_NOT_OBJECT) {
            policy.put("target", note.toString());
        } else {
            ObjectNode target = policy.putObject("target");
            target.put("type", "StorageResource");
            target.putArray("value").add(note.toString());
        }
        ObjectNode constraint = policy.putArray("constraint").addObject();
        constraint.put("leftOperand", "dateTime");
        if (flaw != Flaw.CONSTRAINT_WITHOUT_OPERATOR) {
            constraint.put("operator", "lt");
        }
        constraint.put("rightOperand", "2099-01-01T00:00:00Z");
        if (flaw == Flaw.ACCESS_NOT_ARRAY) {
            doc.set("access", policy);
        } else {
            doc.putArray("access").add(policy);
        }
        return json.writeValueAsString(doc);
    }

    private String request(URI note) throws IOException {
        ObjectNode doc = json.createObjectNode();
        doc.putArray("@context").add("https://www.w3.org/ns/lws/v1");
        doc.putArray("type").add(flaw == Flaw.ACCESS_REQUEST_WRONG_TYPE ? "AccessGrant" : "AccessRequest");
        doc.put("storage", storage.toString());
        ObjectNode policy = doc.putArray("access").addObject();
        policy.putArray("type").add("AccessPolicy");
        policy.putArray("action").add("modify");
        policy.put("assignee", bob.webid());
        ObjectNode target = policy.putObject("target");
        target.put("type", "StorageResource");
        target.putArray("value").add(note.toString());
        return json.writeValueAsString(doc);
    }

    private String subscription(URI topic) throws IOException {
        ObjectNode doc = json.createObjectNode();
        doc.put("type", flaw == Flaw.SUBSCRIPTION_UNADVERTISED_TYPE ? "Webhook" : "WebhookSubscription");
        if (flaw == Flaw.SUBSCRIPTION_TOPIC_NOT_ARRAY) {
            doc.put("topic", topic.toString());
        } else {
            doc.putArray("topic").add(topic.toString());
        }
        if (flaw != Flaw.SUBSCRIPTION_WITHOUT_INBOX) {
            doc.put("inbox", inbox.toString());
        }
        return json.writeValueAsString(doc);
    }

    private Map<String, URI> services(Reply description) throws IOException {
        Map<String, URI> out = new HashMap<>();
        for (JsonNode s : json.readTree(description.body()).path("service")) {
            JsonNode types = s.path("type");
            for (JsonNode t : types.isArray() ? types : json.createArrayNode().add(types)) {
                out.putIfAbsent(t.asText(), description.url().resolve(s.path("serviceEndpoint").asText()));
            }
        }
        return out;
    }

    // ---- HTTP ----

    /** An answer, and what the client reads from it. */
    private record Reply(URI url, int status, Map<String, List<String>> headers, String body) {

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }

        URI location() {
            String l = header("Location");
            if (l == null) {
                throw new IllegalStateException(url + " answered " + status + " without a Location");
            }
            return url.resolve(l);
        }

        String etag() {
            String etag = header("ETag");
            if (etag == null) {
                throw new IllegalStateException(url + " answered " + status + " without an ETag");
            }
            return etag;
        }

        boolean allows(String method) {
            String allow = header("Allow");
            if (allow == null) {
                return false;
            }
            for (String m : allow.split(",")) {
                if (m.trim().equals(method)) {
                    return true;
                }
            }
            return false;
        }

        /** The target of the first Link with this relation, resolved, or null. */
        URI link(String rel) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (!e.getKey().equalsIgnoreCase("Link")) {
                    continue;
                }
                for (String field : e.getValue()) {
                    Matcher m = LINK.matcher(field);
                    while (m.find()) {
                        Matcher r = REL.matcher(m.group(2));
                        if (r.find()) {
                            for (String each : r.group(1).trim().split("\\s+")) {
                                if (each.equalsIgnoreCase(rel)) {
                                    return url.resolve(m.group(1));
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    private Reply send(String method, URI url, Agent as, String contentType, String body, String accept,
                       Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Authorization", "Bearer " + as.token());
        if (flaw == Flaw.TOKEN_ALSO_IN_OWN_HEADER) {
            b.header("X-Access-Token", as.token());
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (accept != null) {
            b.header("Accept", accept);
        }
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(url, r.statusCode(), new LinkedHashMap<>(r.headers().map()), r.body());
    }
}
