/*
 * ============================================================================
 * Name        : SyncFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Fragment to handle P2P/P2M syncing and App sharing
 * ============================================================================
 */

package org.iiab.controller;

import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import androidx.lifecycle.ViewModelProvider;
import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.util.List;


public class SyncFragment extends Fragment implements org.iiab.controller.sync.presentation.ArchCheckHost,
        org.iiab.controller.sync.presentation.ShareHost {

    private static final String TAG = "IIAB-SyncFragment";
    // S16: name the ADB-optimization prefs/keys (shared with the ADB-share tab).
    private static final String ADB_PREFS = "iiab_adb_prefs";
    private static final String PREF_FOCUS_ADB = "focus_adb";

    // ADFA-4506: arch labels + compatibility dialogs carved into a controller.
    private final org.iiab.controller.sync.presentation.ArchCheckController archCheckController =
            new org.iiab.controller.sync.presentation.ArchCheckController(this, this);

    // ADFA-4506: the Share area (rsync daemon + APK server) carved into a controller.
    private final org.iiab.controller.sync.presentation.ShareController shareController =
            new org.iiab.controller.sync.presentation.ShareController(this, this);

    private RadioGroup rgSyncMode;
    private LinearLayout containerShare, containerReceive, containerProgress;

    // Receive UI
    private Button btnScanQr, btnCancelTransfer;
    private TextView txtTransferFilename, txtTransferSpeed, txtTransferEta;
    private ProgressBar progressBarTransfer;


    // Managers
    private org.iiab.controller.sync.transport.TransportEngine transport;
    private org.iiab.controller.sync.presentation.SyncStateViewModel syncVm; // 3b-2: survives recreation
    private long lastTransferSeq = -1L; // 3b-2: fire terminal dialog once

    // Share config (rsync port/user/module/apk-port) — used by the Receive probe;
    // the Share area itself is owned by ShareController.
    private final org.iiab.controller.sync.domain.ShareConfig shareConfig = org.iiab.controller.sync.domain.ShareConfig.defaults();


    // Scanner Launcher
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null) {
            handleScannedData(result.getContents());
        } else {
            Toast.makeText(getContext(), getString(R.string.cancel), Toast.LENGTH_SHORT).show();
        }
    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_sync, container, false);
        syncVm = new ViewModelProvider(requireActivity()).get(org.iiab.controller.sync.presentation.SyncStateViewModel.class);
        transport = syncVm.getTransport();

        shareController.bind(view, transport, shareConfig);

        rgSyncMode = view.findViewById(R.id.rg_sync_mode);
        containerShare = view.findViewById(R.id.container_share);
        containerReceive = view.findViewById(R.id.container_receive);
        containerProgress = view.findViewById(R.id.container_progress);

        btnScanQr = view.findViewById(R.id.btn_scan_qr);
        btnCancelTransfer = view.findViewById(R.id.btn_cancel_transfer);
        txtTransferFilename = view.findViewById(R.id.txt_transfer_filename);
        txtTransferSpeed = view.findViewById(R.id.txt_transfer_speed);
        txtTransferEta = view.findViewById(R.id.txt_transfer_eta);
        progressBarTransfer = view.findViewById(R.id.progress_bar_transfer);

        TextView txtHostArchLabel = view.findViewById(R.id.txt_host_arch_label);
        TextView txtGuestArchLabel = view.findViewById(R.id.txt_guest_arch_label);

        // ADFA-4506: arch labels + compatibility dialogs are owned by ArchCheckController.
        archCheckController.bind(txtHostArchLabel, txtGuestArchLabel);
        archCheckController.applyStaticLabels();
        archCheckController.updateArchLabelsVisibility();
        setupToggleLogic();
        setupReceiveLogic();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 3b-2: re-bind the rsync transfer progress after any recreation (theme toggle).
        org.iiab.controller.sync.presentation.SyncProgressRepository.get().state().observe(getViewLifecycleOwner(), this::renderTransfer);
    }

    private void setupToggleLogic() {
        rgSyncMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_mode_share) {
                containerShare.setVisibility(View.VISIBLE);
                containerReceive.setVisibility(View.GONE);
            } else if (checkedId == R.id.rb_mode_receive) {
                containerShare.setVisibility(View.GONE);
                containerReceive.setVisibility(View.VISIBLE);
            }
            archCheckController.updateArchLabelsVisibility();
        });
    }



    // --- RSYNC DAEMON METHODS ---


    /** ADFA-4496: show the IP (and which interface) the QR advertises, so a stale QR from a
     *  previous network is obvious instead of looking like a transfer bug. */



    // --- APK SERVER METHODS ---




    // --- CLIENT (RECEIVER) METHODS ---

    private void setupReceiveLogic() {
        btnScanQr.setOnClickListener(v -> {
            if (!isSystemOptimizedForSync()) {
                showPhantomWarningDialog(this::startReceiveFlow);
                return;
            }
            startReceiveFlow();
        });

        btnCancelTransfer.setOnClickListener(v -> {
            transport.stop();
            disableSystemProtection();
            syncVm.releaseNetwork(); // ADFA-4496
            org.iiab.controller.sync.presentation.SyncProgressRepository.get().postIdle();
            containerProgress.setVisibility(View.GONE);
            btnScanQr.setVisibility(View.VISIBLE);
        });
    }

    private void handleScannedData(String scannedJson) {
        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(scannedJson);
        if (creds == null) {
            Toast.makeText(getContext(), getString(R.string.sync_toast_invalid_qr), Toast.LENGTH_SHORT).show();
            return;
        }

        // --- ARCHITECTURE VALIDATION ---
        int hostBits = creds.archBits;
        int guestBits = archCheckController.getArchBits();

        if (hostBits != 0 && hostBits != guestBits) {
            if (hostBits == 64 && guestBits == 32) {
                archCheckController.showArchIncompatibilityDialog(getString(R.string.sync_error_arch_hardware_32));
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
                    archCheckController.showArchIncompatibilityDialog(getString(R.string.sync_error_arch_fixable));
                } else {
                    archCheckController.showArchIncompatibilityDialog(getString(R.string.sync_error_arch_strict_64));
                }
                return;
            }
        }

        // --- EVERYTHING IS OK: PROCEED TO DOWNLOAD ---
        archCheckController.showArchCompatibilitySuccess(() -> {
            if (!creds.hasRootfs) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_dialog_empty_host_title))
                        .setMessage(getString(R.string.sync_dialog_empty_host_msg))
                        .setPositiveButton(getString(R.string.sync_dialog_btn_try_anyway), (dialog, which) -> startProbe(creds))
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            } else {
                startProbe(creds);
            }
        });
    }

    /**
     * ADFA-4492 step 4: kick the pre-transfer probe + dry-run, which now run in the
     * Activity-scoped ViewModel and publish their phases to SyncProgressRepository. The fragment
     * only shows the connecting UI; renderTransfer() reacts to CONNECTING/CALCULATING/CONFIRM/
     * ABORTED, so the sondeo survives a recreation (theme toggle) instead of being dropped.
     */
    private void startProbe(SyncHandshakeHelper.SyncCredentials creds) {
        btnScanQr.setVisibility(View.GONE);
        containerProgress.setVisibility(View.VISIBLE);
        progressBarTransfer.setIndeterminate(true);
        txtTransferFilename.setText(getString(R.string.sync_msg_connecting));
        syncVm.startProbe(requireContext().getApplicationContext(), shareConfig, creds);
    }

    private void startTransfer(SyncHandshakeHelper.SyncCredentials creds, File destDir) {
        enableSystemProtection();
        if (!destDir.exists()) destDir.mkdirs();

        // 3b-2: progress flows through SyncProgressRepository so the UI re-binds after a
        // recreation; the listener must NOT touch fragment views, and the transport uses
        // the application context (it lives in the Activity-scoped ViewModel).
        org.iiab.controller.sync.presentation.SyncProgressRepository.get().postTransferring(0, "", "", "RootFS");

        transport.startClient(requireContext().getApplicationContext(), shareConfig, creds.ip, creds.port, creds.user, creds.pass, destDir.getAbsolutePath(), new org.iiab.controller.sync.transport.TransportEngine.SyncListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta, String currentFile) {
                org.iiab.controller.sync.presentation.SyncProgressRepository.get().postTransferring(percentage, speed, eta, currentFile);
            }

            @Override
            public void onComplete(String message) {
                org.iiab.controller.sync.presentation.SyncProgressRepository.get().postSuccess(message);
            }

            @Override
            public void onError(String error) {
                org.iiab.controller.sync.presentation.SyncProgressRepository.get().postFailed(error);
            }
        });
    }

    /** Renders the transfer state from SyncProgressRepository; re-binds after recreation (3b-2). */
    private void renderTransfer(org.iiab.controller.sync.presentation.SyncTransferState st) {
        if (st == null) return;
        switch (st.phase) {
            case CONNECTING:
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(true);
                txtTransferFilename.setText(getString(R.string.sync_msg_connecting));
                break;
            case CALCULATING:
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(true);
                txtTransferFilename.setText(getString(R.string.sync_msg_calculating));
                break;
            case CONFIRM:
                // Dry-run is done; the plan (creds/destDir) lives in the ViewModel and survives
                // recreation, so re-show the confirm dialog once per fragment instance.
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(true);
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    if (getContext() != null) {
                        new AlertDialog.Builder(requireContext())
                                .setTitle(st.title)
                                .setMessage(st.message)
                                .setCancelable(false)
                                .setPositiveButton(getString(R.string.sync_btn_start_transfer), (dialog, which) -> {
                                    SyncHandshakeHelper.SyncCredentials creds = syncVm.getPendingCreds();
                                    File destDir = syncVm.getPendingDestDir();
                                    if (creds != null && destDir != null) {
                                        startTransfer(creds, destDir);
                                    } else {
                                        org.iiab.controller.sync.presentation.SyncProgressRepository.get().postIdle();
                                        containerProgress.setVisibility(View.GONE);
                                        btnScanQr.setVisibility(View.VISIBLE);
                                    }
                                })
                                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
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
                    if (getContext() != null)
                        new AlertDialog.Builder(requireContext())
                                .setTitle(st.title)
                                .setMessage(st.message)
                                .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    syncVm.releaseNetwork(); // ADFA-4496
                    org.iiab.controller.sync.presentation.SyncProgressRepository.get().postIdle();
                }
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
                break;
            case TRANSFERRING:
                ensureReceiveModeForTransfer();
                progressBarTransfer.setIndeterminate(false);
                progressBarTransfer.setProgress(st.percent);
                txtTransferSpeed.setText(st.speed);
                txtTransferEta.setText("ETA: " + st.eta);
                if (!st.file.isEmpty()) {
                    String displayFile = st.file.length() > 40 ? "..." + st.file.substring(st.file.length() - 40) : st.file;
                    txtTransferFilename.setText(getString(R.string.sync_transfer_filename, displayFile));
                }
                break;
            case SUCCESS:
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    disableSystemProtection();
                    if (getContext() != null)
                        new AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.sync_success_title))
                                .setMessage(st.message)
                                .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                                .show();
                    syncVm.releaseNetwork(); // ADFA-4496
                    org.iiab.controller.sync.presentation.SyncProgressRepository.get().postIdle();
                }
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                if (st.seq > lastTransferSeq) {
                    lastTransferSeq = st.seq;
                    disableSystemProtection();
                    if (getContext() != null)
                        new AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.sync_error_title))
                                .setMessage(getString(R.string.sync_error_body, st.message))
                                .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    syncVm.releaseNetwork(); // ADFA-4496
                    org.iiab.controller.sync.presentation.SyncProgressRepository.get().postIdle();
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
        if (rgSyncMode.getCheckedRadioButtonId() != R.id.rb_mode_receive) {
            rgSyncMode.check(R.id.rb_mode_receive);
        }
        containerProgress.setVisibility(View.VISIBLE);
        btnScanQr.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 3b-2: keep an in-flight transfer alive across a configuration change (theme toggle);
        // the transport lives in the Activity-scoped ViewModel and the UI re-binds on recreate.
        if (getActivity() != null && getActivity().isChangingConfigurations()
                && org.iiab.controller.sync.presentation.SyncProgressRepository.get().isActive()) {
            return;
        }
        if (transport != null) transport.stop();
        shareController.stopApkServerQuietly();
        syncVm.releaseNetwork(); // ADFA-4496: drop the network binding when the receive is torn down
        disableSystemProtection(); // S8: ensure the watchdog stops if a transfer was cut short
    }


    // WATCHDOG PROTECTION UTILS
    @Override
    public void enableSystemProtection() {
        Context ctx = getContext();
        if (ctx == null) return; // S8: detached -> nothing to protect
        Intent intent = new Intent(ctx, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    @Override
    public void disableSystemProtection() {
        Context ctx = getContext();
        if (ctx == null) return; // S8: detached; onDestroyView already handled teardown
        Intent intent = new Intent(ctx, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_STOP);
        ctx.startService(intent);
    }

    // SYSTEM RESTRICTION ENFORCER (PPK & CHILD PROCESSES)
    /** ADFA-4496: "optimized" now means the phantom-process monitor is NOT active (live check). */
    @Override
    public boolean isSystemOptimizedForSync() {
        return !org.iiab.controller.sync.transport.PhantomProcessHelper.isMonitoringLikelyActive(getContext());
    }

    /** ADFA-4496: informed pre-flight dialog — offers the version-appropriate remedy plus
     *  "continue anyway" (the reactive safety net catches an actual kill). */
    @Override
    public void showPhantomWarningDialog(Runnable onContinue) {
        if (getContext() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.phantom_warn_title))
                .setMessage(getString(R.string.phantom_warn_body))
                .setIcon(android.R.drawable.ic_dialog_alert);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            b.setPositiveButton(getString(R.string.phantom_warn_open_dev), (dialog, which) ->
                    org.iiab.controller.sync.transport.PhantomProcessHelper.openDeveloperOptions(requireContext()));
        } else {
            b.setPositiveButton(getString(R.string.adb_enforcer_btn_setup), (dialog, which) -> {
                requireContext().getSharedPreferences(ADB_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_FOCUS_ADB, true).apply();
                MainActivity mainAct = (MainActivity) getActivity();
                if (mainAct != null) {
                    androidx.viewpager2.widget.ViewPager2 pager = mainAct.findViewById(R.id.view_pager);
                    if (pager != null) pager.setCurrentItem(2, true);
                }
            });
        }
        b.setNeutralButton(getString(R.string.phantom_warn_continue), (dialog, which) -> {
            if (onContinue != null) onContinue.run();
        });
        b.setNegativeButton(getString(R.string.cancel), null);
        b.show();
    }

    /** Extracted from the Start-server click so the pre-flight can run it after "continue". */

    /** Extracted from the Scan-QR click so the pre-flight can run it after "continue". */
    private void startReceiveFlow() {
        MainActivity mainActivity = (MainActivity) getActivity();

        // EX6: the "safe to receive now?" rule lives in the pure TransferGuard domain.
        boolean serverRunning = mainActivity != null && mainActivity.isServerAlive;
        if (!org.iiab.controller.sync.domain.TransferGuard.canReceive(serverRunning).allowed) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.sync_dialog_server_running_title))
                    .setMessage(getString(R.string.sync_error_stop_server_first))
                    .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            return;
        }

        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.sync_scanner_prompt));
        options.setCameraId(0);
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        barcodeLauncher.launch(options);

    }



    // --- ArchCheckHost (ADFA-4506) -----------------------------------------
    @Override
    public boolean isServerRunning() {
        return shareController.isServerRunning();
    }

    @Override
    public boolean isShareMode() {
        return rgSyncMode.getCheckedRadioButtonId() == R.id.rb_mode_share;
    }
    // --- ShareHost (ADFA-4506) ---------------------------------------------
    @Override
    public boolean isServerAlive() {
        MainActivity a = (MainActivity) getActivity();
        return a != null && a.isServerAlive;
    }

    @Override
    public void updateArchLabelsVisibility() {
        archCheckController.updateArchLabelsVisibility();
    }

    @Override
    public int getArchBits() {
        return archCheckController.getArchBits();
    }
}
