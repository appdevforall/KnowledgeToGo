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
 * step is not retried at once: the next box answer tries again. Main thread only.
 */
public final class Announcement {

    public enum Action { NONE, REGISTER, UNREGISTER }

    private String wanted;       // the code of the open class, or null
    private String registered;   // the code on the network, or null
    private String starting;     // the code of the register that runs
    private boolean busy;

    /** The last box answer: the code of the open class, or null when no class is open. */
    public Action want(String code) {
        wanted = code;
        return step();
    }

    /** The code that a {@link Action#REGISTER} puts on the network. */
    public String toRegister() { return starting; }

    /** The running register or unregister ended. {@code nowRegistered}: the service is on the network. */
    public Action done(boolean nowRegistered) {
        busy = false;
        registered = nowRegistered ? starting : null;
        return step();
    }

    /** The running register or unregister failed: the state stays as it was until the next answer. */
    public void failed() { busy = false; }

    public boolean registered() { return registered != null; }

    private Action step() {
        if (busy || Objects.equals(wanted, registered)) return Action.NONE;
        busy = true;
        if (registered != null) return Action.UNREGISTER;   // closed, or another class: off first
        starting = wanted;
        return Action.REGISTER;
    }
}
