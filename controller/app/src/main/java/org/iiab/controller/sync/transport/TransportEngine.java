/*
 * ============================================================================
 * Name        : TransportEngine.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Port (interface) for the Share feature's file transport: start a
 *               read-only server, pull from a peer, estimate a transfer (dry-run),
 *               and stop. The IIAB implementation (RsyncManager) launches the
 *               native rsync binary; an external host (Code on the Go) provides
 *               its own implementation against its native-Termux rsync — the
 *               binary path and process environment are owned by the adapter, not
 *               by this contract (tech-debt EX1, frees the feature from proot).
 *               Share-export, S14 step 2.
 *
 *               Note: {@code android.content.Context} is the Android platform
 *               handle the adapter needs (binary location, cache dir); a non-
 *               Android host substitutes its own equivalent when it reimplements
 *               this port.
 * ============================================================================
 */
package org.iiab.controller.sync.transport;

import android.content.Context;

import org.iiab.controller.sync.domain.ShareConfig;

public interface TransportEngine {

    /** Progress + terminal callbacks for a pull transfer. */
    interface SyncListener {
        void onProgress(int percentage, String speed, String eta, String currentFile);

        void onComplete(String message);

        void onError(String error);
    }

    /** Callback for the dry-run transfer-size estimate. */
    interface DryRunListener {
        void onCalculated(long bytesToTransfer);

        void onError(String error);
    }

    /** Starts the read-only sharing server. Returns false if it could not start. */
    boolean startServer(Context context, ShareConfig config, String password, String shareDir);

    /** Pulls from a peer (host/port/user/password come from the scanned handshake). */
    void startClient(Context context, ShareConfig config, String hostIp, int port,
                     String user, String password, String destDir, SyncListener listener);

    /** Estimates the bytes a pull would transfer, without writing anything. */
    void calculateTransferPlan(Context context, ShareConfig config, String hostIp, int port,
                               String user, String password, String destDir, DryRunListener listener);

    /** Cancels any in-flight operation and stops the server/client. */
    void stop();
}
