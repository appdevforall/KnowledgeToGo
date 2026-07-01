package org.iiab.controller.feedback.data;

import android.content.Context;

/**
 * Selects the feedback transport. Default is {@code mailto} (unchanged behaviour); a
 * later PR can flip the default to {@code worker} once the Cloudflare Worker is approved
 * and deployed. The value is prefs-backed so it can also be flipped at runtime. ADFA-4507.
 */
public final class FeedbackConfig {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "feedback_transport";

    public static final String MAILTO = "mailto";
    public static final String WORKER = "worker";
    public static final String DEFAULT = MAILTO;

    private FeedbackConfig() {
    }

    public static String transport(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT);
    }

    public static void setTransport(Context ctx, String transport) {
        String t = WORKER.equals(transport) ? WORKER : MAILTO;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).apply();
    }

    /** The transport to use right now, per the flag. */
    public static FeedbackTransport create(Context ctx) {
        return WORKER.equals(transport(ctx))
                ? new WorkerFeedbackTransport()
                : new MailtoFeedbackTransport();
    }
}
