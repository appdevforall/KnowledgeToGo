package org.iiab.controller.analytics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.iiab.controller.analytics.domain.AnalyticsBuckets;
import org.iiab.controller.delivery.data.AnalyticsConsent;
import org.iiab.controller.delivery.data.InstallId;
import org.iiab.controller.feedback.data.FeedbackDiagnostics;

/**
 * Emits operational, anonymous usage events to Firebase Analytics (ADFA-4466 Phase 1).
 *
 * <p><b>Online-first:</b> events are handed straight to the Firebase SDK, which batches
 * and delivers them itself (buffering while offline and flushing on reconnect). The
 * shared store-and-forward backbone and the Cloudflare Worker are out of scope for now.
 *
 * <p><b>Consent-gated:</b> every method is a no-op unless the operator has opted in
 * ({@link AnalyticsConsent}, default OFF). Collection is also toggled at the SDK level via
 * {@link FirebaseAnalytics#setAnalyticsCollectionEnabled(boolean)} so nothing is gathered
 * while consent is OFF. Advertising ID / SSAID collection is disabled in the manifest.
 *
 * <p><b>Data set is strictly operational:</b> an anonymous install id, build info and
 * coarse timing/config. No content, no per-user behaviour, no location, no PII. Device
 * model, OS version, app version and country are Firebase's own automatic dimensions, so
 * this class does not send them as parameters.
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

    /**
     * Keeps the SDK's collection flag in sync with the stored consent. Safe to call from
     * anywhere (e.g. Application start, or right after the consent toggle changes).
     */
    public void applyConsent() {
        FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(AnalyticsConsent.isEnabled(app));
    }

    // -------------------------------------------------------------- app lifecycle

    /** Once per install (and only while opted in). */
    public void logFirstRunIfNeeded() {
        if (!gate()) {
            return;
        }
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_FIRST_RUN_LOGGED, false)) {
            return;
        }
        log("first_run", base());
        p.edit().putBoolean(KEY_FIRST_RUN_LOGGED, true).apply();
    }

    /** App brought to the foreground. */
    public void logAppOpened() {
        if (!gate()) {
            return;
        }
        log("app_opened", base());
    }

    /** App sent to the background: the session and its duration. */
    public void logSession(long durationMs) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putLong("session_ms", durationMs);
        log("session", b);
    }

    // ------------------------------------------------------- operational (Phase 1)

    /** A rootfs install was launched. */
    public void logInstallStarted(String tier, boolean companionData, String arch) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putString("tier", safe(tier));
        b.putString("companion_data", String.valueOf(companionData));
        b.putString("arch", safe(arch));
        log("install_started", b);
    }

    /** A rootfs install finished (success flag). */
    public void logInstallCompleted(String tier, boolean success) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putString("tier", safe(tier));
        b.putString("success", String.valueOf(success));
        log("install_completed", b);
    }

    /** A rootfs install failed at a given phase, with an enum reason code (never a message). */
    public void logInstallFailed(String phase, String reasonCode) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putString("phase", safe(phase));
        b.putString("reason_code", safe(reasonCode));
        log("install_failed", b);
    }

    /** The box server came up. */
    public void logServerStarted() {
        if (!gate()) {
            return;
        }
        log("server_started", base());
    }

    /** The box server went down, with a coarse uptime bucket (never the exact duration). */
    public void logServerStopped(long uptimeMs) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putString("uptime_bucket", AnalyticsBuckets.uptimeBucket(uptimeMs));
        log("server_stopped", b);
    }

    // ------------------------------------------------------------- lifecycle (Phase 2)

    /** The operator finished the first-run setup wizard. */
    public void logOnboardingCompleted() {
        if (!gate()) {
            return;
        }
        log("onboarding_completed", base());
    }

    /** The operator sent operator feedback (no content, just that it happened). */
    public void logFeedbackSent() {
        if (!gate()) {
            return;
        }
        log("feedback_sent", base());
    }

    /** An uncaught error occurred: a coarse type only (e.g. the exception class), never the message. */
    public void logAppError(String type) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putString("type", safe(type));
        log("app_error", b);
    }

    /** A module install finished (module = fixed-catalog key; deployment config, not user content). */
    public void logModuleInstall(String module, boolean success) {
        if (!gate()) {
            return;
        }
        Bundle b = base();
        b.putString("module", safe(module));
        b.putString("result", success ? "success" : "failed");
        log("module_install", b);
    }

    // ------------------------------------------------------------------- internals

    /** True only when the operator opted in; also keeps the SDK flag in sync. */
    private boolean gate() {
        boolean consent = AnalyticsConsent.isEnabled(app);
        FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(consent);
        return consent;
    }

    /**
     * Common bundle: nothing device-identifying (Firebase auto-collects device/OS/version).
     * install_id + binaries_tag are set as user properties so every event is attributable to
     * an anonymous deployment without repeating them as params.
     */
    private Bundle base() {
        FirebaseAnalytics fa = FirebaseAnalytics.getInstance(app);
        fa.setUserProperty("install_id", InstallId.get(app));
        fa.setUserProperty("binaries_tag", safe(FeedbackDiagnostics.binariesTag(app)));
        return new Bundle();
    }

    private void log(String name, Bundle params) {
        FirebaseAnalytics.getInstance(app).logEvent(name, params);
    }

    private static String safe(String v) {
        return v != null ? v : "";
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
