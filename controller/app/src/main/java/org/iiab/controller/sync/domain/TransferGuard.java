/*
 * ============================================================================
 * Name        : TransferGuard.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: is it safe to start a Share transfer right now?
 *               The portable analog of the environment lock (don't move data
 *               while the environment that owns it is live). For IIAB the only
 *               blocker is the local server running (you must stop it before
 *               receiving, since a transfer rewrites the rootfs it serves). An
 *               external host (Code on the Go) extends this with its own rules
 *               (don't transfer a project that is building/running or has
 *               unsaved edits; exclude derived dirs). Pure, no android.*; S14
 *               step 4 / tech-debt EX6.
 * ============================================================================
 */
package org.iiab.controller.sync.domain;

public final class TransferGuard {

    private TransferGuard() {
    }

    /** Why a transfer is not allowed (maps to a user-facing message in the UI). */
    public enum Blocker { NONE, SERVER_RUNNING }

    public static final class Decision {
        public final boolean allowed;
        public final Blocker blocker;

        private Decision(boolean allowed, Blocker blocker) {
            this.allowed = allowed;
            this.blocker = blocker;
        }
    }

    /** Can this device start receiving (pulling) now? Not while it is serving. */
    public static Decision canReceive(boolean serverRunning) {
        return serverRunning
                ? new Decision(false, Blocker.SERVER_RUNNING)
                : new Decision(true, Blocker.NONE);
    }
}
