/*
 * ============================================================================
 * Name        : BiometricHelper.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Biometrics helper
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import org.iiab.controller.ui.dialog.BrandDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

public class BiometricHelper {

    // This is the "phone line" that tells the caller the result
    public interface AuthCallback {
        void onSuccess();
        void onFailed(); // Added failure/cancellation callback
    }

    public static boolean isDeviceSecure(Context context) {
        BiometricManager bm = BiometricManager.from(context);
        int auth = BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            auth = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }
        android.app.KeyguardManager km = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

        return bm.canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS || (km != null && km.isDeviceSecure());
    }

    public static void showEnrollmentDialog(Context context) {
        new BrandDialog(context)
                .setTitle(R.string.security_required_title)
                .setMessage(R.string.security_required_msg)
                .setPositive(R.string.go_to_settings, () -> {
                    Intent intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                    context.startActivity(intent);
                })
                .setNegative(R.string.cancel, null)
                .show();
    }

    // LEGACY COMPATIBILITY: If the old code calls it without handling errors
    public static void prompt(AppCompatActivity activity, String title, String subtitle, Runnable onSuccess) {
        prompt(activity, title, subtitle, new AuthCallback() {
            @Override
            public void onSuccess() {
                if (onSuccess != null) onSuccess.run();
            }

            @Override
            public void onFailed() {
                // Do nothing
            }
        });
    }

    // NEW IMPLEMENTATION: Handles both Success and Failure robustly
    public static void prompt(AppCompatActivity activity, String title, String subtitle, AuthCallback callback) {
        int tempAuth = BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            tempAuth = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }

        final int finalAuth = tempAuth;

        // 1. PRE-CHECK: Auto-fail if the device has no security, avoiding 30s hangs in Bash
        BiometricManager bm = BiometricManager.from(activity);
        if (bm.canAuthenticate(finalAuth) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (callback != null) callback.onFailed();
            return;
        }

        // 2. DELAY HACK: Give the window manager 150ms to stabilize after receiving the broadcast.
        // This prevents BiometricPrompt from silently aborting if the keyboard or BottomSheet is shifting.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Executor executor = ContextCompat.getMainExecutor(activity);
                BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        if (callback != null) callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // Triggered if user cancels, clicks outside, or fails too many times
                        if (callback != null) callback.onFailed();
                    }

                    // NOTE: We do NOT override onAuthenticationFailed() here.
                    // If it fails (e.g. wrong finger), the system UI shakes and lets them try again.
                });

                BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setAllowedAuthenticators(finalAuth)
                        .build();

                biometricPrompt.authenticate(promptInfo);
            } catch (Exception e) {
                if (callback != null) callback.onFailed();
            }
        }, 300);
    }
}