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

    /** NO_ACCOUNT: the admin account is not present in the service (never created, or deleted). */
    public enum Sync { OK, MISMATCH, NO_ACCOUNT, UNREACHABLE }

    public final String service;
    public final Sync sync;
    /** TRUE: the service accepts its factory password. null: not known. */
    public final Boolean factoryDefault;
    /** The rule for a new password, from the box: the shortest length (0: not known) and whether it
     *  needs a letter and a digit. */
    public final int minLength;
    public final boolean letterAndDigit;

    public PasswordState(String service, Sync sync, Boolean factoryDefault) {
        this(service, sync, factoryDefault, 0, false);
    }

    public PasswordState(String service, Sync sync, Boolean factoryDefault, int minLength, boolean letterAndDigit) {
        this.service = service;
        this.sync = sync;
        this.factoryDefault = factoryDefault;
        this.minLength = minLength;
        this.letterAndDigit = letterAndDigit;
    }

    public boolean usesFactoryPassword() {
        return sync == Sync.OK && Boolean.TRUE.equals(factoryDefault);
    }

    /** The service has an admin account that K2Go can manage (its password can be changed or repaired). */
    public boolean hasAccount() {
        return sync == Sync.OK || sync == Sync.MISMATCH;
    }
}
