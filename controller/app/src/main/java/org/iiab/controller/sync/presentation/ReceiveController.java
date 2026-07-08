/*
 * ============================================================================
 * Name        : ReceiveController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The Receive flow of the Sync tab, carved out of SyncFragment
 *               (strangler-fig, ADFA-4506): scan -> probe -> dry-run -> confirm ->
 *               transfer, plus cancel and progress rendering. The probe/dry-run and
 *               transfer state already live in SyncStateViewModel /
 *               SyncProgressRepository (ADFA-4492); this controller is the view glue
 *               and delegates cross-cutting concerns (scanner, arch dialogs,
 *               system-protection, mode toggle) to the Fragment via ReceiveHost.
 *               No behaviour change.
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;

import org.iiab.controller.R;
import org.iiab.controller.SyncHandshakeHelper;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.transport.TransportEngine;

public final class ReceiveController {

    private final Fragment fragment;
    private final ReceiveHost host;

    // Borrowed collaborators (set in bind()).
    private SyncStateViewModel syncVm;
    private ShareConfig shareConfig;

    // Borrowed views (set in bind()).
    private Button btnScanQr, btnCancelTransfer;
    private LinearLayout containerProgress;
    private TextView txtTransferFilename, txtTransferSpeed, txtTransferEta;
    private ProgressBar progressBarTransfer;

    private long lastTransferSeq = -1L; // 3b-2: fire terminal dialog once

    public ReceiveController(Fragment fragment, ReceiveHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Borrow the ViewModel + config and wire the Receive views/listeners. Called from onCreateView(). */
    public void bind(View root, SyncStateViewModel syncVm, ShareConfig shareConfig) {
        this.syncVm = syncVm;
        this.shareConfig = shareConfig;

        btnScanQr = root.findViewById(R.id.btn_scan_qr);
        btnCancelTransfer = root.findViewById(R.id.btn_cancel_transfer);
        containerProgress = root.findViewById(R.id.container_progress);
        txtTransferFilename = root.findViewById(R.id.txt_transfer_filename);
        txtTransferSpeed = root.findViewById(R.id.txt_transfer_speed);
        txtTransferEta = root.findViewById(R.id.txt_transfer_eta);
        progressBarTransfer = root.findViewById(R.id.progress_bar_transfer);

        btnScanQr.setOnClickListener(v -> {
            if (!host.isSystemOptimizedForSync()) {
                host.showPhantomWarningDialog(this::startReceiveFlow);
                return;
            }
            startReceiveFlow();
        });

        btnCancelTransfer.setOnClickListener(v -> {
            syncVm.getTransport().stop();
            host.disableSystemProtection();
            syncVm.releaseNetwork(); // ADFA-4496
            SyncProgressRepository.get().postIdle();
            containerProgress.setVisibility(View.GONE);
            btnScanQr.setVisibility(View.VISIBLE);
        });
    }

    /** Called from the Fragment's QR-scanner ActivityResult callback. */
    public void handleScannedData(String scannedJson) {
        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(scannedJson);
        if (creds == null) {
            Toast.makeText(fragment.getContext(), fragment.getString(R.string.sync_toast_invalid_qr), Toast.LENGTH_SHORT).show();
            return;
        }

        // --- ARCHITECTURE VALIDATION ---
        int hostBits = creds.archBits;
        int guestBits = host.getArchBits();

        if (hostBits != 0 && hostBits != guestBits) {
            if (hostBits == 64 && guestBits == 32) {
                host.showArchIncompatibilityDialog(fragment.getString(R.string.sync_error_arch_hardware_32));
                return;
            } else if (hostBits == 32 && guestBits == 64) {
                boolean hardwareSupports32 = false;
                for (String abi : android.os.Build.SUPPORTED_ABIS) {
                    if (abi.contains("v7a") || (abi.contains("arm") && !abi.contains("64"))) {
                        hardwareSupports32 = true;
                        break;
                    }
                }

                if (hardwareSupports32) {
                    host.showArchIncompatibilityDialog(fragment.getString(R.string.sync_error_arch_fixable));
                } else {
                    host.showArchIncompatibilityDialog(fragment.getString(R.string.sync_error_arch_strict_64));
                }
                return;
            }
        }

        // --- EVERYTHING IS OK: PROCEED TO DOWNLOAD ---
        host.showArchCompatibilitySuccess(() -> {
            if (!creds.hasRootfs) {
                new AlertDialog.Builder(fragment.requireContext())
                        .setTitle(fragment.getString(R.string.sync_dialog_empty_host_title))
                        .setMessage(fragment.getString(R.string.sync_dialog_empty_host_msg))
                        .setPositiveButton(fragment.getString(R.string.sync_dialog_btn_try_anyway), (dialog, which) -> startProbe(creds))
                        .setNegativeButton(fragment.getString(R.string.cancel), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            } else {
                startProbe(creds);
            }
        });
    }

    /**
     * ADFA-4492 step 4: kick the pre-transfer probe + dry-run, which run in the
     * Activity-scoped ViewModel and publish their phases to SyncProgressRepository. The
     * controller only shows the connecting UI; renderTransfer() reacts to CONNECTING/
     * CALCULATING/CONFIRM/ABORTED, so the probe survives a recreation (theme toggle).
     */
    private void startProbe(SyncHandshakeHelper.SyncCredentials creds) {
        btnScanQr.setVisibility(View.GONE);
        containerProgress.setVisibility(View.VISIBLE);
        progressBarTransfer.setIndeterminate(true);
        txtTransferFilename.setText(fragment.getString(R.string.sync_msg_connecting));
        syncVm.startProbe(fragment.requireContext().getApplicationContext(), shareConfig, creds);
    }

    private void startTransfer(SyncHandshakeHelper.SyncCredentials creds, File destDir) {
        host.enableSystemProtection();
        if (!destDir.exists()) destDir.mkdirs();

        // 3b-2: progress flows through SyncProgressRepository so the UI re-binds after a
        // recreation; the listener must NOT touch fragment views, and the transport uses
        // the application context (it lives in the Activity-scoped ViewModel).
        SyncProgressRepository.get().postTransferring(0, "", "", "RootFS");

        syncVm.getTransport().startClient(fragment.requireContext().getApplicationContext(), shareConfig, creds.ip, creds.port, creds.user, creds.pass, destDir.getAbsolutePath(), new TransportEngine.SyncListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta, String currentFile) {
                SyncProgressRepository.get().postTransferring(percentage, speed, eta, currentFile);
            }

            @Override
            public void onComplete(String message) {
                SyncProgressRepository.get().postSuccess(message);
            }

            @Override
            public void onError(String error) {
                SyncProgressRepository.get().postFailed(error);
            }
        });
    }

    /** Renders the transfer state from SyncProgressRepository; re-binds after recreation (3b-2). */
    public void renderTransfer(SyncTransferState st) {
        if (st == null) return;
        switch (st.phase) {
            case CONNECTING:
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(true);
                txtTransferFilename.setText(fragment.getString(R.string.sync_msg_connecting));
                break;
            case CALCULATING:
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(true);
                txtTransferFilename.setText(fragment.getString(R.string.sync_msg_calculating));
                break;
            case CONFIRM:
                // Dry-run is done; the plan (creds/destDir) lives in the ViewModel and survives
                // recreation, so re-show the confirm dialog once per fragment instance.
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(true);
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    if (fragment.getContext() != null) {
                        new AlertDialog.Builder(fragment.requireContext())
                                .setTitle(st.title)
                                .setMessage(st.message)
                                .setCancelable(false)
                                .setPositiveButton(fragment.getString(R.string.sync_btn_start_transfer), (dialog, which) -> {
                                    SyncHandshakeHelper.SyncCredentials creds = syncVm.getPendingCreds();
                                    File destDir = syncVm.getPendingDestDir();
                                    if (creds != null && destDir != null) {
                                        startTransfer(creds, destDir);
                                    } else {
                                        SyncProgressRepository.get().postIdle();
                                        containerProgress.setVisibility(View.GONE);
                                        btnScanQr.setVisibility(View.VISIBLE);
                                    }
                                })
                                .setNegativeButton(fragment.getString(R.string.cancel), (dialog, which) -> {
                                    syncVm.cancelProbe();
                                    containerProgress.setVisibility(View.GONE);
                                    btnScanQr.setVisibility(View.VISIBLE);
                                })
                                .show();
                    }
                }
                break;
            case ABORTED:
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    if (fragment.getContext() != null)
                        new AlertDialog.Builder(fragment.requireContext())
                                .setTitle(st.title)
                                .setMessage(st.message)
                                .setPositiveButton(fragment.getString(R.string.adb_enforcer_btn_ok), null)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    syncVm.releaseNetwork(); // ADFA-4496
                    SyncProgressRepository.get().postIdle();
                }
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
                break;
            case TRANSFERRING:
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(false);
                progressBarTransfer.setProgress(st.percent);
                txtTransferSpeed.setText(st.speed);
                txtTransferEta.setText(fragment.getString(R.string.sync_transfer_eta, st.eta));
                if (!st.file.isEmpty()) {
                    String displayFile = st.file.length() > 40 ? "..." + st.file.substring(st.file.length() - 40) : st.file;
                    txtTransferFilename.setText(fragment.getString(R.string.sync_transfer_filename, displayFile));
                }
                break;
            case SUCCESS:
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    host.disableSystemProtection();
                    if (fragment.getContext() != null)
                        new AlertDialog.Builder(fragment.requireContext())
                                .setTitle(fragment.getString(R.string.sync_success_title))
                                .setMessage(st.message)
                                .setPositiveButton(fragment.getString(R.string.adb_enforcer_btn_ok), null)
                                .show();
                    syncVm.releaseNetwork(); // ADFA-4496
                    SyncProgressRepository.get().postIdle();
                }
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    host.disableSystemProtection();
                    if (fragment.getContext() != null)
                        new AlertDialog.Builder(fragment.requireContext())
                                .setTitle(fragment.getString(R.string.sync_error_title))
                                .setMessage(fragment.getString(R.string.sync_error_body, st.message))
                                .setPositiveButton(fragment.getString(R.string.adb_enforcer_btn_ok), null)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    syncVm.releaseNetwork(); // ADFA-4496
                    SyncProgressRepository.get().postIdle();
                }
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
                break;
            case IDLE:
            default:
                break;
        }
    }

    /** Forces the Share tab into receive mode so the transfer progress is visible after a
     *  recreation (the mode toggle resets otherwise). 3b-2. */
    private void ensureReceiveModeForTransfer() {
        host.selectReceiveMode();
        containerProgress.setVisibility(View.VISIBLE);
        btnScanQr.setVisibility(View.GONE);
    }

    /** Extracted from the Scan-QR click so the pre-flight can run it after "continue". */
    public void startReceiveFlow() {
        // EX6: the "safe to receive now?" rule lives in the pure TransferGuard domain.
        boolean serverRunning = host.isServerAlive();
        if (!org.iiab.controller.sync.domain.TransferGuard.canReceive(serverRunning).allowed) {
            new AlertDialog.Builder(fragment.requireContext())
                    .setTitle(fragment.getString(R.string.sync_dialog_server_running_title))
                    .setMessage(fragment.getString(R.string.sync_error_stop_server_first))
                    .setPositiveButton(fragment.getString(R.string.adb_enforcer_btn_ok), null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            return;
        }
        host.launchQrScanner();
    }
}
