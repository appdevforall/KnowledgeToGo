/*
 * ============================================================================
 * Name        : SystemState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5192. The derived state of the on-device system, evaluated from
 *               the rootfs/Debian/server facts (see SystemStateEvaluator) and published
 *               on ServerState so any surface can observe it live. Extracted from the
 *               legacy DashboardFragment (where it began as a nested enum) so it survives
 *               the retirement of the legacy tabbed UI; the redesign reads it through
 *               ServerState / ServerStateRepository.
 * ============================================================================
 */
package org.appdevforall.k2go;

public enum SystemState {
    ONLINE, OFFLINE, DEBIAN_ONLY, INSTALLER, TERMUX_ONLY, NONE
}
