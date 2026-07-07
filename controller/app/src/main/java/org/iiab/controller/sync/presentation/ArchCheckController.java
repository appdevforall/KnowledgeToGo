/*
 * ============================================================================
 * Name        : ArchCheckController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Architecture (32/64-bit) compatibility check carved out of
 *               SyncFragment (strangler-fig, ADFA-4506): the host/guest arch
 *               labels, the incompatibility dialog, and the compatibility-success
 *               feedback (vibrate + snackbar + delayed label reset). Self-contained;
 *               the label-visibility rule reads the sync mode / server state back
 *               through ArchCheckHost. No behaviour change.
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import org.iiab.controller.util.Snackbars;

import org.iiab.controller.R;

public final class ArchCheckController {

    private static final String TAG = "IIAB-ArchCheckController";

    private final Fragment fragment;
    private final ArchCheckHost host;

    // Borrowed views (set in bind(), from the Fragment's onCreateView()).
    private TextView txtHostArchLabel;
    private TextView txtGuestArchLabel;

    public ArchCheckController(Fragment fragment, ArchCheckHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Borrow the two arch-label views owned by the Fragment's layout. */
    public void bind(TextView hostArchLabel, TextView guestArchLabel) {
        this.txtHostArchLabel = hostArchLabel;
        this.txtGuestArchLabel = guestArchLabel;
    }

    /** This device's architecture width in bits (64 if the native lib dir is 64-bit). */
    public int getArchBits() {
        String arch = getTermuxArch();
        return (arch != null && arch.contains("64")) ? 64 : 32;
    }

    /** Set the static "App is N-bit" text on both labels. */
    public void applyStaticLabels() {
        String archLabelText = fragment.getString(R.string.sync_app_arch_label, getArchBits());
        if (txtHostArchLabel != null) txtHostArchLabel.setText(archLabelText);
        if (txtGuestArchLabel != null) txtGuestArchLabel.setText(archLabelText);
    }

    public void showArchIncompatibilityDialog(String message) {
        android.os.Vibrator v = (android.os.Vibrator) fragment.requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(500);
            }
        }

        new AlertDialog.Builder(fragment.requireContext())
                .setTitle(fragment.getString(R.string.sync_error_arch_title))
                .setMessage(message)
                .setPositiveButton(fragment.getString(R.string.adb_enforcer_btn_ok), null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private String getTermuxArch() {
        try {
            android.content.pm.ApplicationInfo info = fragment.requireContext().getApplicationInfo();
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

    public void showArchCompatibilitySuccess(Runnable onComplete) {
        // S8: this runs in the pre-transfer probing phase. A theme toggle / config
        // change here detaches the fragment, so guard every context/view access and
        // re-check inside the delayed runnable before touching the UI (was an
        // IllegalStateException "not attached to a context" crash).
        Context ctx = fragment.getContext();
        if (!fragment.isAdded() || ctx == null || fragment.getView() == null) return;

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

        Snackbars.make(fragment.getView(), fragment.getString(R.string.sync_msg_arch_compatible)).show();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Context laterCtx = fragment.getContext();
            if (!fragment.isAdded() || laterCtx == null) return; // S8: fragment gone during the 1.5s delay
            if (txtGuestArchLabel != null) {
                txtGuestArchLabel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(laterCtx, R.color.surface_section)));
                txtGuestArchLabel.setTextColor(ContextCompat.getColor(laterCtx, R.color.status_success));
            }
            onComplete.run();
        }, 1500);
    }

    public void updateArchLabelsVisibility() {
        boolean isShareMode = host.isShareMode();
        boolean isServerRunning = host.isServerRunning();

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
