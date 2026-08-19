/*
 * ============================================================================
 * Name        : TerminalActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5192. Dedicated host for the Debian terminal, re-homed out of the
 *               legacy MainActivity so that Activity (and the dead legacy tabbed UI) can
 *               be removed. Thin by design: it loads the native termux engine, hosts the
 *               terminal_bottom_sheet layout, and delegates everything to TerminalController
 *               (which owns the TerminalView, sessions, drawer and extra keys). The two
 *               Activity-side callbacks TerminalController needs — addToLog / vibrateDevice —
 *               are implemented here.
 *
 *               Behaviour-preserving: the terminal opens full-screen and a swipe-down on the
 *               sheet finishes back to the caller (the redesign), exactly as the old
 *               terminal-only mode did in MainActivity.
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

public class TerminalActivity extends AppCompatActivity implements TerminalController.Host {

    // Load native C++ engine (termux). Was previously loaded by MainActivity's static block.
    static {
        System.loadLibrary("termux");
    }

    private TerminalController terminalController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.terminal_activity);

        terminalController = new TerminalController(this, this);
        terminalController.bind();
        attachFinishOnHide();
        openTerminal();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Reused via FLAG_ACTIVITY_SINGLE_TOP (e.g. the keep-alive notification): re-expand.
        openTerminal();
    }

    private void openTerminal() {
        if (terminalController == null) return;
        View root = findViewById(R.id.terminal_coordinator);
        Runnable open = () -> terminalController.openFullTerminal();
        if (root != null) root.post(open);
        else open.run();
    }

    /**
     * Swiping the terminal sheet down finishes back to the caller instead of exposing an
     * empty surface. Mirrors MainActivity.attachTerminalOnlyFinish (ADFA-4987): no peek
     * stop — a swipe-down goes straight to HIDDEN -> finish().
     */
    private void attachFinishOnHide() {
        View sheet = findViewById(R.id.terminal_bottom_sheet);
        if (sheet == null) return;
        BottomSheetBehavior<View> b = BottomSheetBehavior.from(sheet);
        b.setHideable(true);
        b.setSkipCollapsed(true);
        b.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    finish();
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) { }
        });
    }

    @Override
    protected void onDestroy() {
        // Release the terminal UI delegate so the app-scoped session store never holds a
        // destroyed Activity; running sessions keep going (ADFA-4696).
        if (terminalController != null) terminalController.detach();
        super.onDestroy();
    }

    // --- TerminalController.Host ---------------------------------------------
    @Override
    public void addToLog(String message) {
        // Single source of truth; the Usage console observes LogRepository (ADFA-4640).
        LogRepository.get().append(message);
    }

    @Override
    public void vibrateDevice() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(50);
            }
        }
    }
}
