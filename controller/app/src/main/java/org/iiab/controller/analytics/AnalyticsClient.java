package org.iiab.controller.analytics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.iiab.controller.analytics.domain.AnalyticsEvent;
import org.iiab.controller.delivery.DeliveryManager;
import org.iiab.controller.delivery.data.AnalyticsConsent;
import org.iiab.controller.delivery.data.InstallId;
import org.iiab.controller.feedback.data.FeedbackDiagnostics;

/**
 * Emits operational, anonymous usage events into the shared delivery backbone. Every
 * method is a no-op unless the operator has opted in ({@link AnalyticsConsent}) — so with
 * consent OFF nothing is built, collected, or enqueued. The backbone (WorkManager) flushes
 * to the Cloudflare Worker when connectivity returns; if no endpoint is configured yet the
 * events simply stay queued.
 *
 * <p>Data set is strictly operational: an anonymous install id, app/device build info and
 * timing. No content, no per-user behaviour, no location, no PII.
 */
public final class AnalyticsClient {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY_FIRST_RUN_LOGGED = "analytics_first_run_logged";

    private final Context app;

    private AnalyticsClient(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public static AnalyticsClient with(Context ctx) {
        return new AnalyticsClient(ctx);
    }

    /** Once per install (and only while opted in): app installed / first run. */
    public void logFirstRunIfNeeded() {
        if (!AnalyticsConsent.isEnabled(app)) {
            return;
        }
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_FIRST_RUN_LOGGED, false)) {
            return;
        }
        enqueue(base("first_run"));
        p.edit().putBoolean(KEY_FIRST_RUN_LOGGED, true).apply();
    }

    /** App brought to the foreground. */
    public void logAppOpened() {
        if (!AnalyticsConsent.isEnabled(app)) {
            return;
        }
        enqueue(base("app_opened"));
    }

    /** App sent to the background: the session and its duration. */
    public void logSession(long durationMs) {
        if (!AnalyticsConsent.isEnabled(app)) {
            return;
        }
        enqueue(base("session").with("session_ms", durationMs));
    }

    private AnalyticsEvent base(String name) {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "";
        return AnalyticsEvent.named(name)
                .with("install_id", InstallId.get(app))
                .with("app_version", FeedbackDiagnostics.appVersionName(app))
                .with("app_build", FeedbackDiagnostics.appVersionCode(app))
                .with("android", Build.VERSION.RELEASE)
                .with("device", Build.MANUFACTURER + " " + Build.MODEL)
                .with("abi", abi)
                .with("binaries_tag", FeedbackDiagnostics.binariesTag(app))
                .with("ts", System.currentTimeMillis());
    }

    private void enqueue(AnalyticsEvent e) {
        DeliveryManager.with(app).enqueueAnalytics(e.toJson());
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
