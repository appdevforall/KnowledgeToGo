/*
 * ============================================================================
 * Name        : Tier3LinkResolver.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure resolution of a tier-3 help link state (ADFA-4616).
 * ============================================================================
 */
package org.iiab.controller.help.domain;

/**
 * Resolves a tier-3 help link's {@link Tier3LinkState} from the two facts that matter:
 * whether a usable IIAB rootfs is present, and whether the local web server is alive.
 *
 * <p>Presentation maps {@code DashboardFragment.SystemState} to {@code rootfsPresent}
 * (ONLINE/OFFLINE = true; NONE/DEBIAN_ONLY/TERMUX_ONLY/INSTALLER = false) and reads
 * {@code alive} from {@code ServerState}. Kept UI-agnostic so it is JVM-unit-testable.
 */
public final class Tier3LinkResolver {

    private Tier3LinkResolver() {}

    public static Tier3LinkState resolve(boolean rootfsPresent, boolean serverAlive) {
        if (!rootfsPresent) {
            return Tier3LinkState.NO_ROOTFS;
        }
        if (!serverAlive) {
            return Tier3LinkState.SERVER_OFF;
        }
        return Tier3LinkState.LIVE;
    }
}
