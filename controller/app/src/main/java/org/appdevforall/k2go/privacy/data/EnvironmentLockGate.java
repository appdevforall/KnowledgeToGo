/*
 * ============================================================================
 * Name        : EnvironmentLockGate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. EnvironmentGate backed by EnvironmentLock, the one "what holds the box" source.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.data;

import android.content.Context;

import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.privacy.domain.EnvironmentGate;

public final class EnvironmentLockGate implements EnvironmentGate {

    private final Context appContext;

    public EnvironmentLockGate(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public boolean isBusy() {
        // The lock's own classification: a STOPPED-class holder runs the box down to change it.
        return EnvironmentLock.currentHolder(appContext).executionClass
                == org.appdevforall.k2go.system.domain.Operation.ExecutionClass.STOPPED;
    }
}
