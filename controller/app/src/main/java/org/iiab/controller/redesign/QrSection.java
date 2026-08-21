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
        // ADFA-5236: each value sits in its own rounded "field" chip. Credential strings arrive as
        // "Label: value" (e.g. "Wi-Fi: AndroidShare_4464"); we split on the first ": " to show the
        // label muted and the value in mono. A URL ("http://<ip>:8085") has no ": " so it renders as a
        // lone mono value. Chip fill is an M3 surface-container tone so it stands off the block.
        final int chipBg = com.google.android.material.color.MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest,
                ContextCompat.getColor(ctx, R.color.k2go_surface));
        boolean first = true;
        for (String val : vals) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(ctx, 12));
            bg.setColor(chipBg);

            LinearLayout chip = new LinearLayout(ctx);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(bg);
            chip.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));

            int sep = val.indexOf(": ");
            String label = sep > 0 ? val.substring(0, sep) : null;
            String value = sep > 0 ? val.substring(sep + 2) : val;

            if (label != null) {
                TextView lt = new TextView(ctx);
                lt.setText(label);
                M3Text.apply(lt, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        ContextCompat.getColor(ctx, R.color.k2go_muted));
                chip.addView(lt);
            }

            TextView vt = new TextView(ctx);
            vt.setText(value);
            vt.setGravity(label != null ? Gravity.END : Gravity.CENTER);
            // ADFA-5183: readable size on the M3 TitleLarge role (M3Text re-applies the colour after
            // the appearance, per ADFA-4961). ADFA-5236: monospace so digits vs letters can't be
            // misread (0/O, 1/l) when typing into another phone.
            M3Text.apply(vt, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
                    ContextCompat.getColor(ctx, R.color.k2go_ink));
            vt.setTypeface(android.graphics.Typeface.MONOSPACE);
            vt.setTextIsSelectable(true);
            // ADFA-5236: keep it on ONE line — a long URL/IP must not wrap. Autosize shrinks to fit the
            // value column (down to a 12sp floor); short values keep the full size. Set AFTER the
            // appearance so autosize takes over the sizing; width 0 + weight 1 gives it a bound.
            vt.setMaxLines(1);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    vt, 12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (label != null) vlp.setMarginStart(dp(ctx, 12));
            chip.addView(vt, vlp);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (!first) clp.topMargin = dp(ctx, 8);
            first = false;
            fallbackValues.addView(chip, clp);
        }
        fallback.setVisibility(View.VISIBLE);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
