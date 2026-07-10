/*
 * ============================================================================
 * Name        : ShareController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The Share area of the Sync tab, carved out of SyncFragment
 *               (strangler-fig, ADFA-4506). Owns the rsync data daemon and the
 *               APK-sharing server together, because they are mutually exclusive
 *               and share one QR canvas, the network selector, the card ordering
 *               and each other's button visibility. System-protection (Watchdog /
 *               phantom pre-flight) is shared with the Receive flow and stays on
 *               the Fragment, reached through ShareHost. No behaviour change.
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;

import org.iiab.controller.ApkServer;
import org.iiab.controller.R;
import org.iiab.controller.SyncHandshakeHelper;
import org.iiab.controller.sync.domain.ApkShareName;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.transport.NetworkInterfaces;
import org.iiab.controller.sync.transport.TransportEngine;
import org.iiab.controller.ui.dialog.BrandDialog;
import org.iiab.controller.util.AppExecutors;

public final class ShareController {

    private static final String TAG = "IIAB-ShareController";

    private final Fragment fragment;
    private final ShareHost host;

    // Borrowed collaborators (set in bind()).
    private TransportEngine transport;
    private ShareConfig shareConfig;

    // Borrowed views (set in bind()).
    private LinearLayout containerShare;
    private Button btnStartServer;
    private Button btnShareApp;
    private ImageView imgQrCode;
    private TextView txtShareStatus;
    private TextView txtShareIp; // ADFA-4496: advertised IP + interface under the QR
    private LinearLayout qrDisplaySection;
    private View qrCardContainer;
    private RadioGroup rgNetworkSelector;
    private RadioButton rbNetWifi, rbNetHotspot;
    private LinearLayout cardShareSystem;
    private LinearLayout cardShareApk;

    // Owned state.
    private ApkServer apkServer;
    private String apkFileName; // K2Go-<version>-<arch>.apk, shared by the header and the QR URL (ADFA-4540)
    private boolean isDaemonRunning = false;
    private boolean isApkServerRunning = false;
    private String wifiIp = null;
    private String hotspotIp = null;
    private boolean showingWifi = true;
    private String tempPass;
    private boolean hostHasRootfs = true;

    public ShareController(Fragment fragment, ShareHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Borrow the transport + config and wire the Share views/listeners. Called from onCreateView(). */
    public void bind(View root, TransportEngine transport, ShareConfig shareConfig) {
        this.transport = transport;
        this.shareConfig = shareConfig;

        containerShare = root.findViewById(R.id.container_share);
        imgQrCode = root.findViewById(R.id.img_qr_code);
        txtShareStatus = root.findViewById(R.id.txt_share_status);
        btnStartServer = root.findViewById(R.id.btn_start_server);
        btnShareApp = root.findViewById(R.id.btn_share_app);
        txtShareIp = root.findViewById(R.id.txt_share_ip);
        qrDisplaySection = root.findViewById(R.id.qr_display_section);
        rgNetworkSelector = root.findViewById(R.id.rg_network_selector);
        rbNetWifi = root.findViewById(R.id.rb_net_wifi);
        rbNetHotspot = root.findViewById(R.id.rb_net_hotspot);
        cardShareSystem = root.findViewById(R.id.card_share_system);
        cardShareApk = root.findViewById(R.id.card_share_apk);
        qrCardContainer = root.findViewById(R.id.qr_card_container);

        // Network switch: crossfade the QR and reload it for the active server.
        rgNetworkSelector.setOnCheckedChangeListener((group, checkedId) -> {
            imgQrCode.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                showingWifi = (checkedId == R.id.rb_net_wifi);
                rbNetWifi.setTextColor(showingWifi ? fragment.getResources().getColor(R.color.dash_text_primary) : fragment.getResources().getColor(R.color.dash_text_secondary));
                rbNetHotspot.setTextColor(!showingWifi ? fragment.getResources().getColor(R.color.dash_text_primary) : fragment.getResources().getColor(R.color.dash_text_secondary));
                if (isDaemonRunning) updateQrDisplayRsync();
                if (isApkServerRunning) updateQrDisplayApk();
                imgQrCode.animate().alpha(1f).setDuration(150).start();
            }).start();
        });

        // RSYNC SERVER LOGIC (data syncing) — informed pre-flight, then startShareFlow().
        btnStartServer.setOnClickListener(v -> {
            if (fragment.getActivity() == null) return;
            if (!host.isSystemOptimizedForSync()) {
                host.showPhantomWarningDialog(this::startShareFlow);
                return;
            }
            startShareFlow();
        });

        // APK SERVER LOGIC (app sharing / bootstrap).
        btnShareApp.setOnClickListener(v -> {
            if (isDaemonRunning) {
                new BrandDialog(fragment.requireContext())
                        .setTitle(R.string.sync_dialog_server_running_title)
                        .setMessage(R.string.sync_error_stop_server_first)
                        .setPositive(R.string.adb_enforcer_btn_ok, BrandDialog.Role.PRIMARY, null)
                        .show();
                return;
            }

            if (!isApkServerRunning) {
                fetchNetworkInterfaces();
                if (wifiIp == null && hotspotIp == null) {
                    Toast.makeText(fragment.getContext(), fragment.getString(R.string.sync_error_no_network), Toast.LENGTH_SHORT).show();
                    return;
                }
                startApkServer();
            } else {
                stopApkServer();
            }
        });
    }

    /** True while the rsync daemon or the APK server is running. */
    public boolean isServerRunning() {
        return isDaemonRunning || isApkServerRunning;
    }

    /** Quiet teardown for onDestroyView (no UI touches); the shared transport is stopped by the Fragment. */
    public void stopApkServerQuietly() {
        if (apkServer != null) {
            apkServer.stop();
            apkServer = null;
        }
        isApkServerRunning = false;
    }

    private void fetchNetworkInterfaces() {
        // EX3: single source of LAN IP discovery (shared with QrActivity).
        NetworkInterfaces.LanIps ips = NetworkInterfaces.discover();
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
            boolean started = transport.startServer(fragment.requireContext(), shareConfig, tempPass, shareDir);
            if (!fragment.isAdded() || fragment.getActivity() == null) return;
            fragment.requireActivity().runOnUiThread(() -> onShareDaemonResult(started));
        });
    }

    private void onShareDaemonResult(boolean started) {
        if (!fragment.isAdded()) return;
        if (started) {
            isDaemonRunning = true;
            host.enableSystemProtection();
            host.updateArchLabelsVisibility();

            qrDisplaySection.setVisibility(View.VISIBLE);
            qrCardContainer.setVisibility(View.VISIBLE);
            imgQrCode.setAlpha(1f);
            cardShareSystem.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.surface_active_success)));

            rgNetworkSelector.setVisibility((wifiIp != null && hotspotIp != null) ? View.VISIBLE : View.GONE);
            showingWifi = (wifiIp != null);
            updateQrDisplayRsync();

            btnStartServer.setText(fragment.getString(R.string.sync_btn_stop_server));
            btnStartServer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger))); // Red
            btnShareApp.setVisibility(View.GONE);
        } else {
            Toast.makeText(fragment.getContext(), fragment.getString(R.string.sync_error_daemon_failed), Toast.LENGTH_SHORT).show();
        }
    }

    /** ADFA-4496: show the IP (and which interface) the QR advertises, so a stale QR from a
     *  previous network is obvious instead of looking like a transfer bug. */
    private void updateShareIpLabel(String ip) {
        if (txtShareIp == null) return;
        if (ip == null) {
            txtShareIp.setVisibility(View.GONE);
            return;
        }
        String iface = fragment.getString(showingWifi ? R.string.wifi : R.string.hotspot);
        txtShareIp.setText(iface + "   " + ip);
        txtShareIp.setVisibility(View.VISIBLE);
    }

    private void updateQrDisplayRsync() {
        String currentIp = showingWifi ? wifiIp : hotspotIp;
        updateShareIpLabel(currentIp);
        String jsonPayload = SyncHandshakeHelper.createPayload(currentIp, shareConfig.rsyncPort, shareConfig.user, tempPass, hostHasRootfs, host.getArchBits());
        Bitmap qrBitmap = SyncHandshakeHelper.generateQrCode(jsonPayload, 500);

        if (qrBitmap != null) imgQrCode.setImageBitmap(qrBitmap);

        String baseText = showingWifi ? fragment.getString(R.string.sync_share_status_wifi) : fragment.getString(R.string.sync_share_status_hotspot);
        txtShareStatus.setText(baseText);
        txtShareStatus.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.text_secondary));
    }

    private void stopShareDaemon() {
        transport.stop();
        isDaemonRunning = false;
        host.disableSystemProtection();
        host.updateArchLabelsVisibility();

        qrDisplaySection.setVisibility(View.GONE);
        btnStartServer.setText(fragment.getString(R.string.sync_btn_start_server));
        cardShareSystem.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.surface_card)));

        btnStartServer.setText(fragment.getString(R.string.sync_btn_start_server));
        btnStartServer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_success))); // Green
        btnShareApp.setVisibility(View.VISIBLE);
        txtShareStatus.setText(fragment.getString(R.string.sync_share_status_off));
        txtShareStatus.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_warning)); // Orange
    }

    // --- APK SERVER METHODS ---
    /**
     * Arch label of the APK we are about to share, read from its own {@code lib/<abi>/}
     * folders (the file that travels), not from the device. A universal build therefore
     * gets "universal" even on a single-ABI phone. Falls back to the device primary ABI
     * only if the APK can't be read. ADFA-4540.
     */
    private String apkArch(String apkPath) {
        java.util.Set<String> abis = new java.util.LinkedHashSet<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/")) {
                    int slash = name.indexOf('/', 4);
                    if (slash > 4) {
                        abis.add(name.substring(4, slash));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read ABIs from APK; falling back to device ABI", e);
            String[] dev = android.os.Build.SUPPORTED_ABIS;
            if (dev != null && dev.length > 0) {
                abis.add(dev[0]);
            }
        }
        return ApkShareName.archLabel(abis);
    }

    private void startApkServer() {
        try {
            String myApkPath = fragment.requireContext().getApplicationInfo().sourceDir;

            // ADFA-4540: stamp the download name with brand+version+arch so the receiver
            // knows exactly which build they got (replaces the ambiguous "-Latest").
            apkFileName = ApkShareName.fileName(org.iiab.controller.BuildConfig.VERSION_NAME, apkArch(myApkPath));
            apkServer = new ApkServer(shareConfig.apkPort, myApkPath, apkFileName);
            apkServer.start();
            isApkServerRunning = true;

            qrDisplaySection.setVisibility(View.VISIBLE);
            qrCardContainer.setVisibility(View.VISIBLE);
            updateCardOrder(true);
            imgQrCode.setAlpha(1f);
            cardShareApk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.surface_active_info)));

            rgNetworkSelector.setVisibility((wifiIp != null && hotspotIp != null) ? View.VISIBLE : View.GONE);
            showingWifi = (wifiIp != null);
            updateQrDisplayApk();

            btnShareApp.setText(fragment.getString(R.string.sync_btn_stop_app));
            btnShareApp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger))); // Red
            btnStartServer.setVisibility(View.GONE);

        } catch (Exception e) {
            Log.e(TAG, "Error starting APK Server", e);
            Toast.makeText(fragment.getContext(), fragment.getString(R.string.sync_error_daemon_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateQrDisplayApk() {
        String currentIp = showingWifi ? wifiIp : hotspotIp;
        updateShareIpLabel(currentIp);
        String downloadUrl = "http://" + currentIp + ":" + shareConfig.apkPort + "/" + apkFileName;
        Bitmap qrBitmap = SyncHandshakeHelper.generateQrCode(downloadUrl, 500);

        if (qrBitmap != null) imgQrCode.setImageBitmap(qrBitmap);

        String baseText = showingWifi ? fragment.getString(R.string.sync_app_status_wifi) : fragment.getString(R.string.sync_app_status_hotspot);
        txtShareStatus.setText(baseText);
        txtShareStatus.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.text_secondary));
    }

    private void stopApkServer() {
        if (apkServer != null) {
            apkServer.stop();
            apkServer = null;
        }
        isApkServerRunning = false;
        updateCardOrder(false);

        qrDisplaySection.setVisibility(View.GONE);
        btnShareApp.setText(fragment.getString(R.string.sync_btn_share_app));
        cardShareApk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.surface_card)));

        btnShareApp.setText(fragment.getString(R.string.sync_btn_share_app));
        btnShareApp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_info))); // Blue
        btnStartServer.setVisibility(View.VISIBLE);
        txtShareStatus.setText(fragment.getString(R.string.sync_share_status_off));
        txtShareStatus.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_warning)); // Orange
    }

    private void updateCardOrder(boolean isApkActive) {
        // Remove both cards from the parent container, then re-add in priority order.
        containerShare.removeView(cardShareSystem);
        containerShare.removeView(cardShareApk);

        if (isApkActive) {
            containerShare.addView(cardShareApk);
            containerShare.addView(cardShareSystem);
        } else {
            containerShare.addView(cardShareSystem);
            containerShare.addView(cardShareApk);
        }
    }

    /** Extracted from the Start-server click so the pre-flight can run it after "continue". */
    public void startShareFlow() {
        if (fragment.getActivity() == null) return;

        if (isApkServerRunning) {
            new BrandDialog(fragment.requireContext())
                    .setTitle(R.string.sync_dialog_server_running_title)
                    .setMessage(R.string.sync_error_stop_apk_first)
                    .setPositive(R.string.adb_enforcer_btn_ok, BrandDialog.Role.PRIMARY, null)
                    .show();
            return;
        }

        if (host.isServerAlive()) {
            new BrandDialog(fragment.requireContext())
                    .setTitle(R.string.sync_dialog_server_running_title)
                    .setMessage(R.string.sync_error_stop_server_first)
                    .setPositive(R.string.adb_enforcer_btn_ok, BrandDialog.Role.PRIMARY, null)
                    .show();
            return;
        }

        if (!isDaemonRunning) {
            fetchNetworkInterfaces();
            if (wifiIp == null && hotspotIp == null) {
                Toast.makeText(fragment.getContext(), fragment.getString(R.string.sync_error_no_network), Toast.LENGTH_SHORT).show();
                return;
            }

            File rootfsDir = new File(fragment.requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
            hostHasRootfs = rootfsDir.exists() && rootfsDir.isDirectory();

            if (!hostHasRootfs) {
                new BrandDialog(fragment.requireContext())
                        .setTitle(R.string.sync_dialog_missing_env_title)
                        .setMessage(R.string.sync_dialog_missing_env_msg)
                        .setPositive(R.string.sync_dialog_btn_continue, BrandDialog.Role.PRIMARY, () -> startShareDaemon(rootfsDir))
                        .setNegative(R.string.cancel, null)
                        .show();
            } else {
                startShareDaemon(rootfsDir);
            }
        } else {
            new BrandDialog(fragment.requireContext())
                    .setTitle(R.string.sync_dialog_stop_title)
                    .setMessage(R.string.sync_dialog_stop_msg)
                    .setPositive(R.string.sync_btn_stop_server, BrandDialog.Role.DESTRUCTIVE, () -> stopShareDaemon())
                    .setNegative(R.string.cancel, null)
                    .show();
        }
    }
}
