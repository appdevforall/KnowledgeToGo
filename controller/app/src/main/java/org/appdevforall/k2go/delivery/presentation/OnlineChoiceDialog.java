/*
 * ============================================================================
 * Name        : OnlineChoiceDialog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-588. The one-time privacy question and the label of its switch.
 * ============================================================================
 */
package org.appdevforall.k2go.delivery.presentation;

import android.app.Activity;

import org.appdevforall.k2go.BuildConfig;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.delivery.OnlineChoiceEffects;
import org.appdevforall.k2go.delivery.data.OnlineConsent;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

/**
 * K2GO-588: asked once in every build (F-Droid too: crash reports and online content). The wizard
 * asks on a fresh install; Home asks an existing install after the update.
 */
public final class OnlineChoiceDialog {

    private OnlineChoiceDialog() {}

    /** The switch and button label: usage data only where the build has analytics (not F-Droid). */
    public static int choiceLabel() {
        return BuildConfig.ANALYTICS_ENABLED
                ? R.string.k2go_online_accept_usage
                : R.string.k2go_online_accept_crash;
    }

    /** The question text that matches {@link #choiceLabel()}. */
    public static int choiceBody() {
        return BuildConfig.ANALYTICS_ENABLED
                ? R.string.k2go_online_body_usage
                : R.string.k2go_online_body_crash;
    }

    /** Shows the question unless it was answered; runs {@code onDone} after the answer, or at once. */
    public static void askOnce(Activity activity, Runnable onDone) {
        if (OnlineConsent.wasAsked(activity)) { onDone.run(); return; }
        new BrandDialog(activity)
                .setTitle(R.string.k2go_online_title)
                .setMessage(activity.getString(choiceBody(), activity.getString(R.string.app_name)))
                .setCancelable(false)
                .setPositive(choiceLabel(), () -> answer(activity, true, onDone))
                .setNegative(R.string.analytics_enroll_decline, () -> answer(activity, false, onDone))
                .show();
    }

    private static void answer(Activity activity, boolean on, Runnable onDone) {
        OnlineConsent.setEnabled(activity, on);
        OnlineConsent.markAsked(activity);
        OnlineChoiceEffects.apply(activity);
        onDone.run();
    }
}
