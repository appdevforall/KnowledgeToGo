package org.iiab.controller.delivery.data;

import android.content.Context;

/**
 * Opt-in flag for crash / error reporting (GlitchTip via the Sentry SDK). Separate from
 * {@link AnalyticsConsent} on purpose: error reports are operational (not behavioural
 * telemetry) and carry no PII, so they can follow a different policy. Default ON; flip the
 * default here if administration decides error reports must also be opt-in.
 */
public final class CrashReportConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "crash_reports_opt_in";

    private CrashReportConsent() {
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply();
    }
}
