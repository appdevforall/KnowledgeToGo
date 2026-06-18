/*
 * ============================================================================
 * Name        : DashboardFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Initial dashboard status activity
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.deviceinfo.data.BuildDeviceAbiProvider;
import org.iiab.controller.deviceinfo.domain.GetDeviceArchUseCase;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private TextView txtDeviceName;
    private TextView txtAndroidVersion;
    private TextView txtHostArch;
    private TextView txtWifiIp, txtHotspotIp, txtUptime, badgeStatus, txtTermuxState;

    // --- GAUGE VARIABLES ---
    private ResourceGaugeView gaugeStorage, gaugeRam, gaugeSwap, gaugeBattery;
    private android.widget.ViewFlipper gaugeFlipper;
    private android.widget.ImageButton btnFlipGauges;
    private android.widget.RelativeLayout gaugesContainer;

    private TextView txtTermuxArch, txtDebianArch;
    private LinearLayout archContainer;
    private String cachedTermuxArch = null;
    private String cachedDebianArch = null;
    private boolean isArchCalculated = false;
    private TextView modulesTitle;
    private View ledTermuxState;
    private LinearLayout modulesContainer;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    // --- MODULE CONFIGURATION TARGETING SCALABILITY ---
    private static class IiabModule {
        String endpoint;
        int nameResId;
        boolean requires64Bit;

        IiabModule(String endpoint, int nameResId, boolean requires64Bit) {
            this.endpoint = endpoint;
            this.nameResId = nameResId;
            this.requires64Bit = requires64Bit;
        }
    }

    // THE MASTER ROSTER: We can add, remove, or restrict modules here.
    private final java.util.List<IiabModule> MASTER_ROSTER = java.util.Arrays.asList(
            new IiabModule("books", R.string.dash_books, false),
            new IiabModule("code", R.string.dash_code, false),
            new IiabModule("kiwix", R.string.dash_kiwix, true),
            new IiabModule("kolibri", R.string.dash_kolibri, false),
            new IiabModule("maps", R.string.dash_maps, false),
            new IiabModule("matomo", R.string.dash_matomo, false),
            new IiabModule("dashboard", R.string.dash_system, false)
    );

    public enum SystemState {
        ONLINE, OFFLINE, DEBIAN_ONLY, INSTALLER, TERMUX_ONLY, NONE
    }

    private SystemState currentSystemState = SystemState.NONE;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Bindings
        txtDeviceName = view.findViewById(R.id.dash_text_device_name);
        txtAndroidVersion = view.findViewById(R.id.dash_text_android_version);
        txtHostArch = view.findViewById(R.id.dash_text_host_arch);

        // --- DIAGNOSTIC BYPASS (AGGRESSIVE ALERT DIALOG) ---
        txtDeviceName.setOnClickListener(v -> {
            File iiabDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
            String message;

            if (!iiabDir.exists()) {
                message = getString(R.string.dash_diag_dir_missing, iiabDir.getAbsolutePath());
            } else {
                String[] contents = iiabDir.list();
                if (contents == null || contents.length == 0) {
                    message = getString(R.string.dash_diag_dir_empty);
                } else {
                    message = getString(R.string.dash_diag_dir_contains, contents.length, java.util.Arrays.toString(contents));
                }
            }

            // Force a blocking UI dialog to show the results
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dash_diag_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.dash_diag_btn_ok, null)
                    .show();
        });
        // -------------------------

        txtWifiIp = view.findViewById(R.id.dash_text_wifi_ip);
        txtHotspotIp = view.findViewById(R.id.dash_text_hotspot_ip);
        txtUptime = view.findViewById(R.id.dash_text_uptime);
        badgeStatus = view.findViewById(R.id.dash_badge_status);

        gaugeStorage = view.findViewById(R.id.gauge_storage);
        gaugeRam = view.findViewById(R.id.gauge_ram);
        gaugeSwap = view.findViewById(R.id.gauge_swap);
        gaugeBattery = view.findViewById(R.id.gauge_battery);
        gaugeFlipper = view.findViewById(R.id.gauge_flipper);
        btnFlipGauges = view.findViewById(R.id.btn_flip_gauges);
        gaugesContainer = view.findViewById(R.id.dash_gauges_container);

        // --- ANIMATION SETUP FOR VIEWFLIPPER ---
        // Sets sliding animations for a smooth page transition
        gaugeFlipper.setInAnimation(requireContext(), android.R.anim.slide_in_left);
        gaugeFlipper.setOutAnimation(requireContext(), android.R.anim.slide_out_right);

        btnFlipGauges.setOnClickListener(v -> {
            gaugeFlipper.showNext();
            // Trigger gauge animation for whichever page just became visible
            triggerVisibleGaugesAnimation();
        });

        // Trigger animation when touching the gauges directly
        View.OnClickListener animateCurrentClick = v -> triggerVisibleGaugesAnimation();
        gaugeFlipper.setOnClickListener(animateCurrentClick);
        gaugeStorage.setOnClickListener(animateCurrentClick);
        gaugeBattery.setOnClickListener(animateCurrentClick);
        gaugeRam.setOnClickListener(animateCurrentClick);
        gaugeSwap.setOnClickListener(animateCurrentClick);

        ledTermuxState = view.findViewById(R.id.led_termux_state);
        txtTermuxState = view.findViewById(R.id.text_termux_state);
        txtTermuxArch = view.findViewById(R.id.dash_text_termux_arch);
        txtDebianArch = view.findViewById(R.id.dash_text_debian_arch);
        archContainer = view.findViewById(R.id.dash_arch_wrapper);
        modulesContainer = view.findViewById(R.id.modules_container);
        modulesTitle = view.findViewById(R.id.dash_modules_title);

        modulesContainer.setVisibility(View.GONE);
        modulesTitle.setText(String.format(getString(R.string.label_separator_up), getString(R.string.dash_installed_modules)));

        // Listener to collapse/expand
        modulesTitle.setOnClickListener(v -> {
            boolean isGone = modulesContainer.getVisibility() == View.GONE;
            modulesContainer.setVisibility(isGone ? View.VISIBLE : View.GONE);
            modulesTitle.setText(String.format(getString(isGone ? R.string.label_separator_down : R.string.label_separator_up), getString(R.string.dash_installed_modules)));
        });

        // Generate module views dynamically
        createModuleViews();

        // Configure refresh timer (every 5 seconds)
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateSystemStats();
                checkServerAndModules();
                refreshHandler.postDelayed(this, 5000);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.post(refreshRunnable);
        // TRIGGER ANIMATION WHEN ENTERING TAB
        new Handler(Looper.getMainLooper()).postDelayed(this::triggerVisibleGaugesAnimation, 100);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    private void updateSystemStats() {
        txtDeviceName.setText(getDeviceName());

        // --- FETCH AND DISPLAY BASE ANDROID VERSION ---
        String androidRelease = android.os.Build.VERSION.RELEASE;
        int sdkVersion = android.os.Build.VERSION.SDK_INT;
        txtAndroidVersion.setText(getString(R.string.dash_android_version_value, "v" + androidRelease, String.valueOf(sdkVersion)));

        // --- FETCH AND DISPLAY HOST (DEVICE) ARCHITECTURE ---
        // This must be the REAL device arch, not the app's ABI: a 32-bit app can
        // run on a 64-bit device (used for testing the 32-bit path), and the
        // device panel must still report 64-bit. App/content arch keeps using
        // getTermuxArch() elsewhere (modules, termux, debian).
        if (txtHostArch != null) {
            String deviceArch = new GetDeviceArchUseCase(new BuildDeviceAbiProvider()).execute();
            txtHostArch.setText(deviceArch);
        }

        // --- CALCULATE SERVER UPTIME ---
        long uptimeMillis = android.os.SystemClock.elapsedRealtime();
        long minutes = (uptimeMillis / (1000 * 60)) % 60;
        long hours = (uptimeMillis / (1000 * 60 * 60)) % 24;
        long days = (uptimeMillis / (1000 * 60 * 60 * 24));

        // Format: "Uptime: 2d 14h 05m" (Omit days if 0)
        String timeStr = (days > 0) ?
                getString(R.string.dash_format_uptime_days, days, hours, minutes) :
                getString(R.string.dash_format_uptime_hours, hours, minutes);

        txtUptime.setText(timeStr);
        txtWifiIp.setText(getWifiIp());
        txtHotspotIp.setText(getHotspotIp());

        // --- GET REAL RAM AND SWAP FROM LINUX ---
        long memTotal = 0, memAvailable = 0, swapTotal = 0, swapFree = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) memTotal = parseMemLine(line);
                else if (line.startsWith("MemAvailable:")) memAvailable = parseMemLine(line);
                // If phone is old and doesn't have "MemAvailable", use "MemFree"
                else if (memAvailable == 0 && line.startsWith("MemFree:")) memAvailable = parseMemLine(line);
                else if (line.startsWith("SwapTotal:")) swapTotal = parseMemLine(line);
                else if (line.startsWith("SwapFree:")) swapFree = parseMemLine(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Convert the values from kB to GB (1 GB = 1048576 kB)
        double memTotalGb = memTotal / 1048576.0;
        double memUsedGb = (memTotal - memAvailable) / 1048576.0;
        int memProgress = memTotal > 0 ? (int) (((memTotal - memAvailable) * 100) / memTotal) : 0;

        double swapTotalGb = swapTotal / 1048576.0;
        double swapUsedGb = (swapTotal - swapFree) / 1048576.0;
        int swapProgress = swapTotal > 0 ? (int) (((swapTotal - swapFree) * 100) / swapTotal) : 0;

        File path = android.os.Environment.getDataDirectory();
        long totalSpace = path.getTotalSpace() / (1024 * 1024 * 1024);
        long freeSpace = path.getFreeSpace() / (1024 * 1024 * 1024);
        long usedSpace = totalSpace - freeSpace;

        // --- UPDATE GAUGE VIEWS (ANIMATED AND COLORED) ---
        int baseColorRam = ContextCompat.getColor(requireContext(), R.color.dash_bar_ram);
        int baseColorSwap = ContextCompat.getColor(requireContext(), R.color.dash_bar_swap);
        int baseColorStorage = ContextCompat.getColor(requireContext(), R.color.dash_bar_storage);

        int warnColor = ContextCompat.getColor(requireContext(), R.color.status_warning); // Orange
        int dangerColor = ContextCompat.getColor(requireContext(), R.color.status_danger); // Red

        // RAM Gauge (Warning at 90%, Danger at 95%)
        int finalColorRam = memProgress >= 95 ? dangerColor : (memProgress >= 90 ? warnColor : baseColorRam);
        String strRam = getString(R.string.dash_format_gb, memUsedGb, memTotalGb);
        if (gaugeRam != null)
            gaugeRam.updateData(memProgress, strRam, getString(R.string.dash_ram_memory), finalColorRam);

        // SWAP Gauge (Warning at 90%, Danger at 95%)
        if (gaugeSwap != null) {
            if (swapTotal > 0) {
                int finalColorSwap = swapProgress >= 95 ? dangerColor : (swapProgress >= 90 ? warnColor : baseColorSwap);
                String strSwap = getString(R.string.dash_format_gb, swapUsedGb, swapTotalGb);
                gaugeSwap.updateData(swapProgress, strSwap, getString(R.string.dash_swap_virtual), finalColorSwap);
            } else {
                gaugeSwap.updateData(0, getString(R.string.dash_format_na), getString(R.string.dash_swap_virtual), baseColorSwap);
            }
        }

        // STORAGE Gauge (Warning at 90%, Danger at 95%)
        if (gaugeStorage != null) {
            int storageProgress = totalSpace > 0 ? (int) ((usedSpace * 100f) / totalSpace) : 0;
            int finalColorStorage = storageProgress >= 95 ? dangerColor : (storageProgress >= 90 ? warnColor : baseColorStorage);
            String strStorage = getString(R.string.dash_format_gb_int, usedSpace, totalSpace);
            gaugeStorage.updateData(storageProgress, strStorage, getString(R.string.dash_main_storage), finalColorStorage);
        }

        // --- BATTERY GAUGE LOGIC ---
        if (gaugeBattery != null) {
            int batLevel = -1;
            boolean isCharging = false;

            try {
                IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = requireContext().registerReceiver(null, iFilter);

                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

                    // Ensure percentage is always between 0 and 100
                    if (level != -1 && scale != -1) {
                        batLevel = (int) ((level / (float) scale) * 100f);
                    }

                    // Strict check to see if it's connected to power (AC, USB, or Wireless)
                    int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    isCharging = (chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
                            chargePlug == BatteryManager.BATTERY_PLUGGED_AC ||
                            chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS);
                }
            } catch (Exception e) {
                android.util.Log.e("IIAB-Dashboard", "Error reading battery stats", e);
            }

            // Apply exact requested battery colors
            int colorBattery;
            if (batLevel <= 33) {
                colorBattery = warnColor; // Orange (1-33%)
            } else if (batLevel <= 66) {
                colorBattery = ContextCompat.getColor(requireContext(), R.color.status_success); // Green (34-66%)
            } else {
                colorBattery = ContextCompat.getColor(requireContext(), R.color.status_info); // Blue (67-100%)
            }

            // Update the gauge with the newly assigned 4-parameter method
            if (batLevel >= 0) {
                // Only add the lightning bolt if isCharging is true
                String batStr = isCharging ? getString(R.string.dash_format_pct_charging, batLevel) : getString(R.string.dash_format_pct, batLevel);
                gaugeBattery.updateData(batLevel, batStr, getString(R.string.dash_battery_title), colorBattery);
            } else {
                // Default fallback if we can't read the battery
                colorBattery = ContextCompat.getColor(requireContext(), R.color.dash_text_secondary);
                gaugeBattery.updateData(0, getString(R.string.dash_format_pct_na), getString(R.string.dash_battery_title), colorBattery);
            }
        }
    }

    // Triggers the fill animation only for the gauges currently displayed on screen
    private void triggerVisibleGaugesAnimation() {
        if (gaugeFlipper.getDisplayedChild() == 0) {
            if (gaugeStorage != null) gaugeStorage.triggerAnimation();
            if (gaugeBattery != null) gaugeBattery.triggerAnimation();
        } else {
            if (gaugeRam != null) gaugeRam.triggerAnimation();
            if (gaugeSwap != null) gaugeSwap.triggerAnimation();
        }
    }

    private void createModuleViews() {
        modulesContainer.removeAllViews();

        // Determine if the installed Termux is 64-bit
        String arch = getTermuxArch();
        boolean is64Bit = arch != null && arch.contains("64");

        // Filter the Master Roster based on architecture support
        java.util.List<IiabModule> activeModules = new java.util.ArrayList<>();
        for (IiabModule module : MASTER_ROSTER) {
            if (module.requires64Bit && !is64Bit) {
                continue;
            }
            activeModules.add(module);
        }

        // Build the UI grid dynamically using the filtered list
        int numCols = 3;
        int numRows = (int) Math.ceil((double) activeModules.size() / numCols);

        for (int row = 0; row < numRows; row++) {
            LinearLayout rowLayout = new LinearLayout(requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rowLayout.setBaselineAligned(false);
            rowLayout.setWeightSum(numCols);
            rowLayout.setPadding(0, 0, 0, 16);

            for (int col = 0; col < numCols; col++) {
                int index = (row * numCols) + col;

                LinearLayout cell = new LinearLayout(requireContext());
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                // Margins to prevent them from sticking together
                int margin = 8;
                if (col == 0) cellParams.setMargins(0, 0, margin, 0); // Left
                else if (col == 1) cellParams.setMargins(margin / 2, 0, margin / 2, 0); // Center
                else cellParams.setMargins(margin, 0, 0, 0); // Right

                cell.setLayoutParams(cellParams);

                if (index < activeModules.size()) {
                    IiabModule currentMod = activeModules.get(index);

                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.rounded_button);
                    cell.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_module_bg)));
                    cell.setPadding(16, 24, 16, 24);
                    cell.setGravity(android.view.Gravity.CENTER);

                    View led = new View(requireContext());
                    led.setLayoutParams(new LinearLayout.LayoutParams(20, 20));
                    led.setBackgroundResource(R.drawable.led_off);
                    led.setId(View.generateViewId());

                    TextView name = new TextView(requireContext());
                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(12, 0, 0, 0);
                    name.setLayoutParams(textParams);

                    // Inject the data from the Master Roster
                    name.setText(getString(currentMod.nameResId));
                    name.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_module_text));
                    name.setTextSize(11f);
                    name.setSingleLine(true);

                    cell.addView(led);
                    cell.addView(name);

                    // The background ping thread relies on this tag to check the URL!
                    cell.setTag(currentMod.endpoint);
                } else {
                    cell.setVisibility(View.INVISIBLE);
                }
                rowLayout.addView(cell);
            }
            modulesContainer.addView(rowLayout);
        }
    }

    private void checkServerAndModules() {
        new Thread(() -> {
            // 1. Ping the network once
            boolean isMainServerAlive = pingUrl("http://localhost:8085/home");

            if (!isAdded() || getActivity() == null) return;

            // 2. Ask the State Machine for the definitive truth
            currentSystemState = evaluateSystemState(isMainServerAlive);

            // 3. Push the state to MainActivity
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).currentSystemState = currentSystemState;
                getActivity().runOnUiThread(() -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).updateUIColorsAndVisibility();
                    }
                });
            }

            // --- CHECKPOINT 2 ---
            if (!isAdded() || getActivity() == null) return;

            // 4. Update the UI on the main thread
            getActivity().runOnUiThread(() -> {
                if (archContainer != null) {
                    if (isArchCalculated && currentSystemState != SystemState.NONE) {
                        archContainer.setVisibility(View.VISIBLE);
                        txtTermuxArch.setText(cachedTermuxArch);
                        txtDebianArch.setText(cachedDebianArch);
                    } else {
                        archContainer.setVisibility(View.GONE);
                    }
                }

                // Configure the Top Traffic Light (Server Status)
                if (currentSystemState == SystemState.ONLINE) {
                    badgeStatus.setText(R.string.dash_online);
                    badgeStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_status_online)));
                } else {
                    badgeStatus.setText(R.string.dash_offline);
                    badgeStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_secondary)));
                }

                // Configure the Bottom LED and Suggestion Message
                switch (currentSystemState) {
                    case ONLINE:
                        ledTermuxState.setBackgroundResource(R.drawable.led_on_green);
                        txtTermuxState.setText(getString(R.string.dash_state_online));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        break;
                    case OFFLINE:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_offline));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
                        break;
                    case DEBIAN_ONLY:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_debian_only));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        break;
                    case INSTALLER:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_installer));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        break;
                    case TERMUX_ONLY:  // Fallthrough intended; no longer used
                    case NONE:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_none));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_warning));
                        break;
                }
            });

            // 5. Scan individual modules (Only if the system is ONLINE)
            for (int r = 0; r < modulesContainer.getChildCount(); r++) {
                LinearLayout row = (LinearLayout) modulesContainer.getChildAt(r);

                for (int c = 0; c < row.getChildCount(); c++) {
                    LinearLayout card = (LinearLayout) row.getChildAt(c);
                    String endpoint = (String) card.getTag();
                    if (endpoint == null) continue;

                    View led = card.getChildAt(0);

                    // Module ON = (System is ONLINE) AND (URL responds)
                    boolean isModuleAlive = (currentSystemState == SystemState.ONLINE) && pingUrl("http://localhost:8085/" + endpoint);

                    if (!isAdded() || getActivity() == null) return;

                    getActivity().runOnUiThread(() -> {
                        led.setBackgroundResource(isModuleAlive ? R.drawable.led_on_green : R.drawable.led_off);
                    });
                }
            }
        }).start();
    }

    private boolean pingUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            return (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400);
        } catch (Exception e) {
            return false;
        }
    }

    // Extracts the numbers (in kB) from the lines of /proc/meminfo
    private long parseMemLine(String line) {
        return SystemStatsUtil.parseMemLine(line);
    }

    // --- METHODS FOR OBTAINING IPs ---
    private String getWifiIp() {
        return getIpByInterface("wlan0");
    }

    private String getHotspotIp() {
        String[] hotspotInterfaces = {"ap0", "wlan1", "swlan0"};
        for (String iface : hotspotInterfaces) {
            String ip = getIpByInterface(iface);
            if (!ip.equals("--")) return ip;
        }
        return "--";
    }

    private String getIpByInterface(String interfaceName) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().equalsIgnoreCase(interfaceName)) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "--";
    }

    // --- METHODS FOR OBTAINING THE DEVICE NAME ---
    private String getDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;

        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.length() == 0) return "";
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

    // --- MASTER STATE EVALUATOR (NATIVE WITH LEGACY MENTAL MAP) ---
    private SystemState evaluateSystemState(boolean isNginxAlive) {

        // 0. Calculate native architecture only once
        if (!isArchCalculated) {
            cachedTermuxArch = getTermuxArch();
            cachedDebianArch = getDebianArch(cachedTermuxArch);
            isArchCalculated = true;
        }

        // Setup paths for native direct inspection
        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        File debianBash = new File(rootfsDir, "bin/bash");
        File flagIiabReady = new File(rootfsDir, "usr/local/pdsm/flag_install_ready");

        // --- 1. Does Termux physically exist on the Android device? ---
        /*
         * [OBSOLETE IN NATIVE ARCHITECTURE]
         * Previously used PackageManager to check "com.termux" and verify firstInstallTime.
         * Handled "The Purge" (Ghost Handling) if signatures mismatched.
         * No longer needed because PRoot and Aria2 are compiled directly into this app.
         */

        // --- 2. Does the Nginx server respond? ---
        if (isNginxAlive) {
            return SystemState.ONLINE;
        }

        // --- 3. Is IIAB fully compiled/restored and ready? ---
        /*
         * [NATIVE ADAPTATION]
         * Previously looked for "flag_iiab_ready" in /sdcard/.iiab_state.
         */
        if (flagIiabReady.exists()) {
            return SystemState.OFFLINE;
        }

        // --- 4. Is the base Debian OS installed, but NO IIAB yet? ---
        /*
         * [NATIVE ADAPTATION]
         * Previously looked for "flag_system_installed" in /sdcard/.iiab_state.
         * Now directly checks if TarExtractor successfully unpacked the base Linux filesystem.
         */
        if (debianBash.exists()) {
            return SystemState.DEBIAN_ONLY;
        }

        // --- 5. Is only the installer ready? ---
        /*
         * [OBSOLETE IN NATIVE ARCHITECTURE]
         * Previously looked for "flag_installer_present" to know if the Bash script was running.
         * The installer is now our native Java UI (Aria2Manager + TarExtractor).
         */

        // --- 6. Only the raw base app is present ---
        /*
         * Previously returned SystemState.TERMUX_ONLY.
         * Now it means the system is completely virgin (no rootfs, no variables).
         */
        return SystemState.NONE;
    }

    // --- METHODS FOR OBTAINING ARCHITECTURES ---
    private String getTermuxArch() {
        try {
            // Inspecting our own app's NDK instead of an external package
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
            android.util.Log.e("IIAB-Dashboard", "Error obtaining native architecture", e);
        }

        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            return android.os.Build.SUPPORTED_ABIS[0];
        }
        return "unknown";
    }

    private String getDebianArch(String androidArch) {
        return SystemStatsUtil.getDebianArch(androidArch);
    }

    // Converter from DP to actual screen pixels
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Dynamically adjust the width when the user rotates the screen
        adaptLayoutToOrientation(newConfig.orientation);
    }

    /**
     * Applies responsive web design principles to the gauges container.
     * Prevents extreme stretching in landscape mode by limiting width to 75%.
     */
    private void adaptLayoutToOrientation(int orientation) {
        if (gaugesContainer == null) return;

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) gaugesContainer.getLayoutParams();

        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: Limit width to 75% of the screen for better UX
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            params.width = (int) (screenWidth * 0.75f);
        } else {
            // Portrait: Use full available width
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }

        gaugesContainer.setLayoutParams(params);
    }
}