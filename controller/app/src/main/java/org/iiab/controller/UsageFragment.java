/*
 * ============================================================================
 * Name        : UsageFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Usage Fragment Activity
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.network.presentation.DnsSettingsUiState;
import org.iiab.controller.network.presentation.DnsSettingsViewModel;
import org.iiab.controller.network.presentation.DnsSettingsViewModelFactory;

import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UsageFragment extends Fragment implements View.OnClickListener {

    private MainActivity mainActivity;
    // INTERFACE VARS
    private EditText edittext_socks_addr, edittext_socks_udp_addr, edittext_socks_port, edittext_socks_user, edittext_socks_pass;
    private CheckBox checkbox_udp_in_tcp, checkbox_remote_dns, checkbox_global, checkbox_maintenance, checkbox_ipv4, checkbox_ipv6;
    private TextView textview_maintenance_warning, configLabel, advConfigLabel, logLabel, logWarning, logSizeText, connectionLog;
    private Button button_apps, button_save, button_control, button_browse_content, btnClearLog, btnCopyLog;
    private LinearLayout logActions, configLayout, advancedConfig, deckContainer;
    private ProgressBar logProgress;
    private ProgressButton btnServerControl;

    private DashboardManager dashboardManager;

    // Setup DNS (network slice, PR B)
    private CheckBox setup_dns_check;
    private LinearLayout dns_setup_fields;
    private EditText dns_primary, dns_secondary;
    private Button dns_accept;
    private TextView dns_result;
    private TextView dns_settings_label;
    private LinearLayout dns_settings_section;
    private DnsSettingsViewModel dnsViewModel;
    private boolean suppressDnsToggle = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) {
            mainActivity = (MainActivity) context;
            mainActivity.setUsageFragment(this);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_usage, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI Bindings
        edittext_socks_addr = view.findViewById(R.id.socks_addr);
        edittext_socks_udp_addr = view.findViewById(R.id.socks_udp_addr);
        edittext_socks_port = view.findViewById(R.id.socks_port);
        edittext_socks_user = view.findViewById(R.id.socks_user);
        edittext_socks_pass = view.findViewById(R.id.socks_pass);
        setup_dns_check = view.findViewById(R.id.setup_dns_check);
        dns_setup_fields = view.findViewById(R.id.dns_setup_fields);
        dns_primary = view.findViewById(R.id.dns_primary);
        dns_secondary = view.findViewById(R.id.dns_secondary);
        dns_accept = view.findViewById(R.id.dns_accept);
        dns_result = view.findViewById(R.id.dns_result);
        dns_settings_label = view.findViewById(R.id.dns_settings_label);
        dns_settings_section = view.findViewById(R.id.dns_settings_section);
        dns_settings_label.setText(String.format(getString(R.string.label_separator_up), getString(R.string.network_advanced_label)));
        dns_settings_label.setOnClickListener(v -> toggleVisibility(dns_settings_section, dns_settings_label, getString(R.string.network_advanced_label)));
        dnsViewModel = new ViewModelProvider(this, new DnsSettingsViewModelFactory(requireContext()))
                .get(DnsSettingsViewModel.class);
        dnsViewModel.state().observe(getViewLifecycleOwner(), this::renderDnsState);
        setup_dns_check.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressDnsToggle) return;
            dnsViewModel.onSetupToggled(checked);
        });
        dns_accept.setOnClickListener(v -> dnsViewModel.onAccept(
                dns_primary.getText().toString(), dns_secondary.getText().toString()));
        checkbox_ipv4 = view.findViewById(R.id.ipv4);
        checkbox_ipv6 = view.findViewById(R.id.ipv6);
        checkbox_global = view.findViewById(R.id.global);
        checkbox_udp_in_tcp = view.findViewById(R.id.udp_in_tcp);
        checkbox_remote_dns = view.findViewById(R.id.remote_dns);
        checkbox_maintenance = view.findViewById(R.id.checkbox_maintenance);
        textview_maintenance_warning = view.findViewById(R.id.maintenance_warning);
        button_apps = view.findViewById(R.id.apps);
        button_save = view.findViewById(R.id.save);
        button_control = view.findViewById(R.id.control);
        button_browse_content = view.findViewById(R.id.btnBrowseContent);

        logActions = view.findViewById(R.id.log_actions);
        btnClearLog = view.findViewById(R.id.btn_clear_log);
        btnCopyLog = view.findViewById(R.id.btn_copy_log);
        connectionLog = view.findViewById(R.id.connection_log);
        logProgress = view.findViewById(R.id.log_progress);
        logWarning = view.findViewById(R.id.log_warning_text);
        logSizeText = view.findViewById(R.id.log_size_text);
        configLayout = view.findViewById(R.id.config_layout);
        configLabel = view.findViewById(R.id.config_label);
        advancedConfig = view.findViewById(R.id.advanced_config);
        advConfigLabel = view.findViewById(R.id.adv_config_label);
        logLabel = view.findViewById(R.id.log_label);

        deckContainer = view.findViewById(R.id.deck_container);
        btnServerControl = view.findViewById(R.id.btn_server_control);

        dashboardManager = new DashboardManager(requireActivity(), view, () -> {
            mainActivity.handleControlClick();
        });

        // Listeners
        button_control.setOnClickListener(v -> mainActivity.handleControlClick());
        button_browse_content.setOnClickListener(v -> mainActivity.handleBrowseContentClick(v));
        btnClearLog.setOnClickListener(this);
        btnCopyLog.setOnClickListener(this);
        configLabel.setOnClickListener(v -> handleConfigToggle());
        advConfigLabel.setOnClickListener(v -> toggleVisibility(advancedConfig, advConfigLabel, getString(R.string.advanced_settings_label)));
        logLabel.setOnClickListener(v -> handleLogToggle());
        checkbox_udp_in_tcp.setOnClickListener(this);
        checkbox_remote_dns.setOnClickListener(this);
        checkbox_global.setOnClickListener(this);
        checkbox_maintenance.setOnClickListener(this);
        button_apps.setOnClickListener(this);
        button_save.setOnClickListener(this);

        btnServerControl.setOnClickListener(v -> {
            // --- Intercept based on State Machine ---
            DashboardFragment.SystemState state = mainActivity.currentSystemState;
            boolean isFullyInstalled = (state == DashboardFragment.SystemState.ONLINE || state == DashboardFragment.SystemState.OFFLINE);

            if (!isFullyInstalled) {
                Snackbar.make(v, R.string.server_not_installed_warning, 6000).show();
                return; // Stop execution here
            }
            // --------------------------------------------------

            if (mainActivity.targetServerState != null) return;

            mainActivity.serverTransitionText = !mainActivity.isServerAlive ? getString(R.string.server_booting) : getString(R.string.server_shutting_down);
            mainActivity.targetServerState = !mainActivity.isServerAlive;

            updateUIColorsAndVisibility();
            btnServerControl.startProgress();

            mainActivity.handleServerLaunchClick(v);
        });

        connectionLog.setMovementMethod(new ScrollingMovementMethod());
        connectionLog.setTextIsSelectable(true);
        connectionLog.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        configLabel.setText(String.format(getString(R.string.label_separator_up), getString(R.string.advanced_settings_label)));
        advConfigLabel.setText(String.format(getString(R.string.label_separator_up), getString(R.string.advanced_settings_label)));
        logLabel.setText(String.format(getString(R.string.label_separator_up), getString(R.string.connection_log_label)));

        updateUI();
    }

    @Override
    public void onClick(View v) {
        if (v == checkbox_global || v == checkbox_remote_dns || v == checkbox_maintenance) {
            mainActivity.savePrefs();
            updateUI();
        } else if (v == button_apps) {
            startActivity(new Intent(requireContext(), AppListActivity.class));
        } else if (v.getId() == R.id.save) {
            mainActivity.savePrefs();
            Toast.makeText(requireContext(), R.string.saved_toast, Toast.LENGTH_SHORT).show();
            addToLog(getString(R.string.settings_saved));
        } else if (v.getId() == R.id.btn_clear_log) {
            showResetLogConfirmation();
        } else if (v.getId() == R.id.btn_copy_log) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IIAB Log", connectionLog.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), R.string.log_copied_toast, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void updateUI() {
        if (button_control == null) return;

        boolean vpnActive = mainActivity.prefs.getEnable();

        if (dashboardManager != null)
            dashboardManager.setTunnelState(vpnActive, mainActivity.isProxyDegraded);

        if (vpnActive) {
            button_control.setText(R.string.control_disable);
            button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_on));
        } else {
            button_control.setText(R.string.control_enable);
            button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_off));
        }

        edittext_socks_addr.setText(mainActivity.prefs.getSocksAddress());
        edittext_socks_udp_addr.setText(mainActivity.prefs.getSocksUdpAddress());
        edittext_socks_port.setText(String.valueOf(mainActivity.prefs.getSocksPort()));
        edittext_socks_user.setText(mainActivity.prefs.getSocksUsername());
        edittext_socks_pass.setText(mainActivity.prefs.getSocksPassword());
        checkbox_ipv4.setChecked(mainActivity.prefs.getIpv4());
        checkbox_ipv6.setChecked(mainActivity.prefs.getIpv6());
        checkbox_global.setChecked(mainActivity.prefs.getGlobal());
        checkbox_udp_in_tcp.setChecked(mainActivity.prefs.getUdpInTcp());
        checkbox_remote_dns.setChecked(mainActivity.prefs.getRemoteDns());
        checkbox_maintenance.setChecked(mainActivity.prefs.getMaintenanceMode());
        boolean editable = !vpnActive;
        edittext_socks_addr.setEnabled(editable);
        edittext_socks_port.setEnabled(editable);
        button_save.setEnabled(editable);

        checkbox_maintenance.setEnabled(editable);
        if (textview_maintenance_warning != null) {
            textview_maintenance_warning.setVisibility(vpnActive ? View.VISIBLE : View.GONE);
        }
    }

    public void updateUIColorsAndVisibility() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        if (button_control == null) return;

        boolean isVpnActive = mainActivity.prefs.getEnable();

        if (dashboardManager != null) {
            dashboardManager.setTunnelState(isVpnActive, mainActivity.isProxyDegraded);
        }

        // Main VPN Button
        if (!mainActivity.isServerAlive) {
            if (isVpnActive) {
                button_control.setText(R.string.control_disable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_on_dim));
            } else {
                button_control.setText(R.string.control_enable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_off_dim));
            }
        } else {
            button_control.setEnabled(true);
            if (isVpnActive) {
                button_control.setText(R.string.control_disable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_on));
            } else {
                button_control.setText(R.string.control_enable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_off));
            }
        }

        // Explore Button
        button_browse_content.setVisibility(View.VISIBLE);
        if (!mainActivity.isServerAlive) {
            button_browse_content.setEnabled(true);
            button_browse_content.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_disabled));
            button_browse_content.setAlpha(1.0f);
            button_browse_content.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_accent));
        } else if (mainActivity.isNegotiating) {
            button_browse_content.setEnabled(true);
            button_browse_content.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_accent));
        } else {
            button_browse_content.setEnabled(true);
            button_browse_content.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_accent));
            button_browse_content.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_ready));
            button_browse_content.setAlpha(1.0f);
        }

        // Server Control Logic
        DashboardFragment.SystemState state = mainActivity.currentSystemState;
        boolean isFullyInstalled = (state == DashboardFragment.SystemState.ONLINE || state == DashboardFragment.SystemState.OFFLINE);

        if (!isFullyInstalled) {
            // SYSTEM NOT READY: Gray out the button
            btnServerControl.setAlpha(0.6f);
            btnServerControl.setText(R.string.launch_server);
            btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_disabled));
        } else if (mainActivity.targetServerState != null) {
            // TRANSITIONING STATE
            btnServerControl.setAlpha(0.6f);
            btnServerControl.setText(mainActivity.serverTransitionText);
            btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_disabled));
        } else {
            // SYSTEM READY: Normal behavior
            btnServerControl.setAlpha(1.0f);
            if (mainActivity.isServerAlive) {
                btnServerControl.setText(R.string.stop_server);
                btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_danger));
            } else {
                btnServerControl.setText(R.string.launch_server);
                btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_success));
            }
        }
    }

    public void stopBtnProgress() {
        btnServerControl.stopProgress();
    }

    // =========================================================================
    // Empty methods kept to prevent crashes from MainActivity's legacy broadcast receivers
    // =========================================================================
    public void startFusionPulse() {
    }

    public void startExitPulse() {
    }

    public void finalizeEntryPulse() {
    }

    public void finalizeExitPulse() {
    }

    public void addToLog(String message) {
        // 1. We add the security padlock to avoid crashes
        if (!isAdded() || getActivity() == null) return;

        // 2. We use getActivity() instead of requireActivity() for security
        getActivity().runOnUiThread(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTime = sdf.format(new Date());
            String logEntry = "[" + currentTime + "] " + message + "\n";
            if (connectionLog != null) {
                connectionLog.append(logEntry);
                scrollToBottom();
            }
        });
    }

    private void scrollToBottom() {
        if (connectionLog != null && connectionLog.getLayout() != null) {
            int scroll = connectionLog.getLayout().getLineTop(connectionLog.getLineCount()) - connectionLog.getHeight();
            if (scroll > 0) connectionLog.scrollTo(0, scroll);
        }
    }

    public void updateLogSizeUI() {
        // 3. We added the lock so that it does not call the Context if it is in another tab
        if (!isAdded() || getContext() == null || logSizeText == null) return;

        // 4. We use getContext() instead of requireContext()
        String sizeStr = LogManager.getFormattedSize(getContext());
        logSizeText.setText(getString(R.string.log_size_format, sizeStr));
    }

    public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) {
        if (dashboardManager != null) {
            dashboardManager.updateConnectivityLeds(wifiOn, hotspotOn);
        }
    }

    public boolean isLogVisible() {
        return connectionLog != null && connectionLog.getVisibility() == View.VISIBLE;
    }

    private void handleLogToggle() {
        boolean isOpening = connectionLog.getVisibility() == View.GONE;
        if (isOpening) {
            if (mainActivity.isReadingLogs) return;
            mainActivity.isReadingLogs = true;
            if (logProgress != null) logProgress.setVisibility(View.VISIBLE);

            LogManager.readLogsAsync(requireContext(), (logContent, isRapidGrowth) -> {
                if (connectionLog != null) {
                    connectionLog.setText(logContent);
                    scrollToBottom();
                }
                if (logProgress != null) logProgress.setVisibility(View.GONE);
                if (logWarning != null)
                    logWarning.setVisibility(isRapidGrowth ? View.VISIBLE : View.GONE);
                updateLogSizeUI();
                mainActivity.isReadingLogs = false;
            });
            mainActivity.startLogSizeUpdates();
        } else {
            mainActivity.stopLogSizeUpdates();
        }
        toggleVisibility(connectionLog, logLabel, getString(R.string.connection_log_label));
        logActions.setVisibility(connectionLog.getVisibility());
        if (logSizeText != null) logSizeText.setVisibility(connectionLog.getVisibility());
    }

    private void handleConfigToggle() {
        if (configLayout.getVisibility() == View.GONE) {
            if (BiometricHelper.isDeviceSecure(requireContext())) {
                BiometricHelper.prompt((androidx.appcompat.app.AppCompatActivity) requireActivity(),
                        getString(R.string.auth_required_title),
                        getString(R.string.auth_required_subtitle),
                        () -> toggleVisibility(configLayout, configLabel, getString(R.string.advanced_settings_label)));
            } else {
                BiometricHelper.showEnrollmentDialog(requireContext());
            }
        } else {
            toggleVisibility(configLayout, configLabel, getString(R.string.advanced_settings_label));
        }
    }

    private void toggleVisibility(View view, TextView label, String text) {
        boolean isGone = view.getVisibility() == View.GONE;
        view.setVisibility(isGone ? View.VISIBLE : View.GONE);
        label.setText(String.format(getString(isGone ? R.string.label_separator_down : R.string.label_separator_up), text));
    }

    private void showResetLogConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.log_reset_confirm_title)
                .setMessage(R.string.log_reset_confirm_msg)
                .setPositiveButton(R.string.reset_log, (dialog, which) -> {
                    LogManager.clearLogs(requireContext(), new LogManager.LogClearCallback() {
                        @Override
                        public void onSuccess() {
                            connectionLog.setText("");
                            addToLog(getString(R.string.log_reset_user));
                            if (logWarning != null) logWarning.setVisibility(View.GONE);
                            updateLogSizeUI();
                            Toast.makeText(requireContext(), R.string.log_cleared_toast, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(requireContext(), getString(R.string.failed_reset_log, message), Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    public void savePrefsFromUI() {
        mainActivity.prefs.setSocksAddress("127.0.0.1");
        mainActivity.prefs.setSocksPort(1080);
        mainActivity.prefs.setSocksUdpAddress("");
        mainActivity.prefs.setSocksUsername("");
        mainActivity.prefs.setSocksPassword("");
        mainActivity.prefs.setIpv4(true);
        mainActivity.prefs.setIpv6(true);
        mainActivity.prefs.setUdpInTcp(false);
        mainActivity.prefs.setRemoteDns(true);
        mainActivity.prefs.setGlobal(true);

        mainActivity.prefs.setMaintenanceMode(checkbox_maintenance.isChecked());
    }

    private void renderDnsState(DnsSettingsUiState st) {
        if (setup_dns_check == null) return;
        suppressDnsToggle = true;
        setup_dns_check.setChecked(st.customEnabled);
        suppressDnsToggle = false;
        dns_setup_fields.setVisibility(st.customEnabled ? View.VISIBLE : View.GONE);
        if (st.status == DnsSettingsUiState.Status.IDLE || st.status == DnsSettingsUiState.Status.UNREACHABLE) {
            dns_primary.setText(st.primary);
            dns_secondary.setText(st.secondary);
        }
        switch (st.status) {
            case TESTING:
                dns_result.setVisibility(View.VISIBLE);
                dns_result.setText(getString(R.string.dns_status_testing));
                dns_result.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
                break;
            case APPLIED:
                dns_result.setVisibility(View.VISIBLE);
                dns_result.setText(getString(R.string.dns_status_ok));
                dns_result.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_success));
                break;
            case INVALID:
            case UNREACHABLE:
                dns_result.setVisibility(View.VISIBLE);
                dns_result.setText(st.message != null ? st.message : "");
                dns_result.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warning));
                break;
            default:
                dns_result.setVisibility(View.GONE);
                break;
        }
    }

    public void highlightServerButton() {
        if (deckContainer == null || !isAdded()) return;

        requireActivity().runOnUiThread(() -> {
            // We save the original padding (the 3dp)
            int pL = deckContainer.getPaddingLeft();
            int pT = deckContainer.getPaddingTop();
            int pR = deckContainer.getPaddingRight();
            int pB = deckContainer.getPaddingBottom();

            // We use ofArgb for a perfect color transition
            android.animation.ValueAnimator colorAnim = android.animation.ValueAnimator.ofArgb(
                    Color.TRANSPARENT,
                    ContextCompat.getColor(requireContext(), R.color.status_info) // Color Cyan
            );
            colorAnim.setDuration(350);
            colorAnim.setRepeatCount(5);
            colorAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);

            float cornerRadius = getResources().getDisplayMetrics().density * 10; // ~10dp

            colorAnim.addUpdateListener(animator -> {
                int color = (int) animator.getAnimatedValue();
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(color);
                gd.setCornerRadius(cornerRadius);
                deckContainer.setBackground(gd);
                deckContainer.setPadding(pL, pT, pR, pB);
            });

            colorAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    deckContainer.setBackgroundColor(Color.TRANSPARENT);
                    deckContainer.setPadding(pL, pT, pR, pB);
                }
            });

            colorAnim.start();
        });
    }
}