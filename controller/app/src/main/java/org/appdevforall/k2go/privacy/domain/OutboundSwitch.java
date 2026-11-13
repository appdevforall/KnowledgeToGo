/*
 * ============================================================================
 * Name        : OutboundSwitch.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. One box service that can contact an outside server on its own.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

/** Immutable. {@code service} is the box service id ({@link #KOLIBRI}, {@link #FORGEJO}). */
public final class OutboundSwitch {

    public static final String KOLIBRI = "kolibri";
    public static final String FORGEJO = "forgejo";

    private final String service;
    private final boolean installed;
    private final boolean outbound;
    private final boolean pendingRestart;

    public OutboundSwitch(String service, boolean installed, boolean outbound) {
        this(service, installed, outbound, false);
    }

    public OutboundSwitch(String service, boolean installed, boolean outbound, boolean pendingRestart) {
        this.service = service;
        this.installed = installed;
        this.outbound = outbound;
        this.pendingRestart = pendingRestart;
    }

    public String service() { return service; }

    /** The service is on the box. A service that is not installed cannot be switched. */
    public boolean installed() { return installed; }

    /** The service may contact its outside server. */
    public boolean outbound() { return outbound; }

    /** The config changed after the running service started: a restart applies it (box-derived). */
    public boolean pendingRestart() { return pendingRestart; }
}
