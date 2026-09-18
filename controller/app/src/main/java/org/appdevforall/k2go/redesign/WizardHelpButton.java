/*
 * ============================================================================
 * Name        : WizardHelpButton.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-406. A fixed top-right "help" icon for the install wizard, added to the
 *               activity content root (no per-layout edit), mirroring FeedbackFab.installOn.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;

/**
 * A fixed top-right help icon on the install wizard. Tap opens the in-app manual
 * ({@link HelpViewerActivity}). Design (K2GO-406): the wizard shows one Help icon top-right on
 * every step; the installed Library home does not. Added programmatically to
 * {@code android.R.id.content} like {@link org.appdevforall.k2go.feedback.presentation.FeedbackFab},
 * so no wizard layout needs editing and every step gets it from one call. Idempotent.
 */
public final class WizardHelpButton {

    private WizardHelpButton() {}

    public static void installOn(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || root.findViewById(R.id.k2go_help_button) != null) {
            return;   // idempotent: already installed on this screen
        }
        float d = activity.getResources().getDisplayMetrics().density;

        ImageButton b = new ImageButton(activity);
        b.setId(R.id.k2go_help_button);
        b.setImageResource(R.drawable.ic_help_outline_24);
        b.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.k2go_teal)));
        b.setContentDescription(activity.getString(R.string.k2go_settings_help));
        int pad = Math.round(8 * d);
        b.setPadding(pad, pad, pad, pad);
        // Borderless ripple, no opaque fill: it floats over the wizard header.
        TypedValue tv = new TypedValue();
        activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);

        int size = Math.round(48 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                Gravity.TOP | Gravity.END);
        int m = Math.round(8 * d);
        lp.setMargins(m, Math.round(16 * d), m, m);   // top margin clears the status bar
        root.addView(b, lp);

        // K2GO-410: the wizard help opens the install topic; Settings (no extra) opens HOME.
        b.setOnClickListener(v ->
                activity.startActivity(new Intent(activity, HelpViewerActivity.class)
                        .putExtra(HelpViewerActivity.EXTRA_TOPIC,
                                org.appdevforall.k2go.help.domain.HelpTopic.INSTALL.name())));
    }
}
