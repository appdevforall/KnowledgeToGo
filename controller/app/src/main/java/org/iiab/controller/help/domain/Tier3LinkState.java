/*
 * ============================================================================
 * Name        : Tier3LinkState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : UI-agnostic state of a tier-3 help link (ADFA-4616).
 * ============================================================================
 */
package org.iiab.controller.help.domain;

/** State of a tier-3 help link, derived from rootfs presence + server liveness. */
public enum Tier3LinkState {
    /** No usable rootfs (SystemState NONE / DEBIAN_ONLY / TERMUX_ONLY / INSTALLER): disabled; guide to Deploy. */
    NO_ROOTFS,
    /** Rootfs present but server down (SystemState OFFLINE, or ONLINE yet not alive): off; guide to Usage -> Launch. */
    SERVER_OFF,
    /** Server up (SystemState ONLINE and alive): link is live. */
    LIVE
}
