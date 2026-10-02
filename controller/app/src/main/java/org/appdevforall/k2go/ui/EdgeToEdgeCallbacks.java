/*
 * ============================================================================
 * Name        : EdgeToEdgeCallbacks.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One owner for edge-to-edge insets (K2GO-439). Registered from
 *               IIABApplication, it pads every activity's content by the system
 *               bars (and the IME) so nothing draws under the status / navigation
 *               bars or the keyboard on targetSdk 35 (Android 15+). Screens that
 *               manage their own insets (LibraryActivity), whose layout already
 *               consumes them (HelpViewerActivity via fitsSystemWindows), or want
 *               none (SplashActivity) opt out via EdgeToEdge.SelfManaged.
 *               PortalActivity stays in: its immersive mode zeroes the insets when
 *               it hides the bars, so the one handler covers both of its states.
 * ============================================================================
 */
package org.appdevforall.k2go.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Applies system-bar and IME insets to each activity's content view, except opted-out screens. */
public final class EdgeToEdgeCallbacks implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onActivityCreated(@NonNull Activity a, @Nullable Bundle savedInstanceState) {
        if (a instanceof EdgeToEdge.SelfManaged) return;
        // android.R.id.content is the frame that holds setContentView's view; padding it insets the screen.
        // These screens have no bottom bar, so clearing the keyboard (IME) on the bottom is safe.
        EdgeToEdge.padAllAndIme(a.findViewById(android.R.id.content));
    }

    @Override public void onActivityStarted(@NonNull Activity a) {}
    @Override public void onActivityResumed(@NonNull Activity a) {}
    @Override public void onActivityPaused(@NonNull Activity a) {}
    @Override public void onActivityStopped(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {}
}
