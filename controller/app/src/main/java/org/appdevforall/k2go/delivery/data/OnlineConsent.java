package org.appdevforall.k2go.delivery.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * K2GO-588: the one privacy choice. On = usage data (where the build has analytics), crash reports
 * and automatic connections (catalog and update checks) on an unmetered network. Off ("Keep
 * offline", the default) = none of them. The choice covers more than the old analytics and
 * crash-report opt-ins, so their answers are dropped and every user is asked once again.
 */
public final class OnlineConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "online_choice";
    /** Whether the user has answered the one-time question (any flow). */
    private static final String KEY_ASKED = "online_choice_asked";
    /** The two opt-ins that this choice replaces (before K2GO-588). */
    private static final String[] OLD_KEYS = {
            "analytics_opt_in", "analytics_enrollment_shown", "crash_reports_opt_in"};

    private OnlineConsent() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY, enabled).apply();
    }

    /** True once the user has answered, so the question shows at most once. */
    public static boolean wasAsked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ASKED, false);
    }

    public static void markAsked(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ASKED, true).apply();
    }

    /** Removes the answers of the old opt-ins, once, so nothing reads them again. */
    public static void dropOldAnswers(Context ctx) {
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor e = null;
        for (String k : OLD_KEYS) {
            if (p.contains(k)) {
                if (e == null) e = p.edit();
                e.remove(k);
            }
        }
        if (e != null) e.apply();
    }
}
