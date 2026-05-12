/*
 * ============================================================================
 * Name        : SyncFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Java wrapper for the native librsync.so binary
 * ============================================================================
 */

package org.iiab.controller;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.Context;
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
import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

public class SyncFragment extends Fragment {

    private RadioGroup rgSyncMode;
    private LinearLayout containerShare, containerReceive, containerProgress;

    // Share UI
    private ImageView imgQrCode;
    private TextView txtShareStatus;
    private Button btnStartServer;

    // Receive UI
    private Button btnScanQr, btnCancelTransfer;
    private TextView txtTransferFilename, txtTransferSpeed, txtTransferEta;
    private ProgressBar progressBarTransfer;

    private RsyncManager rsyncManager;
    private boolean isDaemonRunning = false;

    private String wifiIp = null;
    private String hotspotIp = null;
    private boolean showingWifi = true;
    private View qrCardContainer;
    private ImageButton btnFlipQr;
    private int currentRsyncPort = 8730;
    private String tempUser = "iiab_peer";
    private String tempPass;
    private boolean hostHasRootfs = true; // State tracker

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
        rsyncManager = new RsyncManager();

        rgSyncMode = view.findViewById(R.id.rg_sync_mode);
        containerShare = view.findViewById(R.id.container_share);
        containerReceive = view.findViewById(R.id.container_receive);
        containerProgress = view.findViewById(R.id.container_progress);

        imgQrCode = view.findViewById(R.id.img_qr_code);
        txtShareStatus = view.findViewById(R.id.txt_share_status);
        btnStartServer = view.findViewById(R.id.btn_start_server);

        btnScanQr = view.findViewById(R.id.btn_scan_qr);
        btnCancelTransfer = view.findViewById(R.id.btn_cancel_transfer);
        txtTransferFilename = view.findViewById(R.id.txt_transfer_filename);
        txtTransferSpeed = view.findViewById(R.id.txt_transfer_speed);
        txtTransferEta = view.findViewById(R.id.txt_transfer_eta);
        progressBarTransfer = view.findViewById(R.id.progress_bar_transfer);

        qrCardContainer = view.findViewById(R.id.qr_card_container);
        btnFlipQr = view.findViewById(R.id.btn_flip_qr);

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
        });
    }

    private void setupShareLogic() {
        btnFlipQr.setOnClickListener(v -> {
            showingWifi = !showingWifi;
            updateQrDisplay();
        });

        btnStartServer.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity == null) return;

            // ENFORCE ADB OPTIMIZATION BEFORE SERVING
            if (!isSystemOptimizedForSync()) {
                showOptimizationRequiredDialog();
                return;
            }

            if (mainActivity.isServerAlive) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Server is Running")
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

                // WARNING 1: Check if rootfs actually exists on the server
                File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
                hostHasRootfs = rootfsDir.exists() && rootfsDir.isDirectory();

                if (!hostHasRootfs) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Missing Environment")
                            .setMessage("There doesn't seem to be an IIAB environment installed on this device. Do you want to start the share daemon anyway?")
                            .setPositiveButton("Yes, continue", (dialog, which) -> startShareDaemon(rootfsDir))
                            .setNegativeButton(getString(R.string.cancel), null)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                } else {
                    startShareDaemon(rootfsDir);
                }
            } else {
                // PHASE 1 FIX: Guardrail for stopping the server
                new AlertDialog.Builder(requireContext())
                        .setTitle("Stop Share Daemon?")
                        .setMessage("This will immediately disconnect any peers currently downloading the system. Are you sure you want to stop?")
                        .setPositiveButton(getString(R.string.sync_btn_stop_server), (dialog, which) -> stopShareDaemon())
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            }
        });
    }

    private void fetchNetworkInterfaces() {
        wifiIp = null;
        hotspotIp = null;
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName();
                if (!intf.isUp()) continue;

                boolean isStrictWifi = name.equals("wlan0");
                boolean isHotspot = name.startsWith("ap") || name.startsWith("swlan") || name.equals("wlan1") || name.equals("wlan2");

                if (isStrictWifi || isHotspot) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            if (isStrictWifi) wifiIp = addr.getHostAddress();
                            if (isHotspot) hotspotIp = addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void startShareDaemon(File rootfsDir) {
        tempPass = SyncHandshakeHelper.generateSecurePassword();

        if (!rootfsDir.exists()) rootfsDir.mkdirs();

        boolean started = rsyncManager.startServer(requireContext(), currentRsyncPort, tempUser, tempPass, rootfsDir.getAbsolutePath());

        if (started) {
            isDaemonRunning = true;
            enableSystemProtection();
            qrCardContainer.setVisibility(View.VISIBLE);

            showingWifi = (wifiIp != null);
            btnFlipQr.setVisibility((wifiIp != null && hotspotIp != null) ? View.VISIBLE : View.GONE);

            updateQrDisplay();

            btnStartServer.setText(getString(R.string.sync_btn_stop_server));
            btnStartServer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336"))); // Red
        } else {
            Toast.makeText(getContext(), getString(R.string.sync_error_daemon_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateQrDisplay() {
        String currentIp = showingWifi ? wifiIp : hotspotIp;
        String label = showingWifi ? getString(R.string.sync_label_wifi) : getString(R.string.sync_label_hotspot);

        // Pass the hostHasRootfs flag to the QR payload
        String jsonPayload = SyncHandshakeHelper.createPayload(currentIp, currentRsyncPort, tempUser, tempPass, hostHasRootfs);
        Bitmap qrBitmap = SyncHandshakeHelper.generateQrCode(jsonPayload, 500);

        if (qrBitmap != null) {
            imgQrCode.setImageBitmap(qrBitmap);
        }

        txtShareStatus.setText(getString(R.string.sync_share_status_active, label, currentIp, currentRsyncPort));
        txtShareStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
    }

    private void stopShareDaemon() {
        rsyncManager.stop();
        isDaemonRunning = false;
        disableSystemProtection();

        qrCardContainer.setVisibility(View.GONE);
        btnStartServer.setText(getString(R.string.sync_btn_start_server));
        btnStartServer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#008000"))); // Green
        txtShareStatus.setText(getString(R.string.sync_share_status_off));
        txtShareStatus.setTextColor(android.graphics.Color.parseColor("#FF9800")); // Orange
    }

    private void setupReceiveLogic() {
        btnScanQr.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();

            // ENFORCE ADB OPTIMIZATION BEFORE SCANNING
            if (!isSystemOptimizedForSync()) {
                showOptimizationRequiredDialog();
                return;
            }

            if (mainActivity != null && mainActivity.isServerAlive) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Server is Running")
                        .setMessage(getString(R.string.sync_error_stop_server_first))
                        .setPositiveButton(getString(R.string.adb_enforcer_btn_ok), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return;
            }

            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Scan the IIAB Host QR Code");
            options.setCameraId(0);
            options.setBeepEnabled(false);
            options.setBarcodeImageEnabled(false);
            barcodeLauncher.launch(options);
        });

        btnCancelTransfer.setOnClickListener(v -> {
            rsyncManager.stop();
            disableSystemProtection();
            containerProgress.setVisibility(View.GONE);
            btnScanQr.setVisibility(View.VISIBLE);
        });
    }

    private void handleScannedData(String scannedJson) {
        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(scannedJson);
        if (creds == null) {
            Toast.makeText(getContext(), "Invalid QR Code.", Toast.LENGTH_SHORT).show();
            return;
        }

        // WARNING 2: Check if the host reported having a rootfs
        if (!creds.hasRootfs) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Warning: Empty Host")
                    .setMessage("The host device reports that it does not have a complete IIAB environment installed. Do you still want to attempt the transfer?")
                    .setPositiveButton("Yes, try anyway", (dialog, which) -> checkNetworkAndStart(creds))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        } else {
            checkNetworkAndStart(creds);
        }
    }

    private void checkNetworkAndStart(SyncHandshakeHelper.SyncCredentials creds) {
        btnScanQr.setVisibility(View.GONE);
        containerProgress.setVisibility(View.VISIBLE);
        txtTransferFilename.setText("Connecting to Host...");
        progressBarTransfer.setIndeterminate(true);

        new Thread(() -> {
            boolean isReachable = false;
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(creds.ip, creds.port), 2000);
                socket.close();
                isReachable = true;
            } catch (Exception ignored) { }

            final boolean finalReachable = isReachable;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (finalReachable) {
                        File destDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");

                        // DRY-RUN PHASE
                        txtTransferFilename.setText(getString(R.string.sync_msg_calculating));

                        rsyncManager.calculateTransferPlan(requireContext(), creds.ip, creds.port, creds.user, creds.pass, destDir.getAbsolutePath(), new RsyncManager.DryRunListener() {
                            @Override
                            public void onCalculated(long bytesToTransfer) {
                                double gigabytes = bytesToTransfer / (1024.0 * 1024.0 * 1024.0);

                                // Storage validation
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

                                // Final Confirmation Dialog
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
                                .setTitle("Connection Failed")
                                .setMessage("Cannot reach Host at " + creds.ip + ".\nAre you sure you are connected to the same Wi-Fi network or Hotspot?")
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

        rsyncManager.startClient(requireContext(), creds.ip, creds.port, creds.user, creds.pass, destDir.getAbsolutePath(), new RsyncManager.SyncListener() {
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
        if (rsyncManager != null) {
            rsyncManager.stop();
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
            if ("256".equals(ppkValue) || "512".equals(ppkValue) || "1024".equals(ppkValue)) return true;
            try {
                if (ppkValue != null && Integer.parseInt(ppkValue) >= 256) return true;
            } catch (Exception ignored) {}
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
}