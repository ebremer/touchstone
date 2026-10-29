package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.ebremer.touchstone.driver.protocol.Hello;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     * Drives one client through one new session: every rule in scope, in manifest order, then the
     * session's results.
     *
     * @param launcher starts the client's adapter, as configuration registers it
     * @param service  the client-session service's base
     */
    public static Report drive(AdapterLauncher launcher, URI service, ClientRules rules, Options options)
            throws IOException, InterruptedException {
        List<DriveNote> notes = new ArrayList<>();
        DriveSession driven = DriveSession.open(launcher, service, options);
        try {
            LOG.info("driving {} through session {}", driven.hello().client().name(), driven.session().id());
            for (RuleDefinition rule : inScope(rules, options)) {
                DriveNote note = driven.drive(rule);
                LOG.debug("{}: {}", rule.name(), note);
                notes.add(note);
            }
        } finally {
            driven.close();
        }
        SessionClient session = driven.session();
        return new Report(driven.hello().client(), session.id(), session.pageWithKey(), session.results(), notes,
                session.export("earl"), session.export("junit"));
    }

    /** The rules of the areas in scope, and of the names chosen when some are, in manifest order. */
    public static List<RuleDefinition> inScope(ClientRules rules, Options options) {
        return rules.rules().stream()
                .filter(r -> options.areas().contains(r.area()))
                .filter(r -> options.rules() == null || options.rules().contains(r.name()))
                .toList();
    }
}
