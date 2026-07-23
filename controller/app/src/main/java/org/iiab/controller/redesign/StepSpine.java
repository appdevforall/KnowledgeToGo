/*
 * ============================================================================
 * Name        : StepSpine.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Shared numbered step spine — teal circle + number, green corner
 *               check when a step is done, label below, arrow between steps.
 *               This is the Clone/Connect stepper style; the setup wizard reuses
 *               it (ADFA-4815) so every multi-step flow looks and behaves the
 *               same instead of a plain text breadcrumb.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

public final class StepSpine {

    private StepSpine() {}

    /** One step: the number shown in the circle, the label under it, whether it is the
     *  current step (filled) and whether it is completed (filled + green corner check). */
    public static final class Step {
        final String number, label;
        final boolean active, done;
        public Step(String number, String label, boolean active, boolean done) {
            this.number = number; this.label = label; this.active = active; this.done = done;
        }
    }

    /** Clears {@code container} (a horizontal LinearLayout) and fills it with the steps,
     *  separated by arrows — identical to the Clone/Connect spine. */
    public static void render(LinearLayout container, Step... steps) {
        if (container == null) return;
        container.removeAllViews();
        container.setOrientation(LinearLayout.HORIZONTAL);
        Context ctx = container.getContext();
        for (int i = 0; i < steps.length; i++) {
            container.addView(badge(ctx, steps[i]));
            if (i < steps.length - 1) container.addView(arrow(ctx));
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static View badge(Context ctx, Step s) {
        boolean filled = s.active || s.done;

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 84), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout fl = new FrameLayout(ctx);
        int d = dp(ctx, 38);
        View circle = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (filled) g.setColor(ContextCompat.getColor(ctx, R.color.k2go_teal));
        else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(ctx, 2), ContextCompat.getColor(ctx, R.color.k2go_muted)); }
        circle.setBackground(g);
        fl.addView(circle, new FrameLayout.LayoutParams(d, d));

        TextView t = new TextView(ctx);
        t.setText(s.number);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ContextCompat.getColor(ctx, filled ? R.color.k2go_on_teal : R.color.k2go_muted));
        fl.addView(t, new FrameLayout.LayoutParams(d, d));

        if (s.done) {
            FrameLayout check = new FrameLayout(ctx);
            int cd = dp(ctx, 16);
            View co = new View(ctx);
            GradientDrawable cg = new GradientDrawable();
            cg.setShape(GradientDrawable.OVAL);
            cg.setColor(ContextCompat.getColor(ctx, R.color.k2go_leaf));
            co.setBackground(cg);
            check.addView(co, new FrameLayout.LayoutParams(cd, cd));
            TextView ck = new TextView(ctx);
            ck.setText("✓");
            ck.setGravity(Gravity.CENTER);
            ck.setTextSize(9);
            ck.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_on_teal));
            check.addView(ck, new FrameLayout.LayoutParams(cd, cd));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cd, cd);
            clp.gravity = Gravity.TOP | Gravity.END;
            fl.addView(check, clp);
        }
        col.addView(fl, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));

        TextView lbl = new TextView(ctx);
        lbl.setText(s.label);
        lbl.setGravity(Gravity.CENTER);
        lbl.setTextSize(12);
        lbl.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        col.addView(lbl);
        return col;
    }

    private static View arrow(Context ctx) {
        TextView a = new TextView(ctx);
        a.setText("→");
        a.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        a.setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.bottomMargin = dp(ctx, 18);
        a.setLayoutParams(lp);
        return a;
    }
}
