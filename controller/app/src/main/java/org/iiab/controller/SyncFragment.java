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
import android.widget.ImageButton;
import android.widget.LinearLayout;
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


public class SyncFragment extends Fragment implements org.iiab.controller.sync.presentation.ArchCheckHost,
        org.iiab.controller.sync.presentation.ShareHost,
        org.iiab.controller.sync.presentation.ReceiveHost {

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

    // ADFA-4506: the Receive flow (scan/probe/dry-run/transfer) carved into a controller.
    private final org.iiab.controller.sync.presentation.ReceiveController receiveController =
            new org.iiab.controller.sync.presentation.ReceiveController(this, this);

    private RadioGroup rgSyncMode;
    private LinearLayout containerShare, containerReceive;

    // Managers
    private org.iiab.controller.sync.transport.TransportEngine transport;
    private org.iiab.controller.sync.presentation.SyncStateViewModel syncVm; // 3b-2: survives recreation

    // Share config (rsync port/user/module/apk-port) — used by the Receive probe;
    // the Share area itself is owned by ShareController.
    private final org.iiab.controller.sync.domain.ShareConfig shareConfig = org.iiab.controller.sync.domain.ShareConfig.defaults();

    // Scanner Launcher
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null) {
            receiveController.handleScannedData(result.getContents());
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
        receiveController.bind(view, syncVm, shareConfig);

        rgSyncMode = view.findViewById(R.id.rg_sync_mode);
        containerShare = view.findViewById(R.id.container_share);
        containerReceive = view.findViewById(R.id.container_receive);

        TextView txtHostArchLabel = view.findViewById(R.id.txt_host_arch_label);
        TextView txtGuestArchLabel = view.findViewById(R.id.txt_guest_arch_label);

        // ADFA-4506: arch labels + compatibility dialogs are owned by ArchCheckController.
        archCheckController.bind(txtHostArchLabel, txtGuestArchLabel);
        archCheckController.applyStaticLabels();
        archCheckController.updateArchLabelsVisibility();
        setupToggleLogic();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 3b-2: re-bind the rsync transfer progress after any recreation (theme toggle).
        org.iiab.controller.sync.presentation.SyncProgressRepository.get().state().observe(getViewLifecycleOwner(), receiveController::renderTransfer);
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

    /** Renders the transfer state from SyncProgressRepository; re-binds after recreation (3b-2). */

    /** Forces the Share tab into receive mode so the transfer progress is visible after a
     *  recreation (the mode toggle resets otherwise). 3b-2. */

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
        return ServerStateRepository.get().current().alive;
    }

    @Override
    public void updateArchLabelsVisibility() {
        archCheckController.updateArchLabelsVisibility();
    }

    @Override
    public int getArchBits() {
        return archCheckController.getArchBits();
    }
    // --- ReceiveHost (ADFA-4506) -------------------------------------------
    @Override
    public void showArchIncompatibilityDialog(String message) {
        archCheckController.showArchIncompatibilityDialog(message);
    }

    @Override
    public void showArchCompatibilitySuccess(Runnable onComplete) {
        archCheckController.showArchCompatibilitySuccess(onComplete);
    }

    @Override
    public void launchQrScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.sync_scanner_prompt));
        options.setCameraId(0);
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        barcodeLauncher.launch(options);
    }

    @Override
    public void selectReceiveMode() {
        if (rgSyncMode.getCheckedRadioButtonId() != R.id.rb_mode_receive) {
            rgSyncMode.check(R.id.rb_mode_receive);
        }
    }
}
