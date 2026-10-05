package com.ebremer.touchstone.clients;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.exec.TargetRegistry;

/**
 * The servers a proxy session may front (CLIENT-TESTING.md section 10): targets registered out of
 * band, in the target registry format, never a URL a developer supplies (DESIGN.md section 7.1).
 *
 * <p>A proxy target is a real LWS server deployed behind the service, its front door: it is
 * configured with {@code {base}/p/{id}/} as the start of its public URLs, so nothing it sends is
 * rewritten and every authentication suite works as it would without the proxy. The service
 * forwards {@code {base}/p/{id}/X} to {@code {proxy.backend}X}, and the authorization server
 * metadata path {@code /.well-known/lws-configuration{base path}/p/{id}...} unchanged to the
 * backend's origin.
 *
 * <pre>
 * targets:
 *   local:
 *     baseUrl: http://localhost:18090/touchstone/clients/p/local/storage/   # the storage, as clients see it
 *     adapter: env
 *     properties:
 *       proxy.backend: http://127.0.0.1:8090/      # where {base}/p/local/ is forwarded
 *       proxy.issuer: http://localhost:18090/touchstone/clients/p/local/as   # optional: its authorization server
 * </pre>
 */
final class ProxyTargets {

    /**
     * One server behind the proxy.
     *
     * @param storage the storage's public URL, which a client starts from
     * @param prefix the public URL prefix forwarded, {@code {base}/p/{id}/}
     * @param backend where the prefix is forwarded
     * @param issuer its authorization server's issuer, which the session's OpenID Provider adds to
     *     the audience of its ID Tokens; or null
     */
    record ProxyTarget(String id, URI storage, String prefix, URI backend, String issuer) {
    }

    static final ProxyTargets NONE = new ProxyTargets(Map.of());

    private final Map<String, ProxyTarget> targets;

    private ProxyTargets(Map<String, ProxyTarget> targets) {
        this.targets = Map.copyOf(targets);
    }

    /** The proxy targets in {@code file}, for a service at {@code publicBase}; every target must be one. */
    static ProxyTargets load(Path file, URI publicBase) {
        TargetRegistry registry = TargetRegistry.load(file);
        Map<String, ProxyTarget> out = new LinkedHashMap<>();
        for (String id : registry.ids()) {
            Target t = registry.find(id).orElseThrow();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("proxy target id '" + id + "' is not a simple name");
            }
            String prefix = publicBase + "/p/" + id + "/";
            if (!t.baseUrl().toString().startsWith(prefix)) {
                throw new IllegalArgumentException("proxy target '" + id + "': its baseUrl, the storage as clients see it,"
                        + " must start with " + prefix);
            }
            String backend = t.properties().get("proxy.backend");
            URI b = backend == null ? null : URI.create(backend);
            if (b == null || !b.isAbsolute() || !(b.getScheme().equals("http") || b.getScheme().equals("https"))
                    || b.getHost() == null || !backend.endsWith("/") || b.getRawQuery() != null) {
                throw new IllegalArgumentException("proxy target '" + id + "' needs proxy.backend, an http(s) URL ending in /");
            }
            out.put(id, new ProxyTarget(id, t.baseUrl(), prefix, b, t.properties().get("proxy.issuer")));
        }
        return new ProxyTargets(out);
    }

    Optional<ProxyTarget> find(String id) {
        return Optional.ofNullable(id == null ? null : targets.get(id));
    }

    java.util.Collection<ProxyTarget> all() {
        return targets.values();
    }

    boolean isEmpty() {
        return targets.isEmpty();
    }
}
