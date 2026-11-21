package org.appdevforall.k2go.delivery.data;

import android.content.Context;

/**
 * K2GO-588: the one privacy choice. On = usage data (where the build has analytics), crash reports
 * and automatic connections (catalog and update checks) on an unmetered network. Off ("Keep
 * offline", the default) = none of them. The stored key keeps its old analytics name, so an earlier
 * "Share anonymous data" answer carries over and every other user stays offline.
 */
public final class OnlineConsent {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "analytics_opt_in";
    /** Whether the user has already been shown the one-time consent prompt (any flow). */
    private static final String KEY_ASKED = "analytics_enrollment_shown";

    private OnlineConsent() {
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply();
    }

    /** The switch and button label: usage data only where the build has analytics (not F-Droid). */
    public static int choiceLabel() {
        return org.appdevforall.k2go.BuildConfig.ANALYTICS_ENABLED
                ? org.appdevforall.k2go.R.string.k2go_online_accept_usage
                : org.appdevforall.k2go.R.string.k2go_online_accept_crash;
    }

    /** The onboarding text that matches {@link #choiceLabel()}. */
    public static int choiceBody() {
        return org.appdevforall.k2go.BuildConfig.ANALYTICS_ENABLED
                ? org.appdevforall.k2go.R.string.k2go_online_body_usage
                : org.appdevforall.k2go.R.string.k2go_online_body_crash;
    }

    /** True once the consent prompt has been shown, so onboarding asks at most once. */
    public static boolean wasAsked(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false);
    }

    public static void markAsked(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply();
    }
}
