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
package org.iiab.controller.util;

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

public final class Snackbars {

    private Snackbars() {}

    /** Snackbar whose on-screen time scales with the text length. */
    public static Snackbar make(View view, CharSequence text) {
        String s = (text == null) ? null : text.toString();
        return Snackbar.make(view, text, SnackbarDuration.millisForText(s));
    }

    /** Overload for a string resource id. */
    public static Snackbar make(View view, int resId) {
        CharSequence text = view.getResources().getText(resId);
        return Snackbar.make(view, text, SnackbarDuration.millisForText(text.toString()));
    }
}
