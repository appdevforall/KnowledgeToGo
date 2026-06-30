/*
 * ============================================================================
 * Name        : IIABApplication.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Main Application class for IIAB
 * ============================================================================
 */

package org.iiab.controller;

import android.app.Application;

import org.conscrypt.Conscrypt;

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
    }
}