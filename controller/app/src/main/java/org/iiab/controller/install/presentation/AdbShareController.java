/*
 * ============================================================================
 * Name        : AdbShareController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADB/Share networking carved out of DeployFragment (strangler-fig,
 *               ADFA-4441): ADB connection, NSD discovery, pairing, the ADB UI
 *               broadcast receiver and the connection LEDs. Self-contained except
 *               for the CPU-chart updates that arrive over the ADB channel
 *               (ADB_CPU_UPDATE), routed back to the Fragment via AdbShareHost.
 *               Lifecycle hooks (onViewCreated/onResume/onPause) are driven by
 *               the Fragment. No behaviour change.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.iiab.controller.AdbPairingReceiver;
import org.iiab.controller.IIABAdbManager;
import org.iiab.controller.R;

public final class AdbShareController {

    private static final String TAG = "IIAB-AdbShareController";
    private static final String SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp.";
    private static final String CHANNEL_ID = "adb_pairing_channel";

    private final Fragment fragment;
    private final AdbShareHost host;

    // Borrowed views (set in onViewCreated()).
    private View ledAdbStatus;
    private View ledDcpr;
    private View ledPpk;
    private TextView txtDcpr;
    private TextView txtPpk;
    private TextView txtAdbLedLabel;
    private Button btnAdbAction;

    // Owned ADB/NSD state.
    private android.net.nsd.NsdManager nsdManager;
    private android.net.nsd.NsdManager.DiscoveryListener connectDiscoveryListener, pairingDiscoveryListener;
    private boolean isConnectedToAdb = false, isScanning = false, isAttemptingFastConnect = false;
    private int discoveredConnectPort = -1, discoveredPairingPort = -1;
    private String discoveredHostIp = "127.0.0.1";
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    public AdbShareController(Fragment fragment, AdbShareHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Init from DeployFragment.onViewCreated: stores views + wires ADB. */
    public void onViewCreated(View ledAdbStatus, View ledDcpr, View ledPpk,
                              TextView txtDcpr, TextView txtPpk, TextView txtAdbLedLabel, Button btnAdbAction) {
        this.ledAdbStatus = ledAdbStatus;
        this.ledDcpr = ledDcpr;
        this.ledPpk = ledPpk;
        this.txtDcpr = txtDcpr;
        this.txtPpk = txtPpk;
        this.txtAdbLedLabel = txtAdbLedLabel;
        this.btnAdbAction = btnAdbAction;
        nsdManager = (android.net.nsd.NsdManager) fragment.requireContext().getSystemService(Context.NSD_SERVICE);
        setupAdbNetworking();
        setupAdbListeners();
    }

    /** From DeployFragment.onResume. */
    public void onResume() {
        registerAdbReceiver();
    }

    /** From DeployFragment.onPause. */
    public void onPause() {
        try {
            fragment.requireContext().unregisterReceiver(adbUiUpdateReceiver);
        } catch (Exception ignored) {
        }
    }

    private final BroadcastReceiver adbUiUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("org.iiab.controller.ADB_PAIRING_SUCCESSFUL".equals(action)) {
                fragment.requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("pairing_just_succeeded", false).apply();

                android.util.Log.i(TAG, "Broadcast received: Pairing successful! Re-scanning in 2.5s...");
                if (fragment.isAdded()) {
                    btnAdbAction.setText(fragment.getString(R.string.adb_status_securing));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startAdbPairingFlow();
                    }, 2500);
                }
            } else if ("org.iiab.controller.ADB_PAIRING_FAILED".equals(action)) {
                android.util.Log.w(TAG, "Broadcast received: Pairing failed.");
                if (fragment.isAdded()) resetScanState();

            } else if ("org.iiab.controller.ADB_PAIRING_SENT".equals(action)) {
                btnAdbAction.setText(fragment.getString(R.string.adb_status_connected));
                isConnectedToAdb = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (fragment.isAdded()) updateUiState(true);
                }, 500);

            } else if ("org.iiab.controller.ADB_CPU_UPDATE".equals(action)) {
                String cpuData = intent.getStringExtra("cpu_line");
                if (fragment.isAdded() && isConnectedToAdb && cpuData != null) {
                    float cpuVal = host.parseCpuUsage(cpuData);
                    if (cpuVal >= 0f) {
                        host.addCpuEntry(cpuVal);
                    }
                }
            } else if ("org.iiab.controller.ADB_RESTRICTIONS_UPDATE".equals(action)) {
                if (!fragment.isAdded()) return;

                String cpValue = intent.getStringExtra("child_process_value");
                String rawPpkValue = intent.getStringExtra("ppk_value");

                fragment.requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("child_process_value", cpValue)
                        .putString("ppk_value", rawPpkValue)
                        .apply();

                String ppkDisplay = ("null".equals(rawPpkValue) || "unknown".equals(rawPpkValue)) ? fragment.getString(R.string.adb_ppk_default) : rawPpkValue;

                ledDcpr.setBackgroundTintList(null);
                ledPpk.setBackgroundTintList(null);
                ledPpk.setBackgroundResource(R.drawable.led_off);
                ledDcpr.setBackgroundResource(R.drawable.led_off);

                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    txtPpk.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_ppk_limit_not_required, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green);
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_pending)));
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_info)));
                    }

                    if ("0".equals(cpValue) || "false".equals(cpValue)) {
                        ledDcpr.setBackgroundResource(R.drawable.led_on_green);
                        txtDcpr.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_cp_disabled_ok), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else if ("1".equals(cpValue) || "true".equals(cpValue) || "null".equals(cpValue) || cpValue == null) {
                        ledDcpr.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger)));
                        txtDcpr.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_cp_enabled_limiting), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else {
                        txtDcpr.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_cp_unknown), android.text.Html.FROM_HTML_MODE_COMPACT));
                    }

                } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                    txtDcpr.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_cp_not_required), android.text.Html.FROM_HTML_MODE_COMPACT));
                    txtPpk.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_ppk_limit_active, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green);
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger)));
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_pending)));
                    }
                }
            }
        }
    };

    private void setupAdbNetworking() {
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) fragment.requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("iiab_adb_multicast_lock");
            multicastLock.setReferenceCounted(true);
        }
    }

    private void setupAdbListeners() {
        LinearLayout containerDcpr = fragment.getView().findViewById(R.id.container_led_dcpr);
        containerDcpr.setOnClickListener(v -> {
            if (!isConnectedToAdb) {
                Snackbar.make(v, R.string.adb_req_cp, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT < 34) {
                Snackbar.make(v, R.string.adb_not_req_cp, Snackbar.LENGTH_LONG).show();
                return;
            }

            IIABAdbManager adbManager = IIABAdbManager.getInstance(fragment.requireContext());
            adbManager.executeCommand("settings put global settings_enable_monitor_phantom_procs 0");
            Snackbar.make(v, R.string.adb_snack_disabling_cp, Snackbar.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(fragment.requireContext()), 1000);
        });

        LinearLayout containerPpk = fragment.getView().findViewById(R.id.container_led_ppk);
        containerPpk.setOnClickListener(v -> {
            if (!isConnectedToAdb) {
                Snackbar.make(v, R.string.adb_req_ppk, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Snackbar.make(v, R.string.adb_not_req_ppk, Snackbar.LENGTH_LONG).show();
                return;
            }

            IIABAdbManager adbManager = IIABAdbManager.getInstance(fragment.requireContext());
            adbManager.executeCommand("device_config put activity_manager max_phantom_processes 256");
            Snackbar.make(v, R.string.adb_snack_setting_ppk, Snackbar.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(fragment.requireContext()), 1000);
        });

        btnAdbAction.setOnClickListener(v -> {
            if (isConnectedToAdb) {
                new Thread(() -> {
                    try {
                        IIABAdbManager.getInstance(fragment.requireContext()).disconnect();
                    } catch (Exception ignored) {
                    }
                }).start();
                isConnectedToAdb = false;
                updateUiState(false);
            } else if (!isScanning) {
                startAdbPairingFlow();
            }
        });
    }

    private void registerAdbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("org.iiab.controller.ADB_PAIRING_SUCCESSFUL");
        filter.addAction("org.iiab.controller.ADB_PAIRING_FAILED");
        filter.addAction("org.iiab.controller.ADB_PAIRING_SENT");
        filter.addAction("org.iiab.controller.ADB_CPU_UPDATE");
        filter.addAction("org.iiab.controller.ADB_RESTRICTIONS_UPDATE");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            fragment.requireContext().registerReceiver(adbUiUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            fragment.requireContext().registerReceiver(adbUiUpdateReceiver, filter);
        }
    }

    private void updateUiState(boolean isConnected) {
        btnAdbAction.setEnabled(true);
        if (isConnected) {
            ledAdbStatus.setBackgroundResource(R.drawable.led_on_green);
            txtAdbLedLabel.setText(fragment.getString(R.string.adb_status_connected));
            txtAdbLedLabel.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_success));
            btnAdbAction.setText(fragment.getString(R.string.adb_btn_disconnect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger)));

            txtDcpr.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_ui_checking_cp), android.text.Html.FROM_HTML_MODE_COMPACT));
            txtPpk.setText(android.text.Html.fromHtml(fragment.getString(R.string.adb_ui_checking_ppk), android.text.Html.FROM_HTML_MODE_COMPACT));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);
        } else {
            ledAdbStatus.setBackgroundResource(R.drawable.led_off);
            txtAdbLedLabel.setText(fragment.getString(R.string.adb_status_offline));
            txtAdbLedLabel.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_secondary));
            btnAdbAction.setText(fragment.getString(R.string.adb_btn_connect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_info)));

            txtDcpr.setText(fragment.getString(R.string.adb_ui_unknown_cp));
            txtPpk.setText(fragment.getString(R.string.adb_ui_unknown_ppk));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);
        }
    }

    private void startAdbPairingFlow() {
        startAdbPairingFlow(false);
    }

    public void startAdbPairingFlow(boolean isSilentScan) {
        isScanning = true;
        isAttemptingFastConnect = false;
        btnAdbAction.setText(fragment.getString(R.string.adb_btn_scanning));
        btnAdbAction.setEnabled(false);
        discoveredConnectPort = -1;
        discoveredPairingPort = -1;

        if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();

        connectDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_CONNECT);
        pairingDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_PAIRING);

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
        } catch (Exception e) {
            resetScanState();
            return;
        }

        if (!isSilentScan) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isScanning && !isConnectedToAdb) openDeveloperOptions();
            }, 4000);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::checkIfScanTimedOut, 90000);
    }

    private void attemptFastConnection(String hostIp, int port) {
        if (isAttemptingFastConnect) return;
        isAttemptingFastConnect = true;

        Context appContext = fragment.requireContext().getApplicationContext();
        new Thread(() -> {
            boolean connected = false;
            IIABAdbManager adbManager = IIABAdbManager.getInstance(appContext);

            for (int i = 0; i < 6; i++) {
                try {
                    adbManager.connect(hostIp, port);
                    connected = true;
                    break;
                } catch (Exception e) {
                    try {
                        adbManager.disconnect();
                        Thread.sleep(600);
                    } catch (Exception ignored) {
                    }
                }
            }

            if (connected) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopDiscovery();
                    isConnectedToAdb = true;
                    if (btnAdbAction != null)
                        btnAdbAction.setText(fragment.getString(R.string.adb_status_connected));
                    updateUiState(true);
                });
                adbManager.startCpuMonitor(appContext);
                adbManager.checkSystemRestrictions(appContext);
            } else {
                isAttemptingFastConnect = false;
            }
        }).start();
    }

    private void openDeveloperOptions() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            fragment.startActivity(intent);
        } catch (Exception e) {
            Snackbar.make(fragment.getView(), R.string.adb_snack_dev_options, Snackbar.LENGTH_LONG).show();
        }
    }

    private android.net.nsd.NsdManager.DiscoveryListener createDiscoveryListener(String serviceType) {
        return new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
            }

            @Override
            public void onServiceLost(android.net.nsd.NsdServiceInfo service) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onServiceFound(android.net.nsd.NsdServiceInfo service) {
                if (service.getServiceType().contains("_adb-tls")) resolveService(service);
            }
        };
    }

    private void resolveService(android.net.nsd.NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new android.net.nsd.NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(android.net.nsd.NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceResolved(android.net.nsd.NsdServiceInfo serviceInfo) {
                int port = serviceInfo.getPort();
                String type = serviceInfo.getServiceType();
                String hostIp = serviceInfo.getHost().getHostAddress();
                String myIp = getLocalWifiIp();

                if (hostIp != null && !hostIp.equals(myIp) && !hostIp.equals("127.0.0.1")) return;

                new Handler(Looper.getMainLooper()).post(() -> {
                    discoveredHostIp = hostIp;
                    if (type.contains("connect")) {
                        discoveredConnectPort = port;
                        attemptFastConnection(hostIp, port);
                    } else if (type.contains("pairing")) {
                        discoveredPairingPort = port;
                    }

                    if (discoveredConnectPort != -1 && discoveredPairingPort != -1 && !isConnectedToAdb) {
                        stopDiscovery();
                        showPairingNotification(discoveredHostIp, discoveredConnectPort, discoveredPairingPort);
                        resetScanState();
                    }
                });
            }
        });
    }

    private void showPairingNotification(String hostIp, int connectPort, int pairingPort) {
        RemoteInput remoteInput = new RemoteInput.Builder(AdbPairingReceiver.KEY_PIN_REPLY).setLabel(fragment.getString(R.string.adb_notif_input_hint)).build();
        Intent replyIntent = new Intent(fragment.requireContext(), AdbPairingReceiver.class);
        replyIntent.putExtra("hostIp", hostIp);
        replyIntent.putExtra("connectPort", connectPort);
        replyIntent.putExtra("pairingPort", pairingPort);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent replyPendingIntent = PendingIntent.getBroadcast(fragment.requireContext(), 0, replyIntent, flags);
        NotificationCompat.Action action = new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, fragment.getString(R.string.adb_notif_action_pin), replyPendingIntent).addRemoteInput(remoteInput).build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ADB Pairing", NotificationManager.IMPORTANCE_HIGH);
            fragment.requireContext().getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(fragment.requireContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(fragment.getString(R.string.adb_notif_title))
                .setContentText(fragment.getString(R.string.adb_notif_desc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .addAction(action)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) fragment.requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(AdbPairingReceiver.NOTIFICATION_ID, builder.build());
    }

    private void stopDiscovery() {
        try {
            if (connectDiscoveryListener != null) {
                nsdManager.stopServiceDiscovery(connectDiscoveryListener);
                connectDiscoveryListener = null;
            }
            if (pairingDiscoveryListener != null) {
                nsdManager.stopServiceDiscovery(pairingDiscoveryListener);
                pairingDiscoveryListener = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        }
    }

    private void checkIfScanTimedOut() {
        if (isScanning && (discoveredConnectPort == -1 || discoveredPairingPort == -1)) {
            Snackbar.make(fragment.getView(), R.string.adb_toast_scan_timeout, Snackbar.LENGTH_LONG).show();
            stopDiscovery();
            resetScanState();
        }
    }

    private void resetScanState() {
        isScanning = false;
        if (!isConnectedToAdb) {
            btnAdbAction.setEnabled(true);
            btnAdbAction.setText(fragment.getString(R.string.adb_btn_connect));
        }
    }

    private String getLocalWifiIp() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) fragment.requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0)
                return String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        }
        return "127.0.0.1";
    }
}
