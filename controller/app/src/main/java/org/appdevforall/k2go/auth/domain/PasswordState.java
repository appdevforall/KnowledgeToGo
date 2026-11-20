/*
 * ============================================================================
 * Name        : PasswordState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. What the box knows about one service's admin password, from a live
 *               sign-in (dash-node GET /credentials/:service/status). Never stored in the app.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.domain;

public final class PasswordState {

    public enum Sync { OK, MISMATCH, UNREACHABLE }

    public final String service;
    public final Sync sync;
    /** TRUE: the service accepts its factory password. null: not known. */
    public final Boolean factoryDefault;

    public PasswordState(String service, Sync sync, Boolean factoryDefault) {
        this.service = service;
        this.sync = sync;
        this.factoryDefault = factoryDefault;
    }

    public boolean usesFactoryPassword() {
        return sync == Sync.OK && Boolean.TRUE.equals(factoryDefault);
    }
}
