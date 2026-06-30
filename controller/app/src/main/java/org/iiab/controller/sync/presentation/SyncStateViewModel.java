/*
 * ============================================================================
 * Name        : SyncStateViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Activity-scoped holder for the Share feature's TransportEngine,
 *               so an in-flight rsync transfer is NOT tied to a single
 *               SyncFragment instance and survives a configuration-change
 *               recreation (theme toggle / rotation). The transport holds a
 *               Process/socket (no Context/View), so it is leak-safe to keep
 *               here. ADFA-4492 (3b-2).
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.ViewModel;

import org.iiab.controller.R;
import org.iiab.controller.RsyncManager;
import org.iiab.controller.SyncHandshakeHelper;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.transport.TransportEngine;
import org.iiab.controller.util.AppExecutors;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SyncStateViewModel extends ViewModel {

    private static final String TAG = "IIAB-SyncStateVM";

    private TransportEngine transport;

    // ADFA-4492 step 4: the pre-transfer probe + dry-run run here (Activity-scoped), not in the
    // fragment, so a theme toggle during the sondeo no longer drops it. The fragment kicks it via
    // startProbe() and reacts to the phases published on SyncProgressRepository; the chosen
    // credentials/destination are kept here so the re-bound fragment can start the transfer.
    private SyncHandshakeHelper.SyncCredentials pendingCreds;
    private File pendingDestDir;

    /** The single transport instance for this Activity; created lazily, reused across recreations. */
    public TransportEngine getTransport() {
        if (transport == null) transport = new RsyncManager();
        return transport;
    }

    public SyncHandshakeHelper.SyncCredentials getPendingCreds() { return pendingCreds; }

    public File getPendingDestDir() { return pendingDestDir; }

    /**
     * Reachability probe + rsync dry-run, off the fragment. Publishes CONNECTING -> CALCULATING ->
     * CONFIRM (ready, with size) or ABORTED (unreachable / not enough space / dry-run error). The
     * dry-run process lives in this Activity-scoped transport, so it survives a recreation.
     */
    public void startProbe(Context appCtx, ShareConfig shareConfig, SyncHandshakeHelper.SyncCredentials creds) {
        this.pendingCreds = creds;
        this.pendingDestDir = null;
        final SyncProgressRepository repo = SyncProgressRepository.get();
        repo.postConnecting();

        AppExecutors.get().io().execute(() -> {
            boolean reachable = false;
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(creds.ip, creds.port), 2000);
                socket.close();
                reachable = true;
            } catch (Exception e) {
                Log.w(TAG, "Reachability probe to " + creds.ip + ":" + creds.port + " failed", e);
            }

            if (!reachable) {
                repo.postAborted(appCtx.getString(R.string.sync_dialog_conn_failed_title),
                        appCtx.getString(R.string.sync_dialog_conn_failed_msg, creds.ip));
                return;
            }

            File destDir = new File(appCtx.getFilesDir(), "rootfs/installed-rootfs/iiab");
            if (!destDir.exists()) destDir.mkdirs();
            pendingDestDir = destDir;
            repo.postCalculating();

            getTransport().calculateTransferPlan(appCtx, shareConfig, creds.ip, creds.port,
                    creds.user, creds.pass, destDir.getAbsolutePath(),
                    new TransportEngine.DryRunListener() {
                        @Override
                        public void onCalculated(long bytesToTransfer) {
                            double gigabytes = bytesToTransfer / (1024.0 * 1024.0 * 1024.0);
                            double freeSpaceGb = android.os.Environment.getDataDirectory().getFreeSpace()
                                    / (1024.0 * 1024.0 * 1024.0);
                            if (gigabytes > (freeSpaceGb - 5.0)) {
                                repo.postAborted(appCtx.getString(R.string.sync_error_storage_title),
                                        appCtx.getString(R.string.sync_error_storage_msg, gigabytes, freeSpaceGb));
                                return;
                            }
                            String[] listing = destDir.list();
                            String title = (listing != null && listing.length > 0)
                                    ? appCtx.getString(R.string.sync_title_update)
                                    : appCtx.getString(R.string.sync_title_install);
                            repo.postConfirm(title, appCtx.getString(R.string.sync_msg_confirm_transfer, gigabytes));
                        }

                        @Override
                        public void onError(String error) {
                            repo.postAborted(appCtx.getString(R.string.sync_error_calc_title),
                                    appCtx.getString(R.string.sync_error_calc_msg, error));
                        }
                    });
        });
    }

    /** User declined the transfer (or cancelled mid-probe): stop any dry-run and reset. */
    public void cancelProbe() {
        if (transport != null) transport.stop();
        SyncProgressRepository.get().postIdle();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (transport != null) {
            transport.stop();
            transport = null;
        }
    }
}
