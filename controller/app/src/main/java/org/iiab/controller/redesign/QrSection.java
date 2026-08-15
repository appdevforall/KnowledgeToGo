/*
 * ============================================================================
 * Name        : QrSection.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One "scan this" section — a QR (or a placeholder) with a caption and a
 *               reveal-on-tap text fallback. Shared by the stacked-QR flows.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.iiab.controller.sync.transport.QrCodec;

/**
 * ADFA-5157: extracted from ConnectFragment (ADFA-5154), where it lived as a private
 * {@code Section} holder plus {@code setQr}/{@code setFallback}/{@code applyFallbackOpen}
 * helpers. Connect uses it twice (Join / Open) and once for the single Wi-Fi QR; the Clone
 * (Send) redesign needs the very same section, so it lives here as one definition instead of
 * being copied into a second fragment.
 *
 * <p>A section is bound from a root view plus the ids of its parts, so it works whether those
 * parts come from an {@code <include>} of {@code view_k2go_qr_section} or from inline markup.
 * It owns its own reveal state ({@link #fbOpen}) across re-renders. It holds no Context; the
 * few methods that need one take it as a parameter, so the section can be built in
 * {@code onCreateView} and driven from anywhere.
 */
public final class QrSection {
    public final FrameLayout frame;
    public final ImageView qr;
    public final TextView ph, caption, subCaption, fallbackToggle;
    public final LinearLayout fallback, fallbackValues;
    /** This section's fallback reveal state, kept across re-renders. */
    public boolean fbOpen;

    public QrSection(View r, int frameId, int qrId, int phId, int capId, int subId,
                     int toggleId, int fallbackId, int valuesId) {
        frame = r.findViewById(frameId);
        qr = r.findViewById(qrId);
        ph = r.findViewById(phId);
        caption = r.findViewById(capId);
        subCaption = r.findViewById(subId);
        fallbackToggle = r.findViewById(toggleId);
        fallback = r.findViewById(fallbackId);
        fallbackValues = r.findViewById(valuesId);
    }

    /** Draw {@code data} into the QR, or clear it and show {@code placeholder} in the slot. */
    public void setQr(Context ctx, String data, String placeholder) {
        if (data == null) {
            qr.setImageBitmap(null);
            ph.setText(placeholder == null ? "" : placeholder);
            ph.setVisibility(View.VISIBLE);
            return;
        }
        int px = Math.round(200 * ctx.getResources().getDisplayMetrics().density);
        qr.setImageBitmap(QrCodec.encode(data, px));
        ph.setVisibility(View.GONE);
    }

    /**
     * ADFA-4815: the text fallback is reveal-on-tap. The toggle sits under the caption; the quote
     * block with the values stays hidden until tapped, so a working scan stays clutter-free.
     */
    public void setFallback(Context ctx, String[] vals) {
        fallbackValues.removeAllViews();
        if (vals == null || vals.length == 0) {
            fallback.setVisibility(View.GONE);
            fallbackToggle.setVisibility(View.GONE);
            return;
        }
        for (String val : vals) {
            TextView t = new TextView(ctx);
            t.setText(val);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
            t.setTextIsSelectable(true);
            fallbackValues.addView(t);
        }
        fallbackToggle.setVisibility(View.VISIBLE);
        applyFallbackOpen(ctx);
        fallbackToggle.setOnClickListener(x -> { fbOpen = !fbOpen; applyFallbackOpen(ctx); });
    }

    private void applyFallbackOpen(Context ctx) {
        fallback.setVisibility(fbOpen ? View.VISIBLE : View.GONE);
        fallbackToggle.setText(fbOpen
                ? ctx.getString(R.string.k2go_hide) + "  ▴"
                : ctx.getString(R.string.k2go_scan_didnt_work) + "  ▸");
    }
}
