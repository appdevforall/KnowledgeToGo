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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.appdevforall.k2go.contentexchange.domain.ShareProgress;
import org.appdevforall.k2go.hotspot.HotspotMode;
import org.appdevforall.k2go.hotspot.domain.HotspotHolders;

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
    private long totalBytes;
    private String sessionId;
    private String token;
    private String manifestUrl;
    private String errorMessage;
    /** The sender's Hotspot / Wi-Fi choice for this run (K2GO-577); fresh per run, Hotspot first. */
    private HotspotMode hotspotMode;
    /** The receiver's last reported progress, or how the session ended (K2GO-577). Null until
     *  the first poll, and when the box cannot report it. */
    private volatile ShareProgress progress;

    void setListener(Listener l) {
        this.listener = l;
    }

    // ---- service-side mutation -----------------------------------------------------------------

    /** Start a run: the item count and total size are fixed now; the invite arrives once conex-core
     *  opens the session. */
    void beginPreparing(int itemCount, long totalBytes) {
        this.itemCount = itemCount;
        this.totalBytes = totalBytes;
        this.sessionId = null;
        this.token = null;
        this.manifestUrl = null;
        this.errorMessage = null;
        this.hotspotMode = null;
        this.progress = null;
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
        totalBytes = 0;
        sessionId = null;
        token = null;
        manifestUrl = null;
        errorMessage = null;
        hotspotMode = null;
        progress = null;
        publish();
    }

    /** The service's poll result: the receiver's progress, or how the session ended. */
    void setProgress(ShareProgress p) {
        this.progress = p;
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

    long totalBytes() {
        return totalBytes;
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

    ShareProgress progress() {
        return progress;
    }

    /** The one wording for how a share ended, for the screen and the final notification. */
    static int endedMessage(ShareProgress p) {
        if (p != null && p.state == ShareProgress.State.COMPLETED) {
            return org.appdevforall.k2go.R.string.k2go_cx_share_complete;
        }
        if (p != null && p.state == ShareProgress.State.CANCELLED) {
            return org.appdevforall.k2go.R.string.k2go_cx_share_cancelled;
        }
        return org.appdevforall.k2go.R.string.k2go_cx_share_ended;
    }

    /** The share is over (the receiver finished or stopped, or the session expired): the screen
     *  shows the result until the user leaves, and nothing should hold the hotspot for it. */
    boolean isEnded() {
        ShareProgress p = progress;
        return active && p != null && p.isEnded();
    }

    /** This run's Hotspot / Wi-Fi rule, made on first use. The service releases the hold on exit. */
    HotspotMode hotspotMode(Context context) {
        if (hotspotMode == null) {
            hotspotMode = HotspotMode.of(context, HotspotHolders.Holder.CONTENT_SHARE, HotspotMode.Mode.HOTSPOT);
        }
        return hotspotMode;
    }
}
