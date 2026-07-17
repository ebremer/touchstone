package com.ebremer.touchstone.mcp.config;

import java.util.Optional;
import java.util.Set;

import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.exec.TargetRegistry;

/**
 * Access to the pre-registered target registry. Reloads the file on each lookup so a
 * target added out-of-band is visible without a restart. The security boundary is that
 * callers pass a target <em>id</em>; the URL only ever comes from this file.
 */
public final class Targets {

    private final TouchstoneProperties props;

    public Targets(TouchstoneProperties props) {
        this.props = props;
    }

    public Optional<Target> find(String targetId) {
        return registry().find(targetId);
    }

    public Set<String> ids() {
        return registry().ids();
    }

    private TargetRegistry registry() {
        return TargetRegistry.load(props.targets());
    }
}
