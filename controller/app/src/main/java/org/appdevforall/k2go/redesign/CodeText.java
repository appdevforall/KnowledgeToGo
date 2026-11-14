/*
 * ============================================================================
 * Name        : CodeText.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The phone-to-phone code path shared by Clone and Content Exchange: scan the
 *               code, or move it as text (copy / share on the sender, paste on the receiver),
 *               and join the sender's Wi-Fi. Binds view_k2go_code_text / view_k2go_code_paste.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.util.Consumer;

import com.journeyapps.barcodescanner.ScanOptions;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.util.Snackbars;

public final class CodeText {

    private CodeText() {
    }

    /** Copy the payload to the clipboard and confirm with a snackbar on {@code anchor}. */
    public static void copy(View anchor, String payload) {
        ClipboardManager cm = (ClipboardManager) anchor.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("K2Go transfer code", payload));
        }
        Snackbars.make(anchor, R.string.k2go_clone_toast_code_copied).show();
    }

    /** Hand the payload to the Android share sheet (chat, email, a remote session). */
    public static void share(Context ctx, String payload) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload);
        ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.k2go_clone_chooser_send_code)));
    }

    /** The QR scanner setup both receivers use. */
    public static ScanOptions scanOptions(String prompt) {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt(prompt);
        o.setBeepEnabled(false);
        o.setBarcodeImageEnabled(false);
        o.setOrientationLocked(false);
        return o;
    }

    /** Open Android Wi-Fi settings (wireless settings as a fallback) to join the sender's network.
     *  Returns false when neither screen can open, so the caller can tell the user. */
    public static boolean openWifiSettings(Context ctx) {
        for (String action : new String[]{Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS}) {
            try {
                ctx.startActivity(new Intent(action));
                return true;
            } catch (Exception ignored) {
                // try the next screen
            }
        }
        return false;
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

    /**
     * The receiver's "Scan didn't work? Enter the code as text" block (view_k2go_code_paste): the link
     * toggles the paste field; "Use this code" hands the trimmed text to {@code onCode}, or asks for a
     * paste when the field is empty. The host decides when the block is shown; this owns the rest.
     */
    public static final class PasteBlock {
        private final TextView toggle;
        private final View block;
        private final EditText field;
        private boolean shown;
        private boolean expanded;

        public PasteBlock(View root, Consumer<String> onCode) {
            toggle = root.findViewById(R.id.k2go_code_showpaste);
            block = root.findViewById(R.id.k2go_code_pasteblock);
            field = root.findViewById(R.id.k2go_code_paste);
            toggle.setOnClickListener(v -> {
                expanded = !expanded;
                apply();
            });
            root.findViewById(R.id.k2go_code_use).setOnClickListener(v -> {
                String text = field.getText().toString().trim();
                if (text.isEmpty()) {
                    Snackbars.make(v, R.string.k2go_clone_toast_paste_first).show();
                    return;
                }
                onCode.accept(text);
            });
        }

        /** Show or hide the whole block (the link and, when open, the field). */
        public void show(boolean show) {
            shown = show;
            apply();
        }

        /** Close the field, for example when the flow starts over. */
        public void collapse() {
            expanded = false;
            apply();
        }

        public boolean isExpanded() {
            return expanded;
        }

        public void setExpanded(boolean open) {
            expanded = open;
            apply();
        }

        private void apply() {
            toggle.setVisibility(shown ? View.VISIBLE : View.GONE);
            setExpandChevron(toggle, expanded, R.color.k2go_teal);
            block.setVisibility(shown && expanded ? View.VISIBLE : View.GONE);
        }
    }
}
