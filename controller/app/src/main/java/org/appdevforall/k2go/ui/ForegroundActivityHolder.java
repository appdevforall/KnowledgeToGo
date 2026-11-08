/*
 * ============================================================================
 * Name        : ForegroundActivityHolder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-557. One app-scoped holder of the current (resumed) Activity, registered once from
 *               IIABApplication. It lets app-wide work reach the foreground screen without a per-screen
 *               base class. {@link #current()} is a reusable foundation (future: password prompts). On
 *               each resume it also re-shows a pending dash-node update failure
 *               (DashUpdateFailureDialog.maybeShow), so a failure that happened while no screen was
 *               watching is not lost. The reference is weak and cleared on pause, so it never pins an
 *               Activity.
 * ============================================================================
 */
package org.appdevforall.k2go.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.appdevforall.k2go.dashboard.presentation.DashUpdateFailureDialog;

import java.lang.ref.WeakReference;

public final class ForegroundActivityHolder implements Application.ActivityLifecycleCallbacks {

    /**
     * Marker for an Activity that must NOT host app-wide dialogs: transient or non-Material-themed
     * screens (the splash, translucent confirms). The holder skips them, so a re-show never inflates a
     * Material dialog on a theme that lacks it (it crashes) or on a window about to finish. Same opt-out
     * shape as {@code EdgeToEdge.SelfManaged}.
     */
    public interface DialogExempt {}

    private static WeakReference<Activity> current;

    /** The current resumed Activity (never a {@link DialogExempt} one), or null when backgrounded. */
    @Nullable
    public static Activity current() {
        return current == null ? null : current.get();
    }

    @Override
    public void onActivityResumed(@NonNull Activity a) {
        if (a instanceof DialogExempt) return;   // splash / translucent: not a dialog host
        current = new WeakReference<>(a);
        // K2GO-557: re-surface a pending update failure on the screen the user just returned to.
        DashUpdateFailureDialog.maybeShow(a);
    }

    @Override
    public void onActivityPaused(@NonNull Activity a) {
        if (current() == a) current = null;
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle savedInstanceState) {}
    @Override public void onActivityStarted(@NonNull Activity a) {}
    @Override public void onActivityStopped(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {}
}
