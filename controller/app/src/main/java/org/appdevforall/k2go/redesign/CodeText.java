/*
 * ============================================================================
 * Name        : CodeText.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The "code as text" path shared by Clone and Content Exchange: copy or share the
 *               sender's QR payload, and the expand chevron of the receiver's paste link.
 *               Binds the views of view_k2go_code_text / view_k2go_code_paste.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.snackbar.Snackbar;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.util.SnackbarDuration;

public final class CodeText {

    private CodeText() {
    }

    /** Copy the payload to the clipboard and confirm with a snackbar on {@code anchor}. */
    public static void copy(View anchor, String payload) {
        Context ctx = anchor.getContext();
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("K2Go transfer code", payload));
        }
        String msg = ctx.getString(R.string.k2go_clone_toast_code_copied);
        Snackbar.make(anchor, msg, SnackbarDuration.millisForText(msg)).show();
    }

    /** Hand the payload to the Android share sheet (chat, email, a remote session). */
    public static void share(Context ctx, String payload) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload);
        ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.k2go_clone_chooser_send_code)));
    }

    /** ADFA-5304: Material 3 expand/collapse affordance (the paste link and Clone's other disclosure
     *  rows): one trailing icon, expand_more when closed and expand_less when open, tinted to the
     *  row's role colour. */
    public static void setExpandChevron(TextView tv, boolean open, int colorRes) {
        Context ctx = tv.getContext();
        Drawable d = ContextCompat.getDrawable(ctx, open ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        if (d != null) {
            d = d.mutate();
            DrawableCompat.setTint(d, ContextCompat.getColor(ctx, colorRes));
        }
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, d, null);
        tv.setCompoundDrawablePadding((int) (6 * ctx.getResources().getDisplayMetrics().density));
    }
}
