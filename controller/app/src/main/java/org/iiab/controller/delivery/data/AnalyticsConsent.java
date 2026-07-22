package org.iiab.controller.delivery.data;

import android.content.Context;

/** Opt-in flag for usage analytics. Default OFF (privacy-by-default). */
public final class AnalyticsConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "analytics_opt_in";
    /** Whether the user has already been shown the one-time consent prompt (any flow). */
    private static final String KEY_ASKED = "analytics_enrollment_shown";

    private AnalyticsConsent() {
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply();
    }

    /** True once the consent prompt has been shown, so onboarding asks at most once. Shares the
     *  legacy key, so a user who already answered in the old flow is not asked again. */
    public static boolean wasAsked(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false);
    }

    public static void markAsked(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply();
    }
}
