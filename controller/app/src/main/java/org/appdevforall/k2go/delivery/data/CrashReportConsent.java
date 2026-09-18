package org.appdevforall.k2go.delivery.data;

import android.content.Context;

import org.appdevforall.k2go.BuildConfig;

/**
 * Opt-in flag for crash / error reporting (GlitchTip via the Sentry SDK). Separate from
 * {@link AnalyticsConsent} on purpose: error reports are operational (not behavioural
 * telemetry) and carry no PII, so they can follow a different policy. The unset default comes
 * from {@code BuildConfig.CRASH_REPORTS_DEFAULT_ON}: true for conventional builds, false for the
 * F-Droid build (K2GO-401), so that build ships crash reporting OFF by default.
 */
public final class CrashReportConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "crash_reports_opt_in";

    private CrashReportConsent() {
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY, BuildConfig.CRASH_REPORTS_DEFAULT_ON);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply();
    }
}
