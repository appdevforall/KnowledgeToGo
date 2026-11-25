/*
 * ============================================================================
 * Name        : Announcement.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: when to register and unregister the class on the local network.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/**
 * Pure steps of the class announcement. The box says if the class is open (wanted); the network
 * service registration is asynchronous, so only one register or unregister runs at a time, and the
 * next one starts when it ends. A failed step is not retried at once: the next box answer tries
 * again. Main thread only.
 */
public final class Announcement {

    public enum Action { NONE, REGISTER, UNREGISTER }

    private boolean wanted;
    private boolean registered;
    private boolean busy;

    /** The last box answer: is the class open? */
    public Action want(boolean open) {
        wanted = open;
        return step();
    }

    /** The running register or unregister ended. {@code nowRegistered}: the service is on the network. */
    public Action done(boolean nowRegistered) {
        busy = false;
        registered = nowRegistered;
        return step();
    }

    /** The running register or unregister failed: the state stays as it was until the next answer. */
    public void failed() { busy = false; }

    public boolean registered() { return registered; }

    private Action step() {
        if (busy || wanted == registered) return Action.NONE;
        busy = true;
        return wanted ? Action.REGISTER : Action.UNREGISTER;
    }
}
