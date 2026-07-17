package com.ebremer.touchstone.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** ServiceLoader lookup for {@link ProvisioningAdapter} implementations. */
public final class ProvisioningAdapters {

    private ProvisioningAdapters() {
    }

    public static ProvisioningAdapter forTarget(Target target) {
        List<String> known = new ArrayList<>();
        for (ProvisioningAdapter adapter : ServiceLoader.load(ProvisioningAdapter.class)) {
            known.add(adapter.id());
            if (adapter.id().equals(target.adapter())) {
                return adapter;
            }
        }
        throw new ProvisioningException(
                "no provisioning adapter '" + target.adapter() + "' for target '" + target.id()
                        + "' (available: " + known + ")");
    }
}
