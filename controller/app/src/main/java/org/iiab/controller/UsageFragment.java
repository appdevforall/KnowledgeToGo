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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.iiab.controller.hotspot.HotspotAvailability;
import org.iiab.controller.hotspot.LocalHotspotManager;

import org.iiab.controller.network.presentation.DnsSettingsUiState;
import org.iiab.controller.network.presentation.DnsSettingsViewModel;
import org.iiab.controller.network.presentation.DnsSettingsViewModelFactory;

import com.google.android.material.snackbar.Snackbar;
import org.iiab.controller.util.Snackbars;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UsageFragment extends Fragment implements View.OnClickListener {

    private MainActivity mainActivity;
    // INTERFACE VARS
    private TextView logLabel, logWarning, logSizeText;
    private ServerLogView connectionLog;
    private Button button_browse_content, btnClearLog, btnCopyLog;
    private LinearLayout logActions, deckContainer;
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

    // ADFA-4520: LocalOnlyHotspot (LOHS) fallback, lives inside the Advanced settings section.
    private LinearLayout lohs_block;
    private CheckBox lohs_toggle;
    private TextView lohs_status, lohs_hint;
    private Button lohs_show_qr;
    private boolean suppressLohsToggle = false;
    private String lohsSsid = null, lohsPass = null;
    private final ActivityResultLauncher<String> lohsLocationPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startLohs();
                } else {
                    setLohsToggleChecked(false);
                    if (getContext() != null) Toast.makeText(getContext(), R.string.lohs_need_location, Toast.LENGTH_LONG).show();
                }
            });

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
        org.iiab.controller.help.TooltipWiring.wireAll(view);

        // UI Bindings
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

        // ADFA-4520: LocalOnlyHotspot section (inside Advanced settings), gated to API 26+.
        lohs_block = view.findViewById(R.id.lohs_block);
        lohs_toggle = view.findViewById(R.id.lohs_toggle);
        lohs_status = view.findViewById(R.id.lohs_status);
        lohs_hint = view.findViewById(R.id.lohs_hint);
        lohs_show_qr = view.findViewById(R.id.lohs_show_qr);
        if (!LocalHotspotManager.isSupported()) {
            lohs_block.setVisibility(View.GONE);
        } else {
            lohs_toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressLohsToggle) return;
                if (isChecked) requestLohsStart(); else LocalHotspotManager.get().stop();
            });
            lohs_show_qr.setOnClickListener(v -> {
                if (lohsSsid != null) {
                    Intent qr = new Intent(getContext(), QrActivity.class);
                    qr.putExtra(QrActivity.EXTRA_WIFI_SSID, lohsSsid);
                    qr.putExtra(QrActivity.EXTRA_WIFI_PASS, lohsPass);
                    startActivity(qr);
                }
            });
            LocalHotspotManager.get().state().observe(getViewLifecycleOwner(), this::renderLohs);
        }
        button_browse_content = view.findViewById(R.id.btnBrowseContent);

        logActions = view.findViewById(R.id.log_actions);
        btnClearLog = view.findViewById(R.id.btn_clear_log);
        btnCopyLog = view.findViewById(R.id.btn_copy_log);
        connectionLog = view.findViewById(R.id.connection_log);
        logProgress = view.findViewById(R.id.log_progress);
        logWarning = view.findViewById(R.id.log_warning_text);
        logSizeText = view.findViewById(R.id.log_size_text);
        logLabel = view.findViewById(R.id.log_label);

        deckContainer = view.findViewById(R.id.deck_container);
        btnServerControl = view.findViewById(R.id.btn_server_control);

        dashboardManager = new DashboardManager(requireActivity(), view);

        // Listeners
        button_browse_content.setOnClickListener(v -> mainActivity.handleBrowseContentClick(v));
        btnClearLog.setOnClickListener(this);
        btnCopyLog.setOnClickListener(this);
        logLabel.setOnClickListener(v -> handleLogToggle());

        btnServerControl.setOnClickListener(v -> {
            // --- Intercept based on State Machine ---
            DashboardFragment.SystemState state = ServerStateRepository.get().current().systemState;
            boolean isFullyInstalled = (state == DashboardFragment.SystemState.ONLINE || state == DashboardFragment.SystemState.OFFLINE);

            if (!isFullyInstalled) {
                Snackbar.make(v, R.string.server_not_installed_warning, 6000).show();
                return; // Stop execution here
            }
            // --------------------------------------------------

            // ADFA-4621: never toggle the server while a rootfs/module install is in flight —
            // concurrent proot sessions over the same rootfs corrupt the install.
            if (org.iiab.controller.install.presentation.InstallProgressRepository.get().isRunning()
                    || org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()) {
                Snackbar.make(v, R.string.server_busy_install_lock, 6000).show();
                return;
            }

            if (mainActivity.targetServerState != null) return;

            mainActivity.serverTransitionText = !ServerStateRepository.get().current().alive ? getString(R.string.server_booting) : getString(R.string.server_shutting_down);
            mainActivity.targetServerState = !ServerStateRepository.get().current().alive;

            updateUIColorsAndVisibility();
            btnServerControl.startProgress();

            mainActivity.handleServerLaunchClick(v);
        });

        logLabel.setText(String.format(getString(R.string.label_separator_up), getString(R.string.connection_log_label)));

        updateUI();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_clear_log) {
            showResetLogConfirmation();
        } else if (v.getId() == R.id.btn_copy_log) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IIAB Log", connectionLog.getContent());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), R.string.log_copied_toast, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void updateUI() {
        // Tunnel/VPN settings UI removed (ADFA-4553); no dynamic state to refresh here.
    }

    public void updateUIColorsAndVisibility() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        if (button_browse_content == null) return;

        // Explore Button
        button_browse_content.setVisibility(View.VISIBLE);
        if (!ServerStateRepository.get().current().alive) {
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
        DashboardFragment.SystemState state = ServerStateRepository.get().current().systemState;
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
            if (ServerStateRepository.get().current().alive) {
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
    // ADFA-4520 helpers -------------------------------------------------------

    private void setLohsToggleChecked(boolean checked) {
        suppressLohsToggle = true;
        if (lohs_toggle != null) lohs_toggle.setChecked(checked);
        suppressLohsToggle = false;
    }

    private void requestLohsStart() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            lohsLocationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        startLohs();
    }

    private void startLohs() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalHotspotManager.get().start(ctx.getApplicationContext());
        }
    }

    private void renderLohs(LocalHotspotManager.State st) {
        if (lohs_status == null || st == null) return;
        switch (st.phase) {
            case STARTING:
                lohs_status.setVisibility(View.VISIBLE);
                lohs_status.setText(R.string.lohs_status_starting);
                lohs_show_qr.setVisibility(View.GONE);
                break;
            case ON:
                lohsSsid = st.ssid;
                lohsPass = st.passphrase;
                setLohsToggleChecked(true);
                lohs_status.setVisibility(View.VISIBLE);
                lohs_status.setText(getString(R.string.lohs_status_on, st.ssid == null ? "" : st.ssid));
                lohs_show_qr.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                setLohsToggleChecked(false);
                lohs_status.setVisibility(View.VISIBLE);
                lohs_status.setText(getString(R.string.lohs_status_failed, st.failureReason));
                lohs_show_qr.setVisibility(View.GONE);
                break;
            case OFF:
            default:
                setLohsToggleChecked(false);
                lohsSsid = null;
                lohsPass = null;
                lohs_status.setVisibility(View.GONE);
                lohs_show_qr.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        maybeRecommendLohs();
    }

    /**
     * ADFA-4520: recommend LOHS only when BOTH conditions hold (AND, not OR): the operator
     * tried the native hotspot and it did not come up, AND there is no SIM in the device.
     * Reveals + pulses the Advanced settings header (where LOHS lives) and shows a snackbar.
     */
    private void maybeRecommendLohs() {
        Context ctx = getContext();
        if (ctx == null || !LocalHotspotManager.isSupported()) return;
        LocalHotspotManager mgr = LocalHotspotManager.get();
        boolean triedNative = mgr.wasNativeHotspotAttempted();
        boolean hotspotUp = mainActivity != null && mainActivity.isHotspotActive();
        boolean simAbsent = HotspotAvailability.isSimAbsent(ctx);
        if (!(triedNative && !hotspotUp && simAbsent)) return;
        mgr.clearNativeHotspotAttempted();
        expandAdvancedSettings();
        pulseView(dns_settings_label);
        View anchor = null;
        if (mainActivity != null) {
            anchor = mainActivity.findViewById(R.id.main_coordinator);
            if (anchor == null) anchor = mainActivity.findViewById(android.R.id.content);
        }
        if (anchor != null) {
            Snackbars.make(anchor, R.string.lohs_recommend)
                    .setAction(R.string.lohs_recommend_action, v -> {
                        expandAdvancedSettings();
                        pulseView(dns_settings_label);
                    })
                    .show();
        }
    }

    private void expandAdvancedSettings() {
        if (dns_settings_section != null && dns_settings_section.getVisibility() != View.VISIBLE) {
            toggleVisibility(dns_settings_section, dns_settings_label, getString(R.string.network_advanced_label));
        }
    }

    /**
     * ADFA-4520 recommendation cue. Mirrors DeployFragment#focusAdvancedMonitoring: blink the
     * header text colour (danger <-> normal, 400ms x5, reverse), resetting to normal at the end.
     */
    private void pulseView(View v) {
        if (!(v instanceof TextView) || getContext() == null) return;
        final TextView t = (TextView) v;
        final int normal = t.getCurrentTextColor();
        int danger = ContextCompat.getColor(requireContext(), R.color.status_danger);
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofObject(
                t, "textColor", new android.animation.ArgbEvaluator(), normal, danger);
        anim.setDuration(400);
        anim.setRepeatCount(5);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { t.setTextColor(normal); }
        });
        anim.start();
    }

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
            String logEntry = "[" + currentTime + "] " + message;
            if (connectionLog != null) {
                connectionLog.append(logEntry);
            }
        });
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
                    connectionLog.setContent(logContent);
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
                            connectionLog.clear();
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
        // Tunnel/VPN prefs removed (ADFA-4553); nothing to persist from this screen.
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