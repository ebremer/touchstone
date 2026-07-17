package com.ebremer.touchstone.core.exec;

/**
 * Pluggable per-implementation provisioning (DESIGN.md paragraph 5.3): account/storage
 * setup is out of spec scope, so each SUT kind brings its own adapter. Adapters are
 * discovered via {@link java.util.ServiceLoader} and selected by {@link Target#adapter()}.
 */
public interface ProvisioningAdapter {

    /** Registry key referenced from targets.yaml ({@code adapter: env}). */
    String id();

    /** Prepares an isolated run on the target: run-root container, credential resolution. */
    RunContext provision(Target target, String runId);
}
