/*
 * ============================================================================
 * Name        : Announcement.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: when to register and unregister the class on the local network.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

import java.util.Objects;

/**
 * Pure steps of the class announcement. The box says which class is open (its code, or null);
 * the network service registration is asynchronous, so only one register or unregister runs at a
 * time, and the next one starts when it ends. A new code takes the old one off first. A failed
 * step is not retried at once: the next box answer tries again, and the answers come less often
 * after each failure in a row ({@link #nextPollMs}). Main thread only.
 */
public final class Announcement {

    public enum Action { NONE, REGISTER, UNREGISTER }

    private String wanted;       // the code of the open class, or null
    private String registered;   // the code on the network, or null
    private String starting;     // the code of the register that runs
    private boolean busy;
    private int failures;        // failed steps in a row

    /** The last box answer: the code of the open class, or null when no class is open. */
    public Action want(String code) {
        if (!Objects.equals(code, wanted)) failures = 0;   // another class: no wait from the old one
        wanted = code;
        return step();
    }

    /** The code that a {@link Action#REGISTER} puts on the network. */
    public String toRegister() { return starting; }

    /** The running register or unregister ended. {@code nowRegistered}: the service is on the network. */
    public Action done(boolean nowRegistered) {
        busy = false;
        failures = 0;
        registered = nowRegistered ? starting : null;
        return step();
    }

    /** The running register or unregister failed: the state stays as it was until the next answer. */
    public void failed() {
        busy = false;
        failures++;
    }

    /** The longest wait between two box answers after failures (review L3). */
    public static final long MAX_BACKOFF_MS = 30000;

    /**
     * The wait before the next box answer: {@code steady} while the class is on the network,
     * {@code first} while it is not, and after failures in a row 2, 4, 8, 16 times {@code first},
     * up to {@link #MAX_BACKOFF_MS}. A system that refuses the register is not asked every second.
     */
    public long nextPollMs(long first, long steady) {
        if (registered != null) return steady;
        if (failures == 0) return first;
        return Math.min(MAX_BACKOFF_MS, first << Math.min(failures, 5));
    }

    public boolean registered() { return registered != null; }

    private Action step() {
        if (busy || Objects.equals(wanted, registered)) return Action.NONE;
        busy = true;
        if (registered != null) return Action.UNREGISTER;   // closed, or another class: off first
        starting = wanted;
        return Action.REGISTER;
    }
}
