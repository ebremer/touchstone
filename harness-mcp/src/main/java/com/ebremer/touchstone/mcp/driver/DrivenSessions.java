package com.ebremer.touchstone.mcp.driver;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.RuleDefinition;
import com.ebremer.touchstone.driver.AdapterRegistry;
import com.ebremer.touchstone.driver.DriveNote;
import com.ebremer.touchstone.driver.DriveSession;
import com.ebremer.touchstone.driver.Driver;
import com.ebremer.touchstone.driver.SessionClient;
import com.ebremer.touchstone.mcp.config.Catalog;
import com.ebremer.touchstone.mcp.config.DriverProperties;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The clients this MCP server drives, and their sessions (CLIENT-TESTING.md section 14.2). Only the
 * stdio profile has it: the driving tools run the operator's registered adapters, on the operator's
 * machine, for the agent attached to this process, and are never hosted (section 14.3).
 *
 * <p>Adapters and services come only from the adapter registry. A session the driver starts is named
 * by its id from then on, and its key stays here: no tool answer, log line or adapter argument
 * carries it.
 */
@Component
@Profile("stdio")
public class DrivenSessions implements DisposableBean {

    /** A client this server drives, in one session. */
    public static final class Driven {
        final String clientId;
        final SessionClient session;
        volatile DriveSession driving;

        Driven(String clientId, SessionClient session, DriveSession driving) {
            this.clientId = clientId;
            this.session = session;
            this.driving = driving;
        }

        public String clientId() {
            return clientId;
        }

        public SessionClient session() {
            return session;
        }

        /** The running client, or null once it was stopped. */
        public DriveSession driving() {
            return driving;
        }
    }

    /** A whole session's drive, running in the background. */
    public static final class DriveJob {
        final String id;
        final Driven driven;
        final int total;
        final List<DriveNote> notes = new CopyOnWriteArrayList<>();
        volatile String status = "RUNNING";
        volatile String error;

        DriveJob(String id, Driven driven, int total) {
            this.id = id;
            this.driven = driven;
            this.total = total;
        }

        public String id() {
            return id;
        }

        public Driven driven() {
            return driven;
        }

        public int total() {
            return total;
        }

        public List<DriveNote> notes() {
            return List.copyOf(notes);
        }

        public String status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    private final DriverProperties props;
    private final TouchstoneProperties touchstone;
    private final Catalog catalog;
    private final Map<String, Driven> sessions = new ConcurrentHashMap<>();
    private final Map<String, DriveJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobIds = new AtomicInteger();
    private volatile ClientRules rules;

    public DrivenSessions(DriverProperties props, TouchstoneProperties touchstone, Catalog catalog) {
        this.props = props;
        this.touchstone = touchstone;
        this.catalog = catalog;
    }

    /** The registry, read again on each call so that an edit to it applies at once. */
    public AdapterRegistry registry() {
        return AdapterRegistry.load(props.registry());
    }

    public ClientRules rules() {
        ClientRules r = rules;
        if (r == null) {
            r = DefinitionLoader.loadClientRules(touchstone.definitions(), catalog.all());
            rules = r;
        }
        return r;
    }

    public RuleDefinition rule(String name) {
        return rules().find(name).orElseThrow(() -> new IllegalArgumentException("no client rule " + name));
    }

    public Collection<Driven> all() {
        return List.copyOf(sessions.values());
    }

    public Optional<Driven> find(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId));
    }

    /** The session client of a session this server started or attached to, by id. */
    public Optional<SessionClient> session(String sessionId) {
        return find(sessionId).map(Driven::session);
    }

    /**
     * The client {@code clientId} driven in {@code session}: a session this server drives, by id; a
     * session of a registered service, by its page's address with the key, which the driver then
     * attaches to; or, with none, the session this server last drove the client in, else a new one.
     */
    public Driven forClient(String clientId, String session, List<String> areas) throws IOException, InterruptedException {
        AdapterRegistry registry = registry();
        if (!registry.clients().containsKey(clientId)) {
            throw new IllegalArgumentException("no client " + clientId + " is registered; registered: "
                    + String.join(", ", registry.clients().keySet()));
        }
        if (session == null || session.isBlank()) {
            for (Driven d : sessions.values()) {
                if (d.clientId.equals(clientId) && d.driving != null && !d.driving.isBroken()) {
                    return d;
                }
            }
            DriveSession driving = DriveSession.open(registry.launcher(clientId, props.logs()), registry.service(null),
                    options(areas));
            return remember(new Driven(clientId, driving.session(), driving));
        }
        Driven known = sessions.get(session.trim());
        if (known != null) {
            if (!known.clientId.equals(clientId)) {
                throw new IllegalArgumentException("session " + known.session.id() + " drives " + known.clientId + ", not " + clientId);
            }
            if (known.driving == null) {
                known.driving = DriveSession.attach(registry.launcher(clientId, props.logs()), known.session, options(areas));
            }
            return known;
        }
        SessionClient attached = attach(registry, session);
        DriveSession driving = DriveSession.attach(registry.launcher(clientId, props.logs()), attached, options(areas));
        return remember(new Driven(clientId, attached, driving));
    }

    /** A session to start a task in or arm a fault in: one this server drives, by id, or one of a registered service. */
    public SessionClient sessionFor(String session) throws IOException, InterruptedException {
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("name a session: the id of one this server drives, or a session page's"
                    + " address with its key");
        }
        Driven known = sessions.get(session.trim());
        return known != null ? known.session : attach(registry(), session);
    }

    /** Drives every rule in scope in the background; {@code progress} hears of each rule driven. */
    public DriveJob startJob(Driven driven, List<RuleDefinition> selected, Consumer<DriveJob> progress) {
        DriveJob job = new DriveJob("drive-" + jobIds.incrementAndGet(), driven, selected.size());
        jobs.put(job.id, job);
        Thread.ofVirtual().name(job.id).start(() -> {
            try {
                for (RuleDefinition rule : selected) {
                    job.notes.add(driven.driving.drive(rule));
                    progress.accept(job);
                }
                job.status = "COMPLETE";
            } catch (IOException | RuntimeException e) {
                job.error = e.getMessage();
                job.status = "FAILED";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.status = "FAILED";
            }
        });
        return job;
    }

    public Optional<DriveJob> job(String id) {
        return Optional.ofNullable(id == null ? null : jobs.get(id));
    }

    /** Stops a driven client's adapters; its session stays readable by id. */
    public Driven stop(String sessionId) {
        Driven d = find(sessionId).orElseThrow(() -> new IllegalArgumentException("this server drives no session " + sessionId));
        DriveSession driving = d.driving;
        d.driving = null;
        if (driving != null) {
            driving.close();
        }
        return d;
    }

    public Driver.Options options(List<String> areas) {
        return new Driver.Options(areas == null || areas.isEmpty() ? Driver.AREAS : List.copyOf(areas), null,
                props.operationTimeout(), props.inboxHost(), props.inboxPort(), props.inboxPublicBase(), true);
    }

    @Override
    public void destroy() {
        for (Driven d : sessions.values()) {
            DriveSession driving = d.driving;
            d.driving = null;
            if (driving != null) {
                driving.close();
            }
        }
    }

    private Driven remember(Driven d) {
        sessions.put(d.session.id(), d);
        return d;
    }

    /**
     * A session page's address with its key, {@code {service}/sessions/{id}/page#key=…}, of a service
     * the registry names; an error never repeats the address, which holds the key.
     */
    private static SessionClient attach(AdapterRegistry registry, String address) throws IOException, InterruptedException {
        String s = address.trim();
        int hash = s.indexOf('#');
        String key = null;
        if (hash >= 0) {
            for (String part : s.substring(hash + 1).split("&")) {
                if (part.startsWith("key=")) {
                    key = part.substring(4);
                }
            }
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("not a session this server drives, nor a session page's address with #key=…");
        }
        String page = s.substring(0, hash);
        List<String> registered = new ArrayList<>();
        for (URI service : registry.services().values()) {
            registered.add(service.toString());
            String prefix = service + "/sessions/";
            if (page.startsWith(prefix) && page.endsWith("/page")) {
                String id = page.substring(prefix.length(), page.length() - "/page".length());
                if (id.matches("[A-Za-z0-9_-]{1,64}")) {
                    return SessionClient.attach(service, id, key);
                }
            }
        }
        throw new IllegalArgumentException("not a session page of a service the adapter registry names; it names "
                + (registered.isEmpty() ? "none" : String.join(", ", registered)));
    }
}
