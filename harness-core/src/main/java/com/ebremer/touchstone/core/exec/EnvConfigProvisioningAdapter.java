package com.ebremer.touchstone.core.exec;

/**
 * The default adapter: everything comes from the target's configuration and environment
 * (tokens, agent IRIs; see definitions/EXECUTION.md section 5), and nothing is provisioned
 * out of band. A test that needs access granted on a storage without an access grant service
 * is therefore inapplicable against it.
 */
public final class EnvConfigProvisioningAdapter implements ProvisioningAdapter {

    @Override
    public String id() {
        return "env";
    }
}
