/*
 * ============================================================================
 * Name        : OnlineChoiceEffects.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-588. The one place that applies the privacy choice to the
 *               clients that act on it (analytics, crash reports).
 * ============================================================================
 */
package org.appdevforall.k2go.delivery;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.analytics.AnalyticsClient;
import org.appdevforall.k2go.delivery.data.OnlineConsent;

/**
 * K2GO-588: every change of the choice (onboarding, Settings) calls {@link #apply(Context)}, and the
 * app start calls {@link #startCrashReports(Context)}. Gates that read the choice on each call
 * (network admission, saved-order admission, Sentry beforeSend) need nothing here. The app open
 * also re-applies Keep offline to the box (PendingRestartsOnOpen), which covers a restore or a clone.
 */
public final class OnlineChoiceEffects {

    private static final String TAG = "OnlineChoiceEffects";

    private OnlineChoiceEffects() {}

    /** Applies the stored choice now: analytics, crash reports, the catalog jobs and the box. */
    public static void apply(Context ctx) {
        Context app = ctx.getApplicationContext();
        AnalyticsClient.with(app).applyConsent();
        org.appdevforall.k2go.redesign.KiwixCatalog.syncRefresh(app);
        org.appdevforall.k2go.kolibri.data.CatalogRepositoryImpl.syncRefresh(app);
        if (OnlineConsent.isEnabled(app)) {
            startCrashReports(app);
        } else {
            if (io.sentry.Sentry.isEnabled()) {
                // close() flushes and waits for the transport (seconds): never on the main thread.
                new Thread(io.sentry.Sentry::close, "sentry-close").start();
            }
            org.appdevforall.k2go.privacy.presentation.PendingRestartsOnOpen.keepOfflineNow(app);
        }
    }

    /**
     * ADFA-4533: crash reports to GlitchTip (Sentry SDK) when the choice is on. No-op without a
     * built-in DSN (the SDK stays inert); the manifest sets io.sentry.auto-init=false, so this is the
     * only init path. Crash events only: no session tracking (it contacts GlitchTip at every start)
     * and no client reports (counts of dropped events). beforeSend drops events once the choice is off.
     * At app start this runs before K2GoUncaughtExceptionHandler is set, so that handler wraps
     * Sentry; a later start (the user turns the choice on) wraps ours instead, and both still run.
     */
    public static void startCrashReports(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!OnlineConsent.isEnabled(app) || io.sentry.Sentry.isEnabled()) return;
        try {
            io.sentry.android.core.SentryAndroid.init(app, options -> {
                options.setEnableAutoSessionTracking(false);
                options.setSendClientReports(false);
                options.setBeforeSend((event, hint) -> OnlineConsent.isEnabled(app) ? event : null);
            });
        } catch (Throwable t) {
            Log.e(TAG, "Sentry init skipped", t);
        }
    }
}
