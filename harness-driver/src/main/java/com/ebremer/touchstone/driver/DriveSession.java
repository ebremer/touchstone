package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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

/**
 * A client being driven through a client session (CLIENT-TESTING.md section 14): its running
 * adapter, the one that verifies deliveries, the inbox the driver hosts, and the session. It stays
 * open between calls, so that an agent can drive one rule, run one operation, start a task or arm
 * a fault, read the findings, and go on.
 *
 * <p>Opening it sets the client up as section 12's step 2 says: alice, with credentials the client
 * signs itself when it can, otherwise a token (D-0104), and the storage found through the client.
 */
public final class DriveSession implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Adapter main;
    private final Adapter verifier;
    private final Inbox inbox;
    private final SessionClient session;
    private final Driver.Options options;
    private final ObjectNode auth;
    private final String problem;
    private final Map<String, String> services = new LinkedHashMap<>();
    private final String root;
    private final DriveRunner runner;
    private boolean reconfigured;
    private String broken;

    private DriveSession(Adapter main, Adapter verifier, Inbox inbox, SessionClient session, Driver.Options options,
            ObjectNode auth, String problem, JsonNode storage) {
        this.main = main;
        this.verifier = verifier;
        this.inbox = inbox;
        this.session = session;
        this.options = options;
        this.auth = auth;
        this.problem = problem;
        if (storage != null) {
            storage.path("services").forEach(s -> s.path("types")
                    .forEach(t -> services.putIfAbsent(t.asText(), s.path("serviceEndpoint").asText())));
        }
        this.root = storage == null ? session.storage().toString()
                : storage.path("storageRoot").asText(session.storage().toString());
        this.runner = new DriveRunner(main.connection(), session, variables(), options.operationTimeout(),
                options.pauses());
    }

    /** Starts the client's adapter and a new session marked driven, and sets the client up. */
    public static DriveSession open(AdapterLauncher launcher, URI service, Driver.Options options)
            throws IOException, InterruptedException {
        Adapter main = launcher.start();
        try {
            return setUp(launcher, main, SessionClient.start(service, settings(main.connection().hello(), options)), options);
        } catch (IOException | InterruptedException | RuntimeException e) {
            main.close();
            throw e;
        }
    }

    /** Starts the client's adapter in a session that already exists, such as one its developer opened. */
    public static DriveSession attach(AdapterLauncher launcher, SessionClient session, Driver.Options options)
            throws IOException, InterruptedException {
        Adapter main = launcher.start();
        try {
            return setUp(launcher, main, session, options);
        } catch (IOException | InterruptedException | RuntimeException e) {
            main.close();
            throw e;
        }
    }

    private static DriveSession setUp(AdapterLauncher launcher, Adapter main, SessionClient session, Driver.Options options)
            throws IOException, InterruptedException {
        AdapterConnection client = main.connection();
        Hello hello = client.hello();
        ObjectNode auth = defaultAuth(hello, session);
        String problem = configure(client, auth, options);
        Adapter verifier = null;
        Inbox inbox = null;
        if (problem == null && options.areas().contains("notifications") && hello.implementsOperation("verify_notification")) {
            verifier = launcher.start();
            problem = configure(verifier.connection(), auth, options);
            inbox = new Inbox(options.inboxHost(), options.inboxPort(), options.inboxPublicBase(), verifier.connection(),
                    options.operationTimeout());
        }
        JsonNode storage = null;
        if (problem == null) {
            Reply found = client.call("discover_storage", JSON.createObjectNode().put("url", session.storage().toString()),
                    options.operationTimeout());
            if (found.ok()) {
                storage = found.result();
            } else {
                problem = "discover_storage ended in " + found.error().describe();
            }
        }
        return new DriveSession(main, verifier, inbox, session, options, auth, problem, storage);
    }

    public Hello hello() {
        return main.connection().hello();
    }

    public SessionClient session() {
        return session;
    }

    /** Why the client could not be set up, or null when it is ready to drive. */
    public String setUpProblem() {
        return problem;
    }

    /** The inbox the driver hosts, ending in {@code /inbox/}, or null when it hosts none. */
    public URI inbox() {
        return inbox == null ? null : inbox.base();
    }

    /** Whether the adapter broke the protocol, after which nothing more can be driven. */
    public boolean isBroken() {
        return broken != null;
    }

    /**
     * Runs one rule's drive (OBSERVATION.md section 12, step 3 of *Running*): configures the client
     * again if a drive reconfigured it, creates the drive's container through it, runs the drive,
     * and waits for the session's deliveries.
     */
    public DriveNote drive(RuleDefinition rule) throws IOException, InterruptedException {
        if (problem != null) {
            return DriveNote.notStarted(rule.name(), "the client could not be set up: " + problem);
        }
        if (broken != null) {
            return DriveNote.notStarted(rule.name(), broken);
        }
        if (rule.drive().isEmpty()) {
            return DriveNote.noDrive(rule.name());
        }
        if (!options.areas().contains(rule.area())) {
            return DriveNote.notStarted(rule.name(), "its area, " + rule.area() + ", is out of scope");
        }
        String cannot = runner.cannotStart(rule, hello());
        if (cannot == null && inbox == null && mentions(rule, "${drive.inbox")) {
            cannot = "the driver hosts no inbox: the notifications area is out of scope, or the client does not verify notifications";
        }
        if (cannot != null) {
            return DriveNote.notStarted(rule.name(), cannot);
        }
        AdapterConnection client = main.connection();
        try {
            if (reconfigured) {
                String again = configure(client, auth, options);
                if (again != null) {
                    return DriveNote.notStarted(rule.name(), "configuring the client again failed: " + again);
                }
                reconfigured = false;
            }
            ObjectNode made = JSON.createObjectNode().put("parent", root).put("slug", "drive-" + rule.name());
            Reply container = client.call("create_container", made, options.operationTimeout());
            if (!container.ok()) {
                return DriveNote.stopped(rule.name(), 0, "creating the drive's container ended in " + container.error().describe());
            }
            String inboxUrl = inbox == null ? "" : inbox.base() + rule.name() + "/";
            DriveNote note = runner.run(rule, container.result().path("location").asText(), inboxUrl);
            reconfigured = rule.drive().stream().anyMatch(s -> "configure".equals(s.op()));
            session.settleDeliveries(Duration.ofSeconds(10));
            return note;
        } catch (BrokenAdapterException e) {
            broken = e.getMessage();
            return DriveNote.stopped(rule.name(), 0, e.getMessage());
        }
    }

    /**
     * Runs one operation of the protocol with the client, as an agent working both sides asks; the
     * session judges the traffic it causes as any other. {@code shutdown} is the driver's own.
     */
    public Reply operation(String op, ObjectNode args) {
        if (op.equals("shutdown")) {
            throw new IllegalArgumentException("shutdown is the driver's; close the driven session instead");
        }
        if (broken != null) {
            throw new BrokenAdapterException(broken);
        }
        try {
            Reply r = main.connection().call(op, args, options.operationTimeout());
            if (op.equals("configure")) {
                reconfigured = true;
            }
            return r;
        } catch (BrokenAdapterException e) {
            broken = e.getMessage();
            throw e;
        }
    }

    @Override
    public void close() {
        main.close();
        if (verifier != null) {
            verifier.close();
        }
        if (inbox != null) {
            inbox.close();
        }
    }

    private static boolean mentions(RuleDefinition rule, String text) {
        for (DriveStep step : rule.drive()) {
            if (step.args().toString().contains(text)) {
                return true;
            }
        }
        return false;
    }

    static ObjectNode settings(Hello hello, Driver.Options options) {
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

    private static String configure(AdapterConnection client, ObjectNode auth, Driver.Options options) {
        ObjectNode args = JSON.createObjectNode();
        args.set("auth", auth);
        Reply r = client.call("configure", args, options.operationTimeout());
        return r.ok() ? null : "configure ended in " + r.error().describe();
    }

    /** The variables of OBSERVATION.md section 12 that every drive of the session shares. */
    private DriveRunner.Variables variables() {
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
                return secrets(credentials, who).path("cidUrl");
            }
            if (!parts[0].equals("credential")) {
                return null;
            }
            return switch (parts[2]) {
                case "privateJwk" -> secrets(credentials, who).path("privateKeyJwk");
                case "kid" -> secrets(credentials, who).path("verificationMethod");
                case "token" -> TextNode.valueOf(session.token(who));
                case "idToken" -> TextNode.valueOf(session.idToken(who));
                case "samlAssertion" -> TextNode.valueOf(session.samlAssertion(who));
                default -> null;
            };
        };
    }

    private JsonNode secrets(Map<String, JsonNode> cache, String who) throws IOException, InterruptedException {
        JsonNode known = cache.get(who);
        if (known == null) {
            known = session.credentials(who);
            cache.put(who, known);
        }
        return known;
    }
}
