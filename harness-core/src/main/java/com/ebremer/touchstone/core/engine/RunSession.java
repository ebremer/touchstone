package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;

import com.ebremer.touchstone.core.definitions.DefinitionLint;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.IdentityDefinition;
import com.ebremer.touchstone.core.definitions.StepDefinition;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.ebremer.touchstone.core.exec.ProvisioningAdapter;
import com.ebremer.touchstone.core.exec.ProvisioningAdapters;
import com.ebremer.touchstone.core.exec.ProvisioningException;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.results.HttpExchangeTrace;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run against one target (EXECUTION.md section 4.1): the run root, the variables derived
 * once per run ({@code storage}, {@code as.*}, the storage description), the credentials and
 * the fixture host. Thread-safe: tests run in parallel against one session.
 */
final class RunSession implements AutoCloseable {

    static final String LWS = "https://www.w3.org/ns/lws#";
    static final String CONTAINER_TYPE = "<" + LWS + "Container>; rel=\"type\"";
    static final String FOAF_AGENT = "http://xmlns.com/foaf/0.1/Agent";
    private static final Logger LOG = LoggerFactory.getLogger(RunSession.class);

    /** The Bearer challenge an anonymous request draws: where the authorization server is, and the realm. */
    record Challenge(String asUri, String realm) {
    }

    /** A container POST: the URI created, or why not, and the exchange either way. */
    record Created(URI uri, HttpExchangeTrace trace, String failure) {
    }

    private final Target target;
    private final Definitions definitions;
    private final String runId;
    private final Http http;
    private final Credentials credentials;
    private final ProvisioningAdapter adapter;
    private final Map<String, Lazy<?>> derived = new ConcurrentHashMap<>();
    private final Scope runScope;
    private FixtureHost fixtures;
    private volatile URI runRoot;

    private RunSession(Target target, Definitions definitions, String runId) {
        this.target = target;
        this.definitions = definitions;
        this.runId = runId;
        long seconds = Long.parseLong(target.properties().getOrDefault("timeout", "30"));
        this.http = new Http(Duration.ofSeconds(seconds));
        this.adapter = ProvisioningAdapters.forTarget(target);
        this.credentials = new Credentials(this);
        this.runScope = new Scope(this, null);
    }

    /**
     * Starts the fixture host when the target can reach one, then creates the run root: a POST
     * to the target's base URL as alice (section 4.1). If that fails there is no run, and no
     * verdict: a {@link ProvisioningException}.
     */
    static RunSession open(Target target, Definitions definitions, String runId) {
        RunSession s = new RunSession(target, definitions, runId);
        try {
            s.fixtures = FixtureHost.startFor(s);
            Created root = s.createContainer(target.baseUrl(), "touchstone-run-" + runId, s.runScope);
            if (root.uri() == null) {
                throw new ProvisioningException("cannot create the run root: " + root.failure());
            }
            s.runRoot = root.uri();
            return s;
        } catch (Unresolvable e) {
            s.close();
            throw new ProvisioningException("cannot create the run root as alice: " + e.getMessage());
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    Target target() {
        return target;
    }

    Definitions definitions() {
        return definitions;
    }

    Credentials credentials() {
        return credentials;
    }

    ProvisioningAdapter adapter() {
        return adapter;
    }

    String runId() {
        return runId;
    }

    URI runRoot() {
        if (runRoot == null) {
            throw Unresolvable.cantTell("${run.root} does not exist yet");
        }
        return runRoot;
    }

    Resp send(Req req) throws IOException {
        try {
            return http.send(req);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    // ------------------------------------------------------------------ containers

    /** POSTs a container under {@code parent} as alice, the way section 4.1 creates the run root. */
    Created createContainer(URI parent, String slug, Scope scope) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Http.header("Link", CONTAINER_TYPE));
        // Not standardised, and nothing depends on it: a hint that makes a run root
        // recognisable in the storage it was left in (D-0040).
        headers.add(Http.header("Slug", slug));
        scope.authorization("alice").forEach((k, v) -> headers.add(Http.header(k, v)));
        Req req = new Req("POST", parent, List.copyOf(headers), null, null);
        Resp resp;
        try {
            resp = send(req);
        } catch (IOException e) {
            return new Created(null, Http.trace(req, null), "POST " + parent + " as alice failed: " + e);
        }
        String location = resp.first("Location");
        if (resp.status() != 201 || location == null) {
            return new Created(null, Http.trace(req, resp), "POST " + parent + " as alice answered "
                    + resp.status() + (location == null ? " without a Location" : "") + ", not 201");
        }
        return new Created(parent.resolve(location), Http.trace(req, resp), null);
    }

    // ------------------------------------------------------------------ derived variables

    @SuppressWarnings("unchecked")
    private <T> T derive(String name, Supplier<T> supplier) {
        return ((Lazy<T>) derived.computeIfAbsent(name, k -> new Lazy<>(supplier))).get();
    }

    /** {@code ${storage}}: the lws#storage link on a GET of the test container as alice. */
    String storage(Scope scope) {
        return derive("storage", () -> {
            String container = scope.bound("test.container");
            if (container == null) {
                throw Unresolvable.cantTell("${storage} is derived from ${test.container}, which is not bound");
            }
            URI uri = URI.create(container);
            Resp resp = fetch("${storage}", get(uri, "application/lws+json", scope.authorization("alice")));
            for (LinkValues.Link link : LinkValues.parse(resp.header("Link"), uri)) {
                if (link.hasRel(LWS + "storage")) {
                    return link.target();
                }
            }
            throw Unresolvable.cantTell("${storage}: GET " + uri + " as alice answered " + resp.status()
                    + " with no Link whose rel is " + LWS + "storage");
        });
    }

    /**
     * {@code ${as.uri}} and {@code ${as.realm}}: the Bearer challenge an anonymous GET draws.
     * The probe is the test container, as section 3 says. Provisioning needs the values before
     * any test container exists, when alice's token is minted to create the run root, and then
     * probes the base URL the same way; either way they are derived once per run.
     */
    Challenge challenge(Scope scope) {
        return derive("as.challenge", () -> {
            String container = scope.bound("test.container");
            URI uri = container != null ? URI.create(container) : runRoot != null ? runRoot : target.baseUrl();
            Resp resp = fetch("${as.uri}", get(uri, null, Map.of()));
            if (resp.status() != 401) {
                throw Unresolvable.inapplicable("an anonymous GET of " + uri + " answered " + resp.status()
                        + ", not 401, so the target does not enforce authentication and ${as.*} does not exist");
            }
            for (Challenges.Challenge c : Challenges.parse(resp.header("WWW-Authenticate"))) {
                if (c.scheme().equalsIgnoreCase("Bearer") && c.param("as_uri") != null && c.param("realm") != null) {
                    return new Challenge(c.param("as_uri"), c.param("realm"));
                }
            }
            throw Unresolvable.cantTell("an anonymous GET of " + uri + " answered 401 without a Bearer challenge"
                    + " carrying as_uri and realm");
        });
    }

    /** {@code ${as.metadataUrl}}: RFC 8414 section 3.1, the well-known segment before the issuer's path. */
    String metadataUrl(Scope scope) {
        URI as = URI.create(challenge(scope).asUri());
        String path = as.getRawPath() == null ? "" : as.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return as.getScheme() + "://" + as.getRawAuthority() + "/.well-known/lws-configuration" + path;
    }

    String metadataMember(Scope scope, String member) {
        String url = metadataUrl(scope);
        JsonNode metadata = derive("as.metadata", () -> json("${as.*}",
                fetch("the authorization server metadata", get(URI.create(url), "application/json", Map.of())), url));
        JsonNode value = metadata.get(member);
        if (value == null || !value.isTextual()) {
            throw Unresolvable.cantTell("the authorization server metadata at " + url + " has no " + member);
        }
        return value.asText();
    }

    /** {@code ${service.<Type>}}: from the storage description, fetched as alice as application/lws+cid. */
    String service(Scope scope, String type) {
        String storage = storage(scope);
        JsonNode description = derive("storage.description", () -> json("the storage description",
                fetch("the storage description", get(URI.create(storage), "application/lws+cid",
                        scope.authorization("alice"))), storage));
        JsonNode services = description.path("service");
        for (JsonNode s : services.isArray() ? services : List.of(services)) {
            if (hasType(s.path("type"), type)) {
                JsonNode endpoint = s.path("serviceEndpoint");
                String value = endpoint.isTextual() ? endpoint.asText() : endpoint.path("id").asText(null);
                if (value != null) {
                    return URI.create(storage).resolve(value).toString();
                }
            }
        }
        throw Unresolvable.inapplicable("the storage description at " + storage + " advertises no " + type);
    }

    private static boolean hasType(JsonNode type, String wanted) {
        for (JsonNode t : type.isArray() ? type : List.of(type)) {
            String v = t.asText();
            if (v.equals(wanted) || v.equals(LWS + wanted)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ${fixtures.baseUrl}}: only for a target that declares it can reach the fixture host. */
    String fixturesBaseUrl() {
        if (!target.capabilities().contains("ReachableFixtures")) {
            throw Unresolvable.inapplicable("the target does not declare ReachableFixtures, so ${fixtures.baseUrl}"
                    + " is undefined");
        }
        String base = target.properties().get("fixtures.baseUrl");
        if (base == null || base.isBlank()) {
            throw Unresolvable.inapplicable("the target declares ReachableFixtures but no fixtures.baseUrl");
        }
        return base.endsWith("/") ? base : base + "/";
    }

    private Req get(URI uri, String accept, Map<String, String> auth) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (accept != null) {
            headers.add(Http.header("Accept", accept));
        }
        auth.forEach((k, v) -> headers.add(Http.header(k, v)));
        return new Req("GET", uri, List.copyOf(headers), null, null);
    }

    private Resp fetch(String what, Req req) {
        try {
            return send(req);
        } catch (IOException e) {
            throw Unresolvable.cantTell(what + ": GET " + req.uri() + " failed: " + e);
        }
    }

    private static JsonNode json(String what, Resp resp, String url) {
        if (resp.status() != 200) {
            throw Unresolvable.cantTell(what + ": GET " + url + " answered " + resp.status());
        }
        try {
            JsonNode node = Templates.JSON.readTree(resp.body());
            if (node == null || !node.isObject()) {
                throw new IOException("not a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw Unresolvable.cantTell(what + " at " + url + " is not a JSON object");
        }
    }

    // ------------------------------------------------------------------ capabilities

    /**
     * The capabilities a test needs that the target lacks: its own {@code requires}, and those
     * of every identity it acts as or mints a credential for (section 4.2).
     */
    Set<String> missingCapabilities(TestDefinition test) {
        Set<String> needed = new LinkedHashSet<>(test.requires());
        Set<String> identities = new LinkedHashSet<>();
        for (StepDefinition step : test.steps()) {
            identities.add(step.identity() != null ? step.identity() : test.identity() != null ? test.identity() : "alice");
            collectCredentials(step.request(), identities);
            collectCredentials(step.response(), identities);
        }
        for (String name : identities) {
            definitions.identity(name).ifPresent(id -> {
                needed.addAll(id.requires());
                if (id.basis() != null) {
                    definitions.identity(id.basis()).map(IdentityDefinition::requires).ifPresent(needed::addAll);
                }
            });
        }
        needed.removeAll(target.capabilities());
        return needed;
    }

    private static void collectCredentials(JsonNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            Matcher m = DefinitionLint.VARIABLE.matcher(node.asText());
            while (m.find()) {
                if (m.group(1).startsWith("credential.")) {
                    out.add(m.group(1).substring("credential.".length()));
                }
            }
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectCredentials(child, out));
        }
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Deletes a container and everything in it (section 10): {@code Depth: infinity} with
     * {@code If-Match: current}, and, when the server refuses recursive delete (a MAY),
     * bottom-up by listing. Failures are logged with what was left behind, never thrown.
     */
    void deleteContainer(URI container, Scope scope) {
        try {
            Map<String, String> auth = scope.authorization("alice");
            if (delete(container, auth, true)) {
                return;
            }
            deleteMembers(container, auth, 0);
            if (!delete(container, auth, false)) {
                LOG.warn("run {}: {} left on the target: it could not be deleted", runId, container);
            }
        } catch (RuntimeException e) {
            LOG.warn("run {}: {} left on the target: {}", runId, container, e.getMessage());
        }
    }

    /** DELETE as alice, conditional on the current ETag; true when the resource is gone. */
    boolean delete(URI uri, Map<String, String> auth, boolean recursive) {
        try {
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            auth.forEach((k, v) -> headers.add(Http.header(k, v)));
            Resp head = send(new Req("HEAD", uri, List.copyOf(headers), null, null));
            if (head.status() == 404 || head.status() == 410) {
                return true;
            }
            if (recursive) {
                headers.add(Http.header("Depth", "infinity"));
            }
            String etag = head.first("ETag");
            if (etag != null) {
                headers.add(Http.header("If-Match", etag));
            }
            int status = send(new Req("DELETE", uri, List.copyOf(headers), null, null)).status();
            return status / 100 == 2 || status == 404 || status == 410;
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteMembers(URI container, Map<String, String> auth, int depth) {
        if (depth > 16) {
            return;
        }
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Http.header("Accept", "application/lws+json"));
        auth.forEach((k, v) -> headers.add(Http.header(k, v)));
        JsonNode listing;
        try {
            Resp resp = send(new Req("GET", container, List.copyOf(headers), null, null));
            listing = resp.status() == 200 ? Templates.JSON.readTree(resp.body()) : null;
        } catch (IOException e) {
            return;
        }
        if (listing == null) {
            return;
        }
        for (JsonNode item : listing.path("items")) {
            String id = item.path("id").asText(null);
            if (id == null) {
                continue;
            }
            URI member = container.resolve(id);
            boolean isContainer = id.endsWith("/") || item.path("type").toString().contains("Container");
            if (isContainer && !delete(member, auth, true)) {
                deleteMembers(member, auth, depth + 1);
            }
            delete(member, auth, false);
        }
    }

    /** Deletes the run root, then stops the fixture host (section 10, "At run end"). */
    @Override
    public void close() {
        try {
            if (runRoot != null) {
                deleteContainer(runRoot, runScope);
            }
        } finally {
            if (fixtures != null) {
                fixtures.close();
            }
        }
    }

}
