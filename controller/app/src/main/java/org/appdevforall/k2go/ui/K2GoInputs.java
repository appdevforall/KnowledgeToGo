/*
 * ============================================================================
 * Name        : K2GoInputs.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Text fields of the Settings forms (DNS, Hotspot, Authentication): the boxed input,
 *               its label row, and the "show password" eye.
 * ============================================================================
 */
package org.appdevforall.k2go.ui;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;

public final class K2GoInputs {
    private K2GoInputs() {}

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** A label (teal) with an optional muted hint on the right. */
    public static View fieldLabel(Context ctx, String label, String hintRight) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = K2GoText.label(ctx, label, R.color.k2go_teal);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(l);
        row.addView(K2GoText.label(ctx, hintRight, R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 16);
        row.setLayoutParams(lp);
        return row;
    }

    public static EditText textInput(Context ctx) {
        EditText e = new EditText(ctx);
        e.setBackgroundResource(R.drawable.k2go_lang_box_bg);
        int p = dp(ctx, 14);
        e.setPadding(p, p, p, p);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        e.setHintTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        e.setLayoutParams(lp);
        return e;
    }

    /** A password field: hidden text plus the eye to show it. */
    public static EditText passwordInput(Context ctx) {
        EditText e = textInput(ctx);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        attachEyeToggle(e);
        return e;
    }

    /** Wire the Material "visibility" eye as the end drawable inside a password field (tap to toggle).
     *  The icons ship white-filled (repo convention) and are tinted to the muted colour at usage. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public static void attachEyeToggle(final EditText field) {
        final android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(field.getContext(), R.color.k2go_muted));
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.outline_visibility_24, 0);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(field, tint);
        field.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            android.graphics.drawable.Drawable d = field.getCompoundDrawablesRelative()[2];
            if (d == null) return false;
            int dw = d.getBounds().width();
            // The end drawable sits on the right in LTR and on the left in RTL; hit-test the correct edge.
            boolean rtl = field.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean hit = rtl
                    ? ev.getX() <= field.getPaddingStart() + dw
                    : ev.getX() >= field.getWidth() - field.getPaddingEnd() - dw;
            if (!hit) return false;
            boolean hidden = field.getTransformationMethod() != null;
            if (hidden) {
                field.setTransformationMethod(null);
                field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.outline_visibility_off_24, 0);
            } else {
                field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.outline_visibility_24, 0);
            }
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(field, tint);
            field.setSelection(field.getText().length());
            v.performClick();
            return true;
        });
    }
}
