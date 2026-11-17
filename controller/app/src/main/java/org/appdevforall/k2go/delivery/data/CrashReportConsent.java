package org.appdevforall.k2go.delivery.data;

import android.content.Context;

/**
 * Opt-in flag for crash / error reporting (GlitchTip via the Sentry SDK). Separate from
 * {@link AnalyticsConsent} on purpose: error reports are operational (not behavioural
 * telemetry) and carry no PII, so they can follow a different policy. Off until the user turns it
 * on, in every build (K2GO-579): "Keep offline" must mean no outbound connection.
 */
public final class CrashReportConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "crash_reports_opt_in";

    private CrashReportConsent() {
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply();
    }
}
