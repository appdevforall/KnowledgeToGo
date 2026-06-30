/*
 * ============================================================================
 * Name        : SyncFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Fragment to handle P2P/P2M syncing and App sharing
 * ============================================================================
 */

package org.iiab.controller;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import org.iiab.controller.util.AppExecutors;
import androidx.activity.result.ActivityResultLauncher;

import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.util.List;

import android.widget.RadioButton;
import androidx.core.content.ContextCompat;

public class SyncFragment extends Fragment {

    private static final String TAG = "IIAB-SyncFragment";
    // S16: name the ADB-optimization prefs/keys (shared with the ADB-share tab).
    private static final String ADB_PREFS = "iiab_adb_prefs";
    private static final String PREF_CHILD_PROCESS = "child_process_value";
    private static final String PREF_PPK = "ppk_value";
    private static final String PREF_FOCUS_ADB = "focus_adb";
    private static final int MIN_PPK_LIMIT = 256; // min phantom-process limit that is "optimized"

    private RadioGroup rgSyncMode;
    private LinearLayout containerShare, containerReceive, containerProgress;

    // Share UI
    private ImageView imgQrCode;
    private TextView txtShareStatus;
    private Button btnStartServer;
    private Button btnShareApp;

    // Receive UI
    private Button btnScanQr, btnCancelTransfer;
    private TextView txtTransferFilename, txtTransferSpeed, txtTransferEta;
    private ProgressBar progressBarTransfer;

    // Arch Labels
    private TextView txtHostArchLabel;
    private TextView txtGuestArchLabel;

    // Managers
    private org.iiab.controller.sync.transport.TransportEngine transport;
    private org.iiab.controller.sync.presentation.SyncStateViewModel syncVm; // 3b-2: survives recreation
    private long lastTransferSeq = -1L; // 3b-2: fire terminal dialog once
    private ApkServer apkServer;

    // States
    private boolean isDaemonRunning = false;
    private boolean isApkServerRunning = false;
    private String wifiIp = null;
    private String hotspotIp = null;
    private boolean showingWifi = true;
    private View qrCardContainer;
    // S14 step 2: transport config (port/user/module/apk-port) — kills S7 hardcodes.
    private final org.iiab.controller.sync.domain.ShareConfig shareConfig = org.iiab.controller.sync.domain.ShareConfig.defaults();
    private String tempPass;
    private boolean hostHasRootfs = true;
    private LinearLayout qrDisplaySection;
    private RadioGroup rgNetworkSelector;
    private RadioButton rbNetWifi, rbNetHotspot;
    private LinearLayout cardShareSystem;
    private LinearLayout cardShareApk;

    private int getArchBits() {
        String arch = getTermuxArch();
        return (arch != null && arch.contains("64")) ? 64 : 32;
    }

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

        rgSyncMode = view.findViewById(R.id.rg_sync_mode);
        containerShare = view.findViewById(R.id.container_share);
        containerReceive = view.findViewById(R.id.container_receive);
        containerProgress = view.findViewById(R.id.container_progress);

        imgQrCode = view.findViewById(R.id.img_qr_code);
        txtShareStatus = view.findViewById(R.id.txt_share_status);
        btnStartServer = view.findViewById(R.id.btn_start_server);
        btnShareApp = view.findViewById(R.id.btn_share_app);

        qrDisplaySection = view.findViewById(R.id.qr_display_section);
        rgNetworkSelector = view.findViewById(R.id.rg_network_selector);
        rbNetWifi = view.findViewById(R.id.rb_net_wifi);
        rbNetHotspot = view.findViewById(R.id.rb_net_hotspot);
        cardShareSystem = view.findViewById(R.id.card_share_system);
        cardShareApk = view.findViewById(R.id.card_share_apk);

        // Listener of the new network switch
        rgNetworkSelector.setOnCheckedChangeListener((group, checkedId) -> {
            // Crossfade animation for QR
            imgQrCode.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                showingWifi = (checkedId == R.id.rb_net_wifi);

                // We updated selector text colors
                rbNetWifi.setTextColor(showingWifi ? getResources().getColor(R.color.dash_text_primary) : getResources().getColor(R.color.dash_text_secondary));
                rbNetHotspot.setTextColor(!showingWifi ? getResources().getColor(R.color.dash_text_primary) : getResources().getColor(R.color.dash_text_secondary));

                // We reload the QR according to the active server
                if (isDaemonRunning) updateQrDisplayRsync();
                if (isApkServerRunning) updateQrDisplayApk();

                // We show the new QR smoothly
                imgQrCode.animate().alpha(1f).setDuration(150).start();
            }).start();
        });

        btnScanQr = view.findViewById(R.id.btn_scan_qr);
        btnCancelTransfer = view.findViewById(R.id.btn_cancel_transfer);
        txtTransferFilename = view.findViewById(R.id.txt_transfer_filename);
        txtTransferSpeed = view.findViewById(R.id.txt_transfer_speed);
        txtTransferEta = view.findViewById(R.id.txt_transfer_eta);
        progressBarTransfer = view.findViewById(R.id.progress_bar_transfer);
        qrCardContainer = view.findViewById(R.id.qr_card_container);

        txtHostArchLabel = view.findViewById(R.id.txt_host_arch_label);
        txtGuestArchLabel = view.findViewById(R.id.txt_guest_arch_label);

        // Static Arch Labels logic (Cleaned up with string resources)
        String archLabelText = getString(R.string.sync_app_arch_label, getArchBits());

        if (txtHostArchLabel != null) txtHostArchLabel.setText(archLabelText);
        if (txtGuestArchLabel != null) txtGuestArchLabel.setText(archLabelText);

        updateArchLabelsVisibility();
        setupToggleLogic();
        setupShareLogic();
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
            updateArchLabelsVisibility();
        });
    }

    private void setupShareLogic() {
        // --------------------------------------------------------------------
        // RSYNC SERVER LOGIC (For Data Syncing)
        // --------------------------------------------------------------------
        btnStartServer.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity == null) return;

            if (!isSystemOptimizedForSync()) {
                showOptimizationRequiredDialog();
                return;
            }

            if (isApkServerRunning) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_dialog_server_running_title))
                        .setMessage(getString(R.string.sync_error_stop_apk_first))
                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return;
            }

            if (mainActivity.isServerAlive) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_dialog_server_running_title))
                        .setMessage(getString(R.string.sync_error_stop_server_first))
                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return;
            }

            if (!isDaemonRunning) {
                fetchNetworkInterfaces();
                if (wifiIp == null && hotspotIp == null) {
                    Toast.makeText(getContext(), getString(R.string.sync_error_no_network), Toast.LENGTH_SHORT).show();
                    return;
                }

                File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
                hostHasRootfs = rootfsDir.exists() && rootfsDir.isDirectory();

                if (!hostHasRootfs) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.sync_dialog_missing_env_title))
                            .setMessage(getString(R.string.sync_dialog_missing_env_msg))
                            .setPositiveButton(getString(R.string.sync_dialog_btn_continue), (dialog, which) -> startShareDaemon(rootfsDir))
                            .setNegativeButton(getString(R.string.cancel), null)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                } else {
                    startShareDaemon(rootfsDir);
                }
            } else {
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_dialog_stop_title))
                        .setMessage(getString(R.string.sync_dialog_stop_msg))
                        .setPositiveButton(getString(R.string.sync_btn_stop_server), (dialog, which) -> stopShareDaemon())
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            }
        });

        // --------------------------------------------------------------------
        // APK SERVER LOGIC (For App Sharing / Bootstrap)
        // --------------------------------------------------------------------
        btnShareApp.setOnClickListener(v -> {
            if (isDaemonRunning) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_dialog_server_running_title))
                        .setMessage(getString(R.string.sync_error_stop_server_first))
                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return;
            }

            if (!isApkServerRunning) {
                fetchNetworkInterfaces();
                if (wifiIp == null && hotspotIp == null) {
                    Toast.makeText(getContext(), getString(R.string.sync_error_no_network), Toast.LENGTH_SHORT).show();
                    return;
                }
                startApkServer();
            } else {
                stopApkServer();
            }
        });
    }

    private void fetchNetworkInterfaces() {
        // EX3: single source of LAN IP discovery (shared with QrActivity).
        org.iiab.controller.sync.transport.NetworkInterfaces.LanIps ips = org.iiab.controller.sync.transport.NetworkInterfaces.discover();
        wifiIp = ips.wifiIp;
        hotspotIp = ips.hotspotIp;
    }

    // --- RSYNC DAEMON METHODS ---
    private void startShareDaemon(File rootfsDir) {
        tempPass = SyncHandshakeHelper.generateSecurePassword();
        if (!rootfsDir.exists()) rootfsDir.mkdirs();

        // Start the rsync daemon off the main thread (file IO + ProcessBuilder.start
        // would otherwise risk an ANR on the UI thread); apply the result on the UI.
        final String shareDir = rootfsDir.getAbsolutePath();
        AppExecutors.get().io().execute(() -> {
            boolean started = transport.startServer(requireContext(), shareConfig, tempPass, shareDir);
            if (!isAdded() || getActivity() == null) return;
            requireActivity().runOnUiThread(() -> onShareDaemonResult(started));
        });
    }

    private void onShareDaemonResult(boolean started) {
        if (!isAdded()) return;
        if (started) {
            isDaemonRunning = true;
            enableSystemProtection();
            updateArchLabelsVisibility();

            qrDisplaySection.setVisibility(View.VISIBLE);
            qrCardContainer.setVisibility(View.VISIBLE);
            imgQrCode.setAlpha(1f);
            cardShareSystem.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_active_success)));

            rgNetworkSelector.setVisibility((wifiIp != null && hotspotIp != null) ? View.VISIBLE : View.GONE);
            showingWifi = (wifiIp != null);
            updateQrDisplayRsync();

            btnStartServer.setText(getString(R.string.sync_btn_stop_server));
            btnStartServer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_danger))); // Red
            btnShareApp.setVisibility(View.GONE);
        } else {
            Toast.makeText(getContext(), getString(R.string.sync_error_daemon_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateQrDisplayRsync() {
        String currentIp = showingWifi ? wifiIp : hotspotIp;
        String jsonPayload = SyncHandshakeHelper.createPayload(currentIp, shareConfig.rsyncPort, shareConfig.user, tempPass, hostHasRootfs, getArchBits());
        Bitmap qrBitmap = SyncHandshakeHelper.generateQrCode(jsonPayload, 500);

        if (qrBitmap != null) imgQrCode.setImageBitmap(qrBitmap);

        String baseText = showingWifi ? getString(R.string.sync_share_status_wifi) : getString(R.string.sync_share_status_hotspot);
        txtShareStatus.setText(baseText);
        txtShareStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
    }

    private void stopShareDaemon() {
        transport.stop();
        isDaemonRunning = false;
        disableSystemProtection();
        updateArchLabelsVisibility();

        qrDisplaySection.setVisibility(View.GONE);
        btnStartServer.setText(getString(R.string.sync_btn_start_server));
        cardShareSystem.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_card)));

        btnStartServer.setText(getString(R.string.sync_btn_start_server));
        btnStartServer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_success))); // Green
        btnShareApp.setVisibility(View.VISIBLE);
        txtShareStatus.setText(getString(R.string.sync_share_status_off));
        txtShareStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warning)); // Orange
    }

    // --- APK SERVER METHODS ---

    private void startApkServer() {
        try {
            String myApkPath = requireContext().getApplicationInfo().sourceDir;

            apkServer = new ApkServer(shareConfig.apkPort, myApkPath);
            apkServer.start();
            isApkServerRunning = true;

            qrDisplaySection.setVisibility(View.VISIBLE);
            qrCardContainer.setVisibility(View.VISIBLE);
            updateCardOrder(true);
            imgQrCode.setAlpha(1f);
            cardShareApk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_active_info)));

            rgNetworkSelector.setVisibility((wifiIp != null && hotspotIp != null) ? View.VISIBLE : View.GONE);
            showingWifi = (wifiIp != null);
            updateQrDisplayApk();

            btnShareApp.setText(getString(R.string.sync_btn_stop_app));
            btnShareApp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_danger))); // Red
            btnStartServer.setVisibility(View.GONE);

        } catch (Exception e) {
            Log.e("SyncFragment", "Error starting APK Server", e);
            Toast.makeText(getContext(), getString(R.string.sync_error_daemon_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateQrDisplayApk() {
        String currentIp = showingWifi ? wifiIp : hotspotIp;
        String downloadUrl = "http://" + currentIp + ":" + shareConfig.apkPort + "/IIAB-Controller-Latest";
        Bitmap qrBitmap = SyncHandshakeHelper.generateQrCode(downloadUrl, 500);

        if (qrBitmap != null) imgQrCode.setImageBitmap(qrBitmap);

        String baseText = showingWifi ? getString(R.string.sync_app_status_wifi) : getString(R.string.sync_app_status_hotspot);
        txtShareStatus.setText(baseText);
        txtShareStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
    }

    private void stopApkServer() {
        if (apkServer != null) {
            apkServer.stop();
            apkServer = null;
        }
        isApkServerRunning = false;
        updateCardOrder(false);

        qrDisplaySection.setVisibility(View.GONE);
        btnShareApp.setText(getString(R.string.sync_btn_share_app));
        cardShareApk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_card)));

        btnShareApp.setText(getString(R.string.sync_btn_share_app));
        btnShareApp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_info))); // Blue
        btnStartServer.setVisibility(View.VISIBLE);
        txtShareStatus.setText(getString(R.string.sync_share_status_off));
        txtShareStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warning)); // Orange
    }

    // --- CLIENT (RECEIVER) METHODS ---

    private void setupReceiveLogic() {
        btnScanQr.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();

            if (!isSystemOptimizedForSync()) {
                showOptimizationRequiredDialog();
                return;
            }

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
        });

        btnCancelTransfer.setOnClickListener(v -> {
            transport.stop();
            disableSystemProtection();
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
        int guestBits = getArchBits();

        if (hostBits != 0 && hostBits != guestBits) {
            if (hostBits == 64 && guestBits == 32) {
                showArchIncompatibilityDialog(getString(R.string.sync_error_arch_hardware_32));
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
                    showArchIncompatibilityDialog(getString(R.string.sync_error_arch_fixable));
                } else {
                    showArchIncompatibilityDialog(getString(R.string.sync_error_arch_strict_64));
                }
                return;
            }
        }

        // --- EVERYTHING IS OK: PROCEED TO DOWNLOAD ---
        showArchCompatibilitySuccess(() -> {
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
        if (apkServer != null) apkServer.stop();
        disableSystemProtection(); // S8: ensure the watchdog stops if a transfer was cut short
    }

    private void updateCardOrder(boolean isApkActive) {
        // We remove both cards from the parent container
        containerShare.removeView(cardShareSystem);
        containerShare.removeView(cardShareApk);

        if (isApkActive) {
            // If APK is active, we paste the APK one first (it will be on top) and then the System one
            containerShare.addView(cardShareApk);
            containerShare.addView(cardShareSystem);
        } else {
            // By default (or if System is active), we paste System first and then APK
            containerShare.addView(cardShareSystem);
            containerShare.addView(cardShareApk);
        }
    }

    // WATCHDOG PROTECTION UTILS
    private void enableSystemProtection() {
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

    private void disableSystemProtection() {
        Context ctx = getContext();
        if (ctx == null) return; // S8: detached; onDestroyView already handled teardown
        Intent intent = new Intent(ctx, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_STOP);
        ctx.startService(intent);
    }

    // SYSTEM RESTRICTION ENFORCER (PPK & CHILD PROCESSES)
    private boolean isSystemOptimizedForSync() {
        if (android.os.Build.VERSION.SDK_INT < 31) return true;

        android.content.SharedPreferences prefs = requireContext().getSharedPreferences(ADB_PREFS, Context.MODE_PRIVATE);
        String cpValue = prefs.getString(PREF_CHILD_PROCESS, null);
        String ppkValue = prefs.getString(PREF_PPK, null);

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            return "0".equals(cpValue) || "false".equals(cpValue);
        } else {
            if ("256".equals(ppkValue) || "512".equals(ppkValue) || "1024".equals(ppkValue)) // fast-path known-good values
                return true;
            try {
                if (ppkValue != null && Integer.parseInt(ppkValue) >= MIN_PPK_LIMIT) return true;
            } catch (NumberFormatException e) {
                Log.w(TAG, "Unparseable ppk_value: " + ppkValue, e);
            }
            return false;
        }
    }

    private void showOptimizationRequiredDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.adb_enforcer_title))
                .setMessage(getString(R.string.adb_enforcer_body))
                .setPositiveButton(getString(R.string.adb_enforcer_btn_setup), (dialog, which) -> {
                    requireContext().getSharedPreferences(ADB_PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(PREF_FOCUS_ADB, true).apply();

                    MainActivity mainAct = (MainActivity) getActivity();
                    if (mainAct != null) {
                        androidx.viewpager2.widget.ViewPager2 pager = mainAct.findViewById(R.id.view_pager);
                        if (pager != null) pager.setCurrentItem(2, true);
                    }
                })
                .setNegativeButton(getString(R.string.adb_enforcer_btn_ok), null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void showArchIncompatibilityDialog(String message) {
        android.os.Vibrator v = (android.os.Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(500);
            }
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.sync_error_arch_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private String getTermuxArch() {
        try {
            android.content.pm.ApplicationInfo info = requireContext().getApplicationInfo();
            String nativeLibDir = info.nativeLibraryDir;
            if (nativeLibDir != null) {
                if (nativeLibDir.endsWith("arm64") || nativeLibDir.contains("arm64-v8a"))
                    return "arm64-v8a";
                if (nativeLibDir.endsWith("arm") || nativeLibDir.contains("armeabi-v7a"))
                    return "armeabi-v7a";
                if (nativeLibDir.endsWith("x86_64") || nativeLibDir.contains("x86_64"))
                    return "x86_64";
                if (nativeLibDir.endsWith("x86") || nativeLibDir.contains("x86")) return "x86";
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read native library dir for arch detection", e);
        }
        if (android.os.Build.SUPPORTED_ABIS.length > 0) return android.os.Build.SUPPORTED_ABIS[0];
        return "unknown";
    }

    private void showArchCompatibilitySuccess(Runnable onComplete) {
        // S8: this runs in the pre-transfer probing phase. A theme toggle / config
        // change here detaches the fragment, so guard every context/view access and
        // re-check inside the delayed runnable before touching the UI (was an
        // IllegalStateException "not attached to a context" crash).
        Context ctx = getContext();
        if (!isAdded() || ctx == null || getView() == null) return;

        android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                long[] pattern = {0, 100, 100, 150};
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
            } else {
                long[] pattern = {0, 100, 100, 150};
                v.vibrate(pattern, -1);
            }
        }

        if (txtGuestArchLabel != null) {
            txtGuestArchLabel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.status_success)));
            txtGuestArchLabel.setTextColor(ContextCompat.getColor(ctx, R.color.text_on_warning));
        }

        Snackbar.make(getView(), getString(R.string.sync_msg_arch_compatible), Snackbar.LENGTH_SHORT).show();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Context laterCtx = getContext();
            if (!isAdded() || laterCtx == null) return; // S8: fragment gone during the 1.5s delay
            if (txtGuestArchLabel != null) {
                txtGuestArchLabel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(laterCtx, R.color.surface_section)));
                txtGuestArchLabel.setTextColor(ContextCompat.getColor(laterCtx, R.color.status_success));
            }
            onComplete.run();
        }, 1500);
    }
    private void updateArchLabelsVisibility() {
        boolean isShareMode = (rgSyncMode.getCheckedRadioButtonId() == R.id.rb_mode_share);
        boolean isServerRunning = isDaemonRunning || isApkServerRunning;

        if (isShareMode) {
            // In Send mode: if the server is running, the file is up. We hide the one below.
            // If the server is NOT running, we show the one below.
            if (txtGuestArchLabel != null) {
                txtGuestArchLabel.setVisibility(isServerRunning ? View.GONE : View.VISIBLE);
            }
        } else {
            // In Receive mode: We always show the one below.
            if (txtGuestArchLabel != null) {
                txtGuestArchLabel.setVisibility(View.VISIBLE);
            }
        }
    }
}
