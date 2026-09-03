/*
 * ============================================================================
 * Name        : ProcessSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5343 (Phase 5a). One identity for this process launch.
 * ============================================================================
 */
package org.appdevforall.k2go.env;

import java.util.UUID;

/**
 * The identity of THIS process launch — one value, generated once when the class first loads and
 * stable for the life of the process, re-generated only in a fresh process (a relaunch after a kill
 * or reboot).
 *
 * <p>It is the single source for "which launch am I", so a durable on-disk marker can record who wrote
 * it and a later launch can tell "I wrote this" from "a now-dead launch wrote this". Two independent
 * markers need the same answer — {@link EnvironmentLock}'s coordination lock and {@link
 * org.appdevforall.k2go.InstallGuard}'s install marker — and giving each its own UUID would be two
 * values answering one question, the duplicate truth the design forbids. Both read {@link #ID}.
 */
public final class ProcessSession {

    /** Unique per process launch; matches only a marker written by this same launch. */
    public static final String ID = UUID.randomUUID().toString();

    private ProcessSession() {
    }
}
