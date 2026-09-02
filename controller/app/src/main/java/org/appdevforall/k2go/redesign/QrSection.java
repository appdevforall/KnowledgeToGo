/*
 * ============================================================================
 * Name        : QrSection.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One "scan this" section — a QR (or a placeholder) with a caption and a
 *               reveal-on-tap text fallback. Shared by the stacked-QR flows.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.sync.transport.QrCodec;
import org.appdevforall.k2go.util.EllipsisAnimator;
import org.appdevforall.k2go.util.M3Text;

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

    // K2GO-375: animated "…" for the placeholder while a value the QR needs is still resolving (e.g. the
    // hotspot AP IP). Lazily bound to `ph`; owned here so every stacked-QR flow gets the same treatment.
    // The owning fragment must stopPending() in onDestroyView so the Handler cannot tick a destroyed view.
    private EllipsisAnimator phDots;

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
        stopPending();   // K2GO-375: a real QR or a static placeholder ends any "resolving…" animation
        if (data == null) {
            qr.setImageBitmap(null);
            ph.setText(placeholder == null ? "" : placeholder);
            ph.setVisibility(View.VISIBLE);
            return;
        }
        // ADFA-5236: encode at the rendered QR size (single source of truth = @dimen/k2go_qr_size)
        // so the bitmap stays crisp at every smallest-width bucket instead of upscaling a fixed 200dp.
        int px = ctx.getResources().getDimensionPixelSize(R.dimen.k2go_qr_size);
        qr.setImageBitmap(QrCodec.encode(data, px));
        ph.setVisibility(View.GONE);
    }

    /**
     * K2GO-375: hold the slot with an animated "{@code label}…" instead of drawing a QR, for the window
     * where the QR's data is not resolvable yet (the hotspot AP interface has no IPv4 yet). Preferred over
     * a static placeholder or a QR pointing at a guessed address: it reads as "working", and the caller
     * redraws (setQr) the moment the value lands. Idempotent while the same label is pending.
     */
    public void setQrPending(Context ctx, String label) {
        qr.setImageBitmap(null);
        ph.setVisibility(View.VISIBLE);
        if (phDots == null) phDots = new EllipsisAnimator(ph, true);   // fixed-width: centered, no jiggle
        phDots.start(label);
    }

    /** Stop the pending animation, if any. Call from the owner's onDestroyView. Safe to call repeatedly. */
    public void stopPending() {
        if (phDots != null) phDots.stop();
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
        // ADFA-5236: each value is a rounded "field" chip laid out as [ content column | trailing Copy ].
        // Credential strings arrive as "Label: value" (split on the first ": ") -> muted label over a
        // mono value; a URL ("http://<ip>:8085") has no ": " -> lone mono value. Copy lives in a RESERVED
        // TRAILING slot (never floating over the value): an icon-only button for every value — the word
        // "Copy" would cap a long URL's autosize. The whole chip is also tappable. Share is NOT here.
        final int chipBg = com.google.android.material.color.MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest,
                ContextCompat.getColor(ctx, R.color.k2go_surface));
        boolean first = true;
        for (String val : vals) {
            int sep = val.indexOf(": ");
            final String label = sep > 0 ? val.substring(0, sep) : null;
            final String value = sep > 0 ? val.substring(sep + 2) : val;

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(ctx, 12));
            bg.setColor(chipBg);

            LinearLayout chip = new LinearLayout(ctx);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(bg);
            chip.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
            chip.setOnClickListener(v -> copyValue(ctx, value));   // whole field taps to copy

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            if (label != null) {
                TextView lt = new TextView(ctx);
                lt.setText(label);
                M3Text.apply(lt, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        ContextCompat.getColor(ctx, R.color.k2go_muted));
                col.addView(lt);
            }
            TextView vt = new TextView(ctx);
            vt.setText(value);
            vt.setGravity(Gravity.START);
            // ADFA-5183: readable M3 TitleLarge (M3Text re-applies the colour after the appearance,
            // ADFA-4961). ADFA-5236: monospace so digits vs letters can't be misread (0/O, 1/l).
            M3Text.apply(vt, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
                    ContextCompat.getColor(ctx, R.color.k2go_ink));
            vt.setTypeface(android.graphics.Typeface.MONOSPACE);
            // ADFA-5236: one line — a long URL/IP must not wrap. Autosize shrinks to fit (12sp floor);
            // short values keep the full size. Set AFTER the appearance so it takes over the sizing.
            vt.setMaxLines(1);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    vt, 12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            col.addView(vt, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            chip.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Trailing Copy: icon-only for EVERY value. The word "Copy" stole width from a long URL/IP
            // (it capped the autosize), so the URL uses the same icon-only slot as the creds.
            chip.addView(copyIconButton(ctx, value));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (!first) clp.topMargin = dp(ctx, 8);
            first = false;
            fallbackValues.addView(chip, clp);
        }
        fallback.setVisibility(View.VISIBLE);
    }

    /** ADFA-5236: icon-only Copy for short values (Wi-Fi, password). 48dp target, tooltip + a11y. */
    private ImageView copyIconButton(Context ctx, String value) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(R.drawable.ic_content_copy);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        CharSequence d = ctx.getString(android.R.string.copy);
        iv.setContentDescription(d);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(iv, d);
        iv.setBackground(ripple(ctx));
        iv.setOnClickListener(v -> copyValue(ctx, value));
        return withSize(iv, dp(ctx, 48), dp(ctx, 48));
    }

    /** ADFA-5236: copy the FULL value to the clipboard and confirm with a "Copied" snackbar. */
    private void copyValue(Context ctx, String value) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("K2Go", value));
        String msg = ctx.getString(R.string.k2go_copied);
        com.google.android.material.snackbar.Snackbar
                .make(fallback, msg, org.appdevforall.k2go.util.SnackbarDuration.millisForText(msg))
                .show();
    }

    private static <T extends View> T withSize(T v, int w, int h) {
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }

    private static android.graphics.drawable.Drawable ripple(Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getDrawable(ctx, tv.resourceId) : null;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
