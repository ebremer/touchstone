package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.DriveStep;
import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.ebremer.touchstone.driver.AdapterLauncher.Adapter;
import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.BrokenAdapterException;
import com.ebremer.touchstone.driver.protocol.Hello;
import com.ebremer.touchstone.driver.protocol.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a client through a client session (OBSERVATION.md section 12, CLIENT-TESTING.md section
 * 14): it starts the client's adapter and a session marked driven, configures the client as alice,
 * finds the storage through the client, and runs every rule's drive in scope, each in a container
 * of its own. The session judges the traffic, as always; the driver only adds a note beside each
 * rule saying how its drive went.
 */
public final class Driver {

    private static final Logger LOG = LoggerFactory.getLogger(Driver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The areas of a session (CLIENT-TESTING.md section 3). */
    public static final List<String> AREAS = List.of("core", "authentication", "notifications", "index");

    /**
     * How to drive.
     *
     * @param areas            the areas in scope
     * @param rules            the names of the rules to drive, or null for every rule in scope
     * @param operationTimeout how long one operation may take
     * @param inboxHost        the address the driver's inbox binds
     * @param inboxPort        its port, or 0 for a free one
     * @param inboxPublicBase  its base as the session reaches it, ending in {@code /inbox/}, or null
     * @param pauses           whether drives pause where they say ({@code waitSeconds}); a session whose
     *                         index does not lag behind writes needs no pause
     */
    public record Options(List<String> areas, Set<String> rules, Duration operationTimeout, String inboxHost,
            int inboxPort, URI inboxPublicBase, boolean pauses) {

        public static Options defaults() {
            return new Options(AREAS, null, Duration.ofSeconds(60), "127.0.0.1", 0, null, true);
        }

        public Options withRules(Set<String> names) {
            return new Options(areas, names, operationTimeout, inboxHost, inboxPort, inboxPublicBase, pauses);
        }

        public Options withAreas(List<String> chosen) {
            return new Options(chosen, rules, operationTimeout, inboxHost, inboxPort, inboxPublicBase, pauses);
        }

        public Options withoutPauses() {
            return new Options(areas, rules, operationTimeout, inboxHost, inboxPort, inboxPublicBase, false);
        }
    }

    /**
     * What a driven session came to.
     *
     * @param client      the client under test, as its adapter's hello names it
     * @param sessionId   the session's id
     * @param pageWithKey the session page with its key, which opens it: for the operator only
     * @param results     the session's results (CLIENT-TESTING.md section 4.4)
     * @param notes       how each rule's drive went, in manifest order
     */
    public record Report(Hello.ClientUnderTest client, String sessionId, String pageWithKey, JsonNode results,
            List<DriveNote> notes, String earl, String junit) {

        /** Whether a MUST rule failed or could not be decided. */
        public boolean mustFailed() {
            JsonNode v = results.path("verdict");
            return v.path("mustFailed").asInt() > 0 || v.path("mustCantTell").asInt() > 0;
        }

        /** The results with each rule's note beside it, as the driver's JSON report. */
        public ObjectNode withNotes() {
            ObjectNode out = results.deepCopy();
            Map<String, DriveNote> byRule = new HashMap<>();
            notes.forEach(n -> byRule.put(n.rule(), n));
            out.get("rules").forEach(r -> {
                DriveNote n = byRule.get(r.path("rule").asText());
                if (n != null) {
                    ObjectNode drive = ((ObjectNode) r).putObject("drive");
                    drive.put("status", n.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
                    if (n.step() > 0) {
                        drive.put("step", n.step());
                    }
                    if (n.reason() != null) {
                        drive.put("reason", n.reason());
                    }
                }
            });
            return out;
        }
    }

    private Driver() {
    }

    /**
     * Drives one client through one new session.
     *
     * @param launcher starts the client's adapter, as configuration registers it
     * @param service  the client-session service's base
     */
    public static Report drive(AdapterLauncher launcher, URI service, ClientRules rules, Options options)
            throws IOException, InterruptedException {
        List<DriveNote> notes = new ArrayList<>();
        Adapter main = launcher.start();
        Adapter verifier = null;
        Inbox inbox = null;
        SessionClient session;
        try {
            AdapterConnection client = main.connection();
            Hello hello = client.hello();
            session = SessionClient.start(service, settings(hello, options));
            LOG.info("driving {} through session {}", hello.client().name(), session.id());
            ObjectNode auth = defaultAuth(hello, session);
            String problem = configure(client, auth, options);
            if (problem == null && options.areas().contains("notifications") && hello.implementsOperation("verify_notification")) {
                verifier = launcher.start();
                problem = configure(verifier.connection(), auth, options);
                inbox = new Inbox(options.inboxHost(), options.inboxPort(), options.inboxPublicBase(), verifier.connection(),
                        options.operationTimeout());
            }
            Reply found = problem != null ? null : client.call("discover_storage",
                    JSON.createObjectNode().put("url", session.storage().toString()), options.operationTimeout());
            if (problem == null && !found.ok()) {
                problem = "discover_storage ended in " + found.error().describe();
            }
            if (problem != null) {
                for (RuleDefinition rule : inScope(rules, options)) {
                    notes.add(DriveNote.notStarted(rule.name(), "the client could not be set up: " + problem));
                }
            } else {
                runDrives(client, hello, session, auth, found.result(), inbox, rules, options, notes);
            }
        } finally {
            main.close();
            if (verifier != null) {
                verifier.close();
            }
            if (inbox != null) {
                inbox.close();
            }
        }
        return new Report(main.connection().hello().client(), session.id(), session.pageWithKey(), session.results(), notes,
                session.export("earl"), session.export("junit"));
    }

    private static void runDrives(AdapterConnection client, Hello hello, SessionClient session, ObjectNode auth,
            JsonNode storage, Inbox inbox, ClientRules rules, Options options, List<DriveNote> notes)
            throws IOException, InterruptedException {
        Map<String, String> services = new LinkedHashMap<>();
        storage.path("services").forEach(s -> s.path("types")
                .forEach(t -> services.putIfAbsent(t.asText(), s.path("serviceEndpoint").asText())));
        String root = storage.path("storageRoot").asText(session.storage().toString());
        DriveRunner runner = new DriveRunner(client, session, variables(session, services), options.operationTimeout(),
                options.pauses());
        boolean reconfigured = false;
        String broken = null;
        for (RuleDefinition rule : inScope(rules, options)) {
            if (broken != null) {
                notes.add(DriveNote.notStarted(rule.name(), broken));
                continue;
            }
            if (rule.drive().isEmpty()) {
                notes.add(DriveNote.noDrive(rule.name()));
                continue;
            }
            String cannot = runner.cannotStart(rule, hello);
            if (cannot == null && inbox == null && mentions(rule, "${drive.inbox")) {
                cannot = "the driver hosts no inbox: the notifications area is out of scope, or the client does not verify notifications";
            }
            if (cannot != null) {
                notes.add(DriveNote.notStarted(rule.name(), cannot));
                continue;
            }
            try {
                if (reconfigured) {
                    String problem = configure(client, auth, options);
                    if (problem != null) {
                        notes.add(DriveNote.notStarted(rule.name(), "configuring the client again failed: " + problem));
                        continue;
                    }
                    reconfigured = false;
                }
                ObjectNode made = JSON.createObjectNode().put("parent", root).put("slug", "drive-" + rule.name());
                Reply container = client.call("create_container", made, options.operationTimeout());
                if (!container.ok()) {
                    notes.add(DriveNote.stopped(rule.name(), 0,
                            "creating the drive's container ended in " + container.error().describe()));
                    continue;
                }
                String inboxUrl = inbox == null ? "" : inbox.base() + rule.name() + "/";
                DriveNote note = runner.run(rule, container.result().path("location").asText(), inboxUrl);
                notes.add(note);
                reconfigured = rule.drive().stream().anyMatch(s -> "configure".equals(s.op()));
                LOG.debug("{}: {}", rule.name(), note);
                session.settleDeliveries(Duration.ofSeconds(10));
            } catch (BrokenAdapterException e) {
                notes.add(DriveNote.stopped(rule.name(), 0, e.getMessage()));
                broken = e.getMessage();
            }
        }
    }

    private static List<RuleDefinition> inScope(ClientRules rules, Options options) {
        return rules.rules().stream()
                .filter(r -> options.areas().contains(r.area()))
                .filter(r -> options.rules() == null || options.rules().contains(r.name()))
                .toList();
    }

    private static boolean mentions(RuleDefinition rule, String text) {
        for (DriveStep step : rule.drive()) {
            if (step.args().toString().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode settings(Hello hello, Options options) {
        ObjectNode settings = JSON.createObjectNode();
        ObjectNode client = settings.putObject("clientUnderTest");
        client.put("name", hello.client().name());
        if (hello.client().version() != null) {
            client.put("version", hello.client().version());
        }
        if (hello.client().homepage() != null) {
            client.put("homepage", hello.client().homepage());
        }
        options.areas().forEach(settings.withArray("areas")::add);
        settings.put("driven", true);
        return settings;
    }

    /** alice: with credentials the client signs itself when it can, otherwise with a token (D-0104). */
    private static ObjectNode defaultAuth(Hello hello, SessionClient session) throws IOException, InterruptedException {
        ObjectNode auth = JSON.createObjectNode();
        if (hello.auth().contains("cid")) {
            JsonNode alice = session.credentials("alice");
            auth.put("type", "cid").put("agent", alice.path("cidUrl").asText());
            auth.set("privateJwk", alice.path("privateKeyJwk"));
            auth.put("kid", alice.path("verificationMethod").asText());
        } else if (hello.auth().contains("bearer")) {
            auth.put("type", "bearer").put("token", session.token("alice"));
        } else {
            auth.put("type", "none");
        }
        return auth;
    }

    private static String configure(AdapterConnection client, ObjectNode auth, Options options) {
        ObjectNode args = JSON.createObjectNode();
        args.set("auth", auth);
        Reply r = client.call("configure", args, options.operationTimeout());
        return r.ok() ? null : "configure ended in " + r.error().describe();
    }

    /** The variables of OBSERVATION.md section 12 that every drive of the session shares. */
    private static DriveRunner.Variables variables(SessionClient session, Map<String, String> services) {
        Map<String, JsonNode> credentials = new HashMap<>();
        return name -> {
            if (name.equals("storage")) {
                return TextNode.valueOf(session.storage().toString());
            }
            if (name.startsWith("service.")) {
                String endpoint = services.get(name.substring("service.".length()));
                return endpoint == null ? null : TextNode.valueOf(endpoint);
            }
            String[] parts = name.split("\\.");
            if (parts.length != 3 || !(parts[1].equals("alice") || parts[1].equals("bob"))) {
                return null;
            }
            String who = parts[1];
            if (parts[0].equals("identity") && parts[2].equals("cidUrl")) {
                return secrets(session, credentials, who).path("cidUrl");
            }
            if (!parts[0].equals("credential")) {
                return null;
            }
            return switch (parts[2]) {
                case "privateJwk" -> secrets(session, credentials, who).path("privateKeyJwk");
                case "kid" -> secrets(session, credentials, who).path("verificationMethod");
                case "token" -> TextNode.valueOf(session.token(who));
                case "idToken" -> TextNode.valueOf(session.idToken(who));
                case "samlAssertion" -> TextNode.valueOf(session.samlAssertion(who));
                default -> null;
            };
        };
    }

    private static JsonNode secrets(SessionClient session, Map<String, JsonNode> cache, String who)
            throws IOException, InterruptedException {
        JsonNode known = cache.get(who);
        if (known == null) {
            known = session.credentials(who);
            cache.put(who, known);
        }
        return known;
    }
}
