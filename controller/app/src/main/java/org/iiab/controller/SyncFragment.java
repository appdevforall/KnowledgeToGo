/*
 * ============================================================================
 * Name        : SyncFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
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

import org.iiab.controller.util.AppExecutors;
import androidx.activity.result.ActivityResultLauncher;

import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import android.widget.RadioButton;
import androidx.core.content.ContextCompat;

public class SyncFragment extends Fragment {

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
        transport = new RsyncManager();

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

            if (mainActivity != null && mainActivity.isServerAlive) {
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
                        .setPositiveButton(getString(R.string.sync_dialog_btn_try_anyway), (dialog, which) -> checkNetworkAndStart(creds))
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            } else {
                checkNetworkAndStart(creds);
            }
        });
    }

    private void checkNetworkAndStart(SyncHandshakeHelper.SyncCredentials creds) {
        btnScanQr.setVisibility(View.GONE);
        containerProgress.setVisibility(View.VISIBLE);
        txtTransferFilename.setText(getString(R.string.sync_msg_connecting));
        progressBarTransfer.setIndeterminate(true);

        new Thread(() -> {
            boolean isReachable = false;
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(creds.ip, creds.port), 2000);
                socket.close();
                isReachable = true;
            } catch (Exception ignored) {
            }

            final boolean finalReachable = isReachable;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (finalReachable) {
                        File destDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");

                        if (!destDir.exists()) {
                            destDir.mkdirs();
                        }

                        txtTransferFilename.setText(getString(R.string.sync_msg_calculating));

                        transport.calculateTransferPlan(requireContext(), shareConfig, creds.ip, creds.port, creds.user, creds.pass, destDir.getAbsolutePath(), new org.iiab.controller.sync.transport.TransportEngine.DryRunListener() {
                            @Override
                            public void onCalculated(long bytesToTransfer) {
                                double gigabytes = bytesToTransfer / (1024.0 * 1024.0 * 1024.0);
                                File dataDir = android.os.Environment.getDataDirectory();
                                double freeSpaceGb = dataDir.getFreeSpace() / (1024.0 * 1024.0 * 1024.0);

                                if (gigabytes > (freeSpaceGb - 5.0)) {
                                    new AlertDialog.Builder(requireContext())
                                            .setTitle(getString(R.string.sync_error_storage_title))
                                            .setMessage(getString(R.string.sync_error_storage_msg, gigabytes, freeSpaceGb))
                                            .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                                            .show();
                                    containerProgress.setVisibility(View.GONE);
                                    btnScanQr.setVisibility(View.VISIBLE);
                                    return;
                                }

                                String title = (destDir.exists() && destDir.list() != null && destDir.list().length > 0) ? getString(R.string.sync_title_update) : getString(R.string.sync_title_install);
                                String msg = getString(R.string.sync_msg_confirm_transfer, gigabytes);

                                new AlertDialog.Builder(requireContext())
                                        .setTitle(title)
                                        .setMessage(msg)
                                        .setPositiveButton(getString(R.string.sync_btn_start_transfer), (dialog, which) -> {
                                            startTransfer(creds, destDir);
                                        })
                                        .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                                            containerProgress.setVisibility(View.GONE);
                                            btnScanQr.setVisibility(View.VISIBLE);
                                        })
                                        .setCancelable(false)
                                        .show();
                            }

                            @Override
                            public void onError(String error) {
                                new AlertDialog.Builder(requireContext())
                                        .setTitle(getString(R.string.sync_error_calc_title))
                                        .setMessage(getString(R.string.sync_error_calc_msg, error))
                                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                                        .show();
                                containerProgress.setVisibility(View.GONE);
                                btnScanQr.setVisibility(View.VISIBLE);
                            }
                        });
                    } else {
                        new AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.sync_dialog_conn_failed_title))
                                .setMessage(getString(R.string.sync_dialog_conn_failed_msg, creds.ip))
                                .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                        containerProgress.setVisibility(View.GONE);
                        btnScanQr.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    private void startTransfer(SyncHandshakeHelper.SyncCredentials creds, File destDir) {
        enableSystemProtection();
        txtTransferFilename.setText(getString(R.string.sync_transfer_filename, "RootFS"));
        progressBarTransfer.setIndeterminate(false);
        progressBarTransfer.setProgress(0);

        if (!destDir.exists()) destDir.mkdirs();

        transport.startClient(requireContext(), shareConfig, creds.ip, creds.port, creds.user, creds.pass, destDir.getAbsolutePath(), new org.iiab.controller.sync.transport.TransportEngine.SyncListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta, String currentFile) {
                progressBarTransfer.setProgress(percentage);
                txtTransferSpeed.setText(speed);
                txtTransferEta.setText("ETA: " + eta);

                if (currentFile != null && !currentFile.isEmpty()) {
                    String displayFile = currentFile.length() > 40 ? "..." + currentFile.substring(currentFile.length() - 40) : currentFile;
                    txtTransferFilename.setText(getString(R.string.sync_transfer_filename, displayFile));
                }
            }

            @Override
            public void onComplete(String message) {
                disableSystemProtection();
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_success_title))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                        .show();
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(String error) {
                disableSystemProtection();
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.sync_error_title))
                        .setMessage(getString(R.string.sync_error_body, error))
                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                containerProgress.setVisibility(View.GONE);
                btnScanQr.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (transport != null) transport.stop();
        if (apkServer != null) apkServer.stop();
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
        Intent intent = new Intent(requireContext(), WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent);
        } else {
            requireContext().startService(intent);
        }
    }

    private void disableSystemProtection() {
        Intent intent = new Intent(requireContext(), WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_STOP);
        requireContext().startService(intent);
    }

    // SYSTEM RESTRICTION ENFORCER (PPK & CHILD PROCESSES)
    private boolean isSystemOptimizedForSync() {
        if (android.os.Build.VERSION.SDK_INT < 31) return true;

        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE);
        String cpValue = prefs.getString("child_process_value", null);
        String ppkValue = prefs.getString("ppk_value", null);

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            return "0".equals(cpValue) || "false".equals(cpValue);
        } else {
            if ("256".equals(ppkValue) || "512".equals(ppkValue) || "1024".equals(ppkValue))
                return true;
            try {
                if (ppkValue != null && Integer.parseInt(ppkValue) >= 256) return true;
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private void showOptimizationRequiredDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.adb_enforcer_title))
                .setMessage(getString(R.string.adb_enforcer_body))
                .setPositiveButton(getString(R.string.adb_enforcer_btn_setup), (dialog, which) -> {
                    requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("focus_adb", true).apply();

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
        } catch (Exception ignored) {
        }
        if (android.os.Build.SUPPORTED_ABIS.length > 0) return android.os.Build.SUPPORTED_ABIS[0];
        return "unknown";
    }

    private void showArchCompatibilitySuccess(Runnable onComplete) {
        android.os.Vibrator v = (android.os.Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
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
            txtGuestArchLabel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_success)));
            txtGuestArchLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_warning));
        }

        Snackbar.make(requireView(), getString(R.string.sync_msg_arch_compatible), Snackbar.LENGTH_SHORT).show();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (txtGuestArchLabel != null) {
                txtGuestArchLabel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_section)));
                txtGuestArchLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_success));
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
