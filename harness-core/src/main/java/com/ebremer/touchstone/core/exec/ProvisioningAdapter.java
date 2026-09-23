package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.util.List;

/**
 * Pluggable per-implementation provisioning (DESIGN.md section 5.3): what a server cannot be
 * asked through the protocol is asked of its adapter. Adapters are discovered with
 * {@link java.util.ServiceLoader} and selected by {@link Target#adapter()}.
 *
 * <p>The engine creates the run root and every test container itself, over the protocol
 * (definitions/EXECUTION.md section 4.1). What remains out of band is access the storage's own
 * access grant service cannot set up because the storage advertises none (section 4.3).
 */
public interface ProvisioningAdapter {

    /** Registry key referenced from targets.yaml ({@code adapter: env}). */
    String id();

    /**
     * Grants {@code actions} (read, modify, create, delete) on {@code resource} to
     * {@code assignee}, an agent IRI or {@code http://xmlns.com/foaf/0.1/Agent} for the public.
     *
     * @return false when this adapter cannot grant access; the test is then inapplicable
     */
    default boolean grant(Target target, URI resource, List<String> actions, String assignee) {
        return false;
    }
}
