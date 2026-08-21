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
import org.iiab.controller.util.M3Text;

/**
 * ADFA-5157: extracted from ConnectFragment (ADFA-5154), where it lived as a private
 * {@code Section} holder plus {@code setQr}/{@code setFallback}/{@code applyFallbackOpen}
 * helpers. Connect uses it twice (Join / Open) and once for the single Wi-Fi QR; the Clone
 * (Send) redesign needs the very same section, so it lives here as one definition instead of
 * being copied into a second fragment.
 *
 * <p>A section is bound from a root view plus the ids of its parts, so it works whether those
 * parts come from an {@code <include>} of {@code view_k2go_qr_section} or from inline markup.
 * The manual-access values are always shown (ADFA-5236; no reveal toggle). It holds no Context; the
 * few methods that need one take it as a parameter, so the section can be built in
 * {@code onCreateView} and driven from anywhere.
 */
public final class QrSection {
    public final FrameLayout frame;
    public final ImageView qr;
    public final TextView ph, caption, subCaption, fallbackToggle;
    public final LinearLayout fallback, fallbackValues;

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
     * ADFA-5236: the manual-access block (hotspot Wi-Fi + password, or the library URL) is shown
     * ALWAYS, not behind a reveal-on-tap toggle. Many phones have no QR scanner baked into the camera
     * and offline users can't download one, so the credentials/URL must be visible without a tap. The
     * caption above says what this is (".. or use the following credentials instead:"). The old
     * "Scan didn't work?" / "Hide" toggle is gone.
     */
    public void setFallback(Context ctx, String[] vals) {
        fallbackToggle.setVisibility(View.GONE);   // ADFA-5236: no reveal toggle anymore
        fallbackValues.removeAllViews();
        if (vals == null || vals.length == 0) {
            fallback.setVisibility(View.GONE);
            return;
        }
        for (String val : vals) {
            TextView t = new TextView(ctx);
            t.setText(val);
            t.setGravity(Gravity.CENTER);
            // ADFA-5183: readable size — the value is READ AND TYPED (server URL, hotspot Wi-Fi name +
            // password). Put it on the M3 TitleLarge role via M3Text (which re-applies the theme colour
            // after the appearance, per ADFA-4961) instead of a fixed sp.
            M3Text.apply(t, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
                    ContextCompat.getColor(ctx, R.color.k2go_ink));
            // ADFA-5236: monospace so a run of digits and letters (password, URL) can't be misread —
            // 0 vs O, 1 vs l — which matters when someone is typing it into another phone.
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setTextIsSelectable(true);
            // ADFA-5236: keep the value on ONE line — a long URL/IP must not wrap. Autosize shrinks it
            // to fit the card width (down to a still-legible 12sp floor); short values keep the full
            // TitleLarge size. Needs match_parent width so autosize has a bound to shrink into, and it
            // is set AFTER the appearance so it takes over the sizing (a fixed size would disable it).
            t.setMaxLines(1);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    t, 12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            fallbackValues.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        fallback.setVisibility(View.VISIBLE);
    }
}
