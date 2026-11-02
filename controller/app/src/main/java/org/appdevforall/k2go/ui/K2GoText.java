package org.appdevforall.k2go.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;

/**
 * The one owner of the K2Go type scale for code-built UI (K2GO-529).
 *
 * <p>A call site asks for a scale step (display, title, body, label, caption) plus a colour token,
 * never for a literal size or a raw Material3 style. The five steps map to the
 * {@code TextAppearance.K2Go.*} styles in {@code res/values/themes_k2go.xml}, which is the single
 * source of the sizes and the Atkinson font.
 *
 * <p>{@link TextView#setTextAppearance(int)} resets colour and weight, so every method applies the
 * appearance first, then the colour, then the optional bold. Any other order drops the colour.
 *
 * <p>Contract and roles: {@code controller/docs/ADR-ui-type-scale-and-guard.md}. The build-time
 * guard ({@code ui-type-scale-guard.gradle}) fails the build when UI reaches past this kit for a
 * raw style, a non-remapped attribute, a literal size or a hardcoded colour.
 */
public final class K2GoText {
    private K2GoText() {}

    /** Style an existing TextView: appearance, then colour, then optional bold. Order matters. */
    public static void apply(TextView t, int k2goStyleRes, int colorRes, boolean bold) {
        t.setTextAppearance(k2goStyleRes);
        t.setTextColor(ContextCompat.getColor(t.getContext(), colorRes));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
    }

    private static TextView make(Context c, CharSequence s, int styleRes, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        apply(t, styleRes, colorRes, bold);
        return t;
    }

    /** 28sp display: the one screen title in a pinned header. The style is already bold. */
    public static TextView display(Context c, CharSequence s, int colorRes) {
        return make(c, s, R.style.TextAppearance_K2Go_Display, colorRes, false);
    }

    /** 18sp title: a card or item name, a value with emphasis. Pass bold for a name. */
    public static TextView title(Context c, CharSequence s, int colorRes, boolean bold) {
        return make(c, s, R.style.TextAppearance_K2Go_Title, colorRes, bold);
    }

    /** 16sp body: a screen intro, a row title, the "what it is" context line. */
    public static TextView body(Context c, CharSequence s, int colorRes) {
        return make(c, s, R.style.TextAppearance_K2Go_Body, colorRes, false);
    }

    /** 14sp label: supporting detail, a value, a hint, a field label. */
    public static TextView label(Context c, CharSequence s, int colorRes) {
        return make(c, s, R.style.TextAppearance_K2Go_Label, colorRes, false);
    }

    /** 12sp caption: RESERVED for one terse per-control helper. Not for a line that identifies an item. */
    public static TextView caption(Context c, CharSequence s, int colorRes) {
        return make(c, s, R.style.TextAppearance_K2Go_Caption, colorRes, false);
    }

    /**
     * A section or group header: the Label scale in teal, all-caps and tracked, so it reads as a
     * group label by weight and colour, not by being the smallest text on the screen.
     */
    public static TextView sectionLabel(Context c, CharSequence s) {
        TextView t = make(c, s, R.style.TextAppearance_K2Go_Label, R.color.k2go_teal, false);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        return t;
    }
}
