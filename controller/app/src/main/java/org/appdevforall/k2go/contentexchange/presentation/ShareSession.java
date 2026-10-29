/*
 * ============================================================================
 * Name        : ShareSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. The one share-run state the invite/QR fragment observes and the sender
 *               foreground service mutates. Encapsulated (no public mutable fields), mirroring
 *               ExportSession: the service owns the single instance and the fragment reads it
 *               through getters. Holds the invite (session id + token + manifest path) the QR needs.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Handler;
import android.os.Looper;

final class ShareSession {

    /** Pinged on the main thread whenever the run state changes. */
    interface Listener {
        void onUpdate();
    }

    /** PREPARING: creating the session on the local conex-core. READY: the invite exists; show the QR. */
    enum Phase { PREPARING, READY, ERROR }

    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;

    private volatile boolean active = false;   // a share run exists (set until clear)
    private volatile Phase phase = Phase.PREPARING;
    private int itemCount;
    private String sessionId;
    private String token;
    private String manifestUrl;
    private String errorMessage;

    void setListener(Listener l) {
        this.listener = l;
    }

    // ---- service-side mutation -----------------------------------------------------------------

    /** Start a run: the item count is fixed now; the invite arrives once conex-core opens the session. */
    void beginPreparing(int itemCount) {
        this.itemCount = itemCount;
        this.sessionId = null;
        this.token = null;
        this.manifestUrl = null;
        this.errorMessage = null;
        this.phase = Phase.PREPARING;
        this.active = true;
        publish();
    }

    /** The local conex-core opened the session: the invite is ready to render. */
    void setReady(String sessionId, String token, String manifestUrl) {
        this.sessionId = sessionId;
        this.token = token;
        this.manifestUrl = manifestUrl;
        this.phase = Phase.READY;
        publish();
    }

    void fail(String message) {
        this.errorMessage = message;
        this.phase = Phase.ERROR;
        publish();
    }

    void clear() {
        active = false;
        phase = Phase.PREPARING;
        itemCount = 0;
        sessionId = null;
        token = null;
        manifestUrl = null;
        errorMessage = null;
        publish();
    }

    void publish() {
        main.post(() -> {
            if (listener != null) {
                listener.onUpdate();
            }
        });
    }

    // ---- UI-side reads -------------------------------------------------------------------------

    boolean hasSession() {
        return active;
    }

    boolean isPreparing() {
        return active && phase == Phase.PREPARING;
    }

    boolean isReady() {
        return active && phase == Phase.READY;
    }

    boolean hasError() {
        return active && phase == Phase.ERROR;
    }

    int itemCount() {
        return itemCount;
    }

    String sessionId() {
        return sessionId;
    }

    String token() {
        return token;
    }

    String manifestUrl() {
        return manifestUrl;
    }

    String errorMessage() {
        return errorMessage;
    }
}
