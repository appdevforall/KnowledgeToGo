/*
 * ============================================================================
 * Name        : Snackbars.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Snackbar factory that applies reading-time duration (ADFA-4579).
 *               Convention: use Snackbars.make(view, msg) instead of
 *               Snackbar.make(view, msg, LENGTH_SHORT/LONG) so long messages are
 *               not cut off. Duration comes from SnackbarDuration.millisForText.
 * ============================================================================
 */
package org.appdevforall.k2go.util;

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

public final class Snackbars {

    private Snackbars() {}

    /** Snackbar whose on-screen time scales with the text length. */
    public static Snackbar make(View view, CharSequence text) {
        String s = (text == null) ? null : text.toString();
        return fit(Snackbar.make(view, text, SnackbarDuration.millisForText(s)));
    }

    /** Overload for a string resource id. */
    public static Snackbar make(View view, int resId) {
        CharSequence text = view.getResources().getText(resId);
        return fit(Snackbar.make(view, text, SnackbarDuration.millisForText(text.toString())));
    }

    /** K2GO-588: the Material default of 2 lines cut long messages (and most translations). */
    private static Snackbar fit(Snackbar bar) {
        android.widget.TextView t = bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        if (t != null) t.setMaxLines(5);
        return bar;
    }
}
