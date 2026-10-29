package com.ebremer.touchstone.fixtures.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.fixtures.client.RefHttp.Reply;
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
 * was refused. It gives a create only user-managed metadata as Link headers, and sends Link headers
 * with an update only with Prefer: set-linkset. Its script touches every client rule, so a session
 * judging it has a trial for each.
 *
 * <p>It authenticates the four ways a session offers (CLIENT-TESTING.md section 3): alice starts
 * with a token the session handed out, and when the storage refuses it, she signs a credential with
 * her own key (the CID suite); bob signs in at his OpenID Provider (the OpenID suite), and later
 * presents a SAML 2.0 assertion from the session's identity provider (the SAML suite). Each time
 * the client finds the authorization server through the protected resource metadata the storage's
 * 401 names (RFC 9728), makes the discovery checks of lws10-core section 5.2.3, and exchanges the
 * credential for a token for the storage the 401 links to.
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
        /** Patches the linkset in JSON Merge Patch, which Accept-Patch does not list. */
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
        DOES_NOT_RESTART_SEARCH,
        /** Presents its first self-issued credential unsigned, alg none. */
        CID_UNSIGNED,
        /** Leaves sub out of its first self-issued credential. */
        CID_WITHOUT_SUB,
        /** Leaves iss out of its first self-issued credential. */
        CID_WITHOUT_ISS,
        /** Leaves client_id out of its first self-issued credential. */
        CID_WITHOUT_CLIENT_ID,
        /** Names another URI as client_id in its first self-issued credential. */
        CID_CLIENT_ID_DIFFERS,
        /** Restricts its first self-issued credential to another audience. */
        CID_AUDIENCE_WITHOUT_AS,
        /** Leaves aud out of its first self-issued credential. */
        CID_WITHOUT_AUDIENCE,
        /** Leaves exp out of its first self-issued credential. */
        CID_WITHOUT_EXP,
        /** Leaves iat out of its first self-issued credential. */
        CID_WITHOUT_IAT,
        /** Presents its first self-issued credential with the id_token token type. */
        CID_TYPED_AS_ID_TOKEN,
        /** Presents its first ID Token with the jwt token type. */
        ID_TOKEN_TYPED_AS_JWT,
        /** Leaves resource out of its first token request. */
        TOKEN_REQUEST_WITHOUT_RESOURCE,
        /** Sends its first credential as assertion instead of subject_token. */
        TOKEN_REQUEST_WITHOUT_SUBJECT_TOKEN,
        /**
         * Asks for a token for the storage the decoy's 401 links to, without checking that the
         * storage's root is on the decoy's origin.
         */
        SKIPS_DISCOVERY_CHECKS,
        /** Its inbox does not verify the signature itself; it makes every other check. */
        INBOX_SKIPS_SIGNATURE_CHECK,
        /** Its inbox does not check the body against the Content-Digest the signature covers. */
        INBOX_SKIPS_DIGEST_CHECK,
        /** Its inbox takes a keyid without a fragment, and then the storage description's only key. */
        INBOX_ACCEPTS_KEYID_WITHOUT_FRAGMENT,
        /** Its inbox does not check that the key's document has the keyid's URL as its id. */
        INBOX_SKIPS_STORAGE_ID_CHECK,
        /** Its inbox acknowledges every notification, verifying nothing. */
        INBOX_ACCEPTS_EVERYTHING,
        /** Its inbox refuses every notification, genuine ones too. */
        INBOX_REFUSES_EVERYTHING,
        /** Gives its second subscription the first one's inbox. */
        SHARES_INBOX,
        /** Names a linkset of its own choosing in a Link header of its first note's create. */
        CREATES_WITH_LINKSET_LINK,
        /** Answers the task to update a note's content and metadata together with Link headers but no Prefer: set-linkset. */
        UPDATES_LINKS_WITHOUT_PREFER,
        /** Presents its first SAML assertion with the jwt token type. */
        SAML_TYPED_AS_JWT
    }


    /** Starts the task of a client rule in the client's session, as a developer would on the session page. */
    @FunctionalInterface
    public interface Tasks {
        /** For a client with no session API to call: tasks are never started, so their rules stay untested. */
        Tasks NONE = rule -> { };

        void start(String rule) throws IOException, InterruptedException;
    }

    /** Hands out a fresh SAML 2.0 assertion about an agent, base64url-encoded, as its identity provider would. */
    @FunctionalInterface
    public interface Assertions {
        String next() throws IOException, InterruptedException;
    }

    /**
     * An agent the client acts for, and how it authenticates.
     *
     * @param cidUrl its CID URL, the URL of its identity document
     * @param token an access token the session handed out, or null
     * @param key the private JWK of the verification method its identity document lists, for
     *     self-issued credentials, or null
     * @param login how it signs in at its OpenID Provider, or null
     * @param assertions where its SAML assertions come from, or null; with a login too, the agent
     *     signs in with OpenID first and presents an assertion after that
     */
    public record Agent(String cidUrl, String token, String key, Login login, Assertions assertions) {

        public Agent(String cidUrl, String token, String key, Login login) {
            this(cidUrl, token, key, login, null);
        }
    }

    /** A sign-in at an OpenID Provider, as a client registered there with a redirect URI. */
    public record Login(String clientId, URI redirectUri, String username, String password) {
    }

    private static final String LWS = "https://www.w3.org/ns/lws#";
    /** A class of the client's own for its notes: user-managed metadata, which a create or update may carry. */
    private static final String NOTE_TYPE = "https://schema.org/NoteDigitalDocument";
    private static final String LWS_JSON = "application/lws+json";
    private static final String JSON_PATCH = "application/json-patch+json";
    private static final String LINKSET_JSON = "application/linkset+json";
    private static final String BASELINE_QUERY = "application/lws-query+json";
    private static final String RICHER_QUERY = "application/sparql-query";

    private final ObjectMapper json = new ObjectMapper();
    private final URI storage;
    private final Agent alice;
    private final Agent bob;
    private final RefInbox inbox;
    private final Flaw flaw;
    private final Tasks tasks;
    /** Its HTTP and authentication ({@link RefHttp}), where the authentication flaws live. */
    private final RefHttp web;

    /**
     * @param storage the storage URL, the only URL the client is given
     * @param alice   the storage's owner
     * @param bob     another agent, who asks alice for access
     * @param inbox   where the client receives notifications, for its subscriptions and its grant
     * @param tasks   starts a rule's task in the session
     */
    public RefLwsClient(URI storage, Agent alice, Agent bob, RefInbox inbox, Flaw flaw, Tasks tasks) {
        this.storage = storage;
        this.alice = alice;
        this.bob = bob;
        this.inbox = inbox;
        this.flaw = flaw;
        this.tasks = tasks;
        this.web = new RefHttp(flaw);
        for (Agent a : List.of(alice, bob)) {
            if (a.token() != null) {
                web.token(a, a.token());
            }
        }
    }

    /** Runs the whole script. */
    public void run() throws IOException, InterruptedException {
        // The root container lists the decoy first, whose 401 links to a storage whose root is on
        // another origin. Meanwhile the session refuses alice's token once, as expired, and she
        // authenticates again with her own key.
        tasks.start("client-token-after-discovery-checks");
        Reply root = send("GET", storage, alice, null, null, LWS_JSON, Map.of());
        JsonNode items = root.status() == 200 ? json.readTree(root.body()).path("items") : json.createArrayNode();
        if (!items.isEmpty()) {
            send("GET", root.url().resolve(items.get(0).path("id").asText()), alice, null, null, null, Map.of());
        }

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
            // The first note carries user-managed metadata of its own, a type, as a create may.
            Map<String, String> links = i > 0 ? Map.of() : Map.of("Link", flaw == Flaw.CREATES_WITH_LINKSET_LINK
                    ? "<" + NOTE_TYPE + ">; rel=\"type\", <https://linkset.invalid/forged>; rel=\"linkset\""
                    : "<" + NOTE_TYPE + ">; rel=\"type\"");
            created.add(send("POST", notes, alice, "text/plain", "note " + i, null, links).location());
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
        boolean mergePatch = flaw == Flaw.LINKSET_PATCH_FORMAT_UNADVERTISED;
        send("PATCH", linkset, alice, mergePatch ? "application/merge-patch+json" : JSON_PATCH, mergePatch ? "{}" : "[]",
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

        // The note's content and its metadata in one request: Link headers, which only count as
        // metadata with Prefer: set-linkset.
        tasks.start("client-combined-update-prefer-set-linkset");
        Reply current = send("GET", note, alice, null, null, null, Map.of());
        Map<String, String> combined = new LinkedHashMap<>();
        combined.put("If-Match", current.etag());
        combined.put("Link", "<" + NOTE_TYPE + ">; rel=\"type\"");
        if (flaw != Flaw.UPDATES_LINKS_WITHOUT_PREFER) {
            combined.put("Prefer", "set-linkset");
        }
        send("PUT", note, alice, "text/plain", "note 0, edited again", null, combined);

        // alice grants bob read access to the note; bob asks to modify it.
        send("POST", services.get("AccessGrantService"), alice, LWS_JSON, grant(note), null, Map.of());
        send("POST", services.get("AccessRequestService"), bob, LWS_JSON, request(note), null, Map.of());

        // bob reads the note, this time with a SAML assertion: his token is gone, so the storage
        // answers 401, and the client exchanges an assertion for a new one.
        if (bob.assertions() != null) {
            web.forget(bob);
            send("GET", note, bob, null, null, null, Map.of());
        }

        // Webhook subscriptions to the container and to a note, each with an inbox of its own.
        String subscriptionType = flaw == Flaw.SUBSCRIPTION_AS_PLAIN_JSON ? "application/json" : LWS_JSON;
        boolean subscribed = send("POST", services.get("NotificationService"), alice, subscriptionType,
                subscription(notes, inbox.uri("notes")), null, Map.of()).status() == 201;
        send("POST", services.get("NotificationService"), alice, subscriptionType,
                subscription(created.get(2), inbox.uri(flaw == Flaw.SHARES_INBOX ? "notes" : "note")), null, Map.of());

        // Each new note is announced to the container's inbox. Four tasks make the session forge
        // that notification, and the inbox must refuse it.
        if (subscribed) {
            for (String rule : List.of("client-inbox-refuses-unpublished-key", "client-inbox-refuses-altered-body",
                    "client-inbox-refuses-keyid-without-fragment", "client-inbox-refuses-foreign-key-document")) {
                tasks.start(rule);
                int before = inbox.received();
                send("POST", notes, alice, "text/plain", "a note for " + rule, null, Map.of());
                inbox.awaitReceived(before + 1, Duration.ofSeconds(10));
            }
        }

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
        doc.put("inbox", flaw == Flaw.ACCESS_INBOX_NOT_URI ? "the inbox" : inbox.uri("grants").toString());
        ObjectNode policy = json.createObjectNode();
        if (flaw != Flaw.POLICY_WITHOUT_TYPE) {
            policy.putArray("type").add("AccessPolicy");
        }
        policy.putArray("action").add(flaw == Flaw.POLICY_UNKNOWN_ACTION ? "write" : "read");
        policy.put("assignee", flaw == Flaw.POLICY_ASSIGNEE_NOT_URI ? "bob" : bob.cidUrl());
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
        policy.put("assignee", bob.cidUrl());
        ObjectNode target = policy.putObject("target");
        target.put("type", "StorageResource");
        target.putArray("value").add(note.toString());
        return json.writeValueAsString(doc);
    }

    private String subscription(URI topic, URI to) throws IOException {
        ObjectNode doc = json.createObjectNode();
        doc.put("type", flaw == Flaw.SUBSCRIPTION_UNADVERTISED_TYPE ? "Webhook" : "WebhookSubscription");
        if (flaw == Flaw.SUBSCRIPTION_TOPIC_NOT_ARRAY) {
            doc.put("topic", topic.toString());
        } else {
            doc.putArray("topic").add(topic.toString());
        }
        if (flaw != Flaw.SUBSCRIPTION_WITHOUT_INBOX) {
            doc.put("inbox", to.toString());
        }
        return json.writeValueAsString(doc);
    }

    private Map<String, URI> services(Reply description) throws IOException {
        return web.services(description);
    }

    private Reply send(String method, URI url, Agent as, String contentType, String body, String accept,
                       Map<String, String> headers) throws IOException, InterruptedException {
        return web.send(method, url, as, contentType, body, accept, headers);
    }
}
