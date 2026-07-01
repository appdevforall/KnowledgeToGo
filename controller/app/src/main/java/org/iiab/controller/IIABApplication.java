/*
 * ============================================================================
 * Name        : IIABApplication.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Main Application class for IIAB
 * ============================================================================
 */

package org.iiab.controller;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.conscrypt.Conscrypt;
import org.iiab.controller.analytics.AnalyticsClient;

import java.security.Security;

import android.util.Log;

public class IIABApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // We inject Conscrypt as the app's primary security provider
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
            Log.i("IIABApplication", "Conscrypt initialized successfully.");
        } catch (Exception e) {
            Log.e("IIABApplication", "Error initializing Conscrypt", e);
        }

        // Capture uncaught exceptions into a crash report, offered on next launch.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
                new org.iiab.controller.feedback.crash.K2GoUncaughtExceptionHandler(
                        new org.iiab.controller.feedback.crash.data.CrashReportStore(this), previous));

        // Operational usage analytics (no-op unless the operator opted in). Emits first_run
        // once, plus app_opened + session duration as the process moves fore/background.
        AnalyticsClient.with(this).logFirstRunIfNeeded();
        registerActivityLifecycleCallbacks(new ForegroundTracker());
    }

    /**
     * Tracks whole-process foreground state with a simple started-activity counter:
     * 0 -> 1 is an app open (session start), 1 -> 0 is the app going to the background
     * (session end + duration). Good enough for operational metrics without extra deps.
     */
    private final class ForegroundTracker implements ActivityLifecycleCallbacks {
        private int startedActivities = 0;
        private long sessionStartMs = 0L;

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            if (startedActivities == 0) {
                sessionStartMs = System.currentTimeMillis();
                AnalyticsClient.with(IIABApplication.this).logAppOpened();
            }
            startedActivities++;
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            startedActivities--;
            if (startedActivities <= 0) {
                startedActivities = 0;
                if (sessionStartMs > 0L) {
                    AnalyticsClient.with(IIABApplication.this)
                            .logSession(System.currentTimeMillis() - sessionStartMs);
                    sessionStartMs = 0L;
                }
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
