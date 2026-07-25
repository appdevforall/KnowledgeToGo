/*
 * ============================================================================
 * Name        : ProvisioningChecklist.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. The one shared per-item checklist renderer for content provisioning —
 *               a round green check (done), a colored dot (teal active / amber failed / gray
 *               queued), the item label (+ optional sub-label), and an inline Retry on failure.
 *               ZIM (ZimPreparingFragment) and Books (BooksDownloadsFragment) both draw their item
 *               lists through this, so the row styling, states, and Retry live in ONE place instead
 *               of a copy per screen. Callers supply the label text and the retry action; the
 *               "active" bucket is any non-terminal, non-queued status, which covers ZIM's INDEXING
 *               and Books' ADDING alike.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

public final class ProvisioningChecklist {
    private ProvisioningChecklist() {}

    public interface OnRetry { void retry(int index); }

    /** Text for a row. {@code sub} is an optional second line (null/empty = single-line). */
    public interface RowText {
        String main(int index);
        default String sub(int index) { return null; }
    }

    /** Statuses use the services' shared convention: PENDING=0, DONE=doneVal, FAILED=failedVal;
     *  any other value counts as "active" (ZIM INDEXING / Books ADDING). */
    public static void render(Context ctx, LinearLayout list, int count, int[] status,
                              int doneVal, int failedVal, RowText text, OnRetry onRetry) {
        list.removeAllViews();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            int st = (status != null && i < status.length) ? status[i] : 0;
            boolean done = st == doneVal;
            boolean failed = st == failedVal;
            boolean active = st != 0 && !done && !failed;

            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(ctx, 6), 0, px(ctx, 6));

            if (done) {
                ImageView chk = new ImageView(ctx);
                chk.setImageResource(R.drawable.ic_check_circle);
                chk.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_leaf));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(ctx, 16), px(ctx, 16));
                clp.rightMargin = px(ctx, 8);
                r.addView(chk, clp);
            } else {
                View dot = new View(ctx);
                dot.setBackgroundResource(R.drawable.k2go_dot);
                int c = failed ? R.color.k2go_amber : (active ? R.color.k2go_teal : R.color.k2go_hairline);
                dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, c)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(ctx, 10), px(ctx, 10));
                dlp.leftMargin = px(ctx, 3);
                dlp.rightMargin = px(ctx, 11);
                r.addView(dot, dlp);
            }

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView t = new TextView(ctx);
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            t.setText(text.main(i));
            t.setMaxLines(2);
            int tc = failed ? R.color.k2go_amber_text : (done || active ? R.color.k2go_ink : R.color.k2go_muted);
            t.setTextColor(ContextCompat.getColor(ctx, tc));
            col.addView(t);

            String sub = text.sub(i);
            if (sub != null && !sub.isEmpty()) {
                TextView s = new TextView(ctx);
                s.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                s.setText(sub);
                s.setTextColor(ContextCompat.getColor(ctx, failed ? R.color.k2go_amber_text : R.color.k2go_muted));
                col.addView(s);
            }
            r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (failed && onRetry != null) {
                TextView retry = new TextView(ctx);
                retry.setText(R.string.k2go_zim_retry);
                retry.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                retry.setTypeface(retry.getTypeface(), Typeface.BOLD);
                retry.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_teal));
                retry.setPadding(px(ctx, 12), px(ctx, 6), px(ctx, 12), px(ctx, 6));
                retry.setBackgroundResource(R.drawable.k2go_getmore_bg);
                retry.setClickable(true);
                retry.setOnClickListener(v -> onRetry.retry(idx));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                retryLp.leftMargin = px(ctx, 8);
                r.addView(retry, retryLp);
            }

            list.addView(r);
        }
    }

    private static int px(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
