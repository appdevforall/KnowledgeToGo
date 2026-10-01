/*
 * ============================================================================
 * Name        : EdgeToEdgeCallbacks.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One owner for edge-to-edge insets (K2GO-439). Registered from
 *               IIABApplication, it pads every activity's content by the system
 *               bars so nothing draws under the status / navigation bars on
 *               targetSdk 35 (Android 15+). Screens that manage their own insets
 *               (LibraryActivity) or want none (PortalActivity immersive mode,
 *               SplashActivity) opt out via EdgeToEdge.SelfManaged.
 * ============================================================================
 */
package org.appdevforall.k2go.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Applies system-bar insets to each activity's content view, except opted-out screens. */
public final class EdgeToEdgeCallbacks implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onActivityCreated(@NonNull Activity a, @Nullable Bundle savedInstanceState) {
        if (a instanceof EdgeToEdge.SelfManaged) return;
        // android.R.id.content is the frame that holds setContentView's view; padding it insets the screen.
        EdgeToEdge.padAll(a.findViewById(android.R.id.content));
    }

    @Override public void onActivityStarted(@NonNull Activity a) {}
    @Override public void onActivityResumed(@NonNull Activity a) {}
    @Override public void onActivityPaused(@NonNull Activity a) {}
    @Override public void onActivityStopped(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {}
}
