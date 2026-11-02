package org.appdevforall.k2go.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedDispatcherOwner;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;

/**
 * The one pinned header for a Settings-area screen (K2GO-529): a back glyph plus the screen title
 * in a single top row, so every sub-screen shares one definition of the chrome instead of repeating
 * it by hand (five layouts plus one code-built screen used to each carry a copy).
 *
 * <p>XML: {@code <org.appdevforall.k2go.ui.K2GoHeaderView app:k2goTitle="@string/..."/>}. Code:
 * {@code new K2GoHeaderView(ctx)} then {@link #setTitle} for a title chosen at runtime. The back
 * glyph carries a contentDescription and meets the 48dp touch target, and by default dispatches
 * Back through the host's OnBackPressedDispatcher (an Activity, or a Fragment's Activity), which
 * pops a fragment or finishes the activity : the same effect each screen had before, defined once.
 *
 * <p>The title uses {@link K2GoText#display} (the one type scale); the glyph keeps a fixed 26sp, the
 * icon-glyph exception the type-scale guard records in its baseline.
 */
public final class K2GoHeaderView extends LinearLayout {

    private final TextView title;

    public K2GoHeaderView(Context c) { this(c, null); }

    public K2GoHeaderView(Context c, @Nullable AttributeSet attrs) {
        super(c, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(16);
        setPadding(pad, pad, pad, dp(8));

        TextView back = new TextView(c);
        back.setText("‹");                 // back chevron glyph
        back.setTextColor(ContextCompat.getColor(c, R.color.k2go_ink));
        back.setTextSize(26);                   // icon-glyph size (the guard's documented fixed-size exception)
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(dp(48));               // the 48dp accessibility touch target, glyph centred within it
        back.setMinHeight(dp(48));
        back.setContentDescription(c.getString(R.string.k2go_back));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> dispatchBack());
        addView(back);

        title = K2GoText.display(c, "", R.color.k2go_ink);
        addView(title);

        if (attrs != null) {
            TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.K2GoHeaderView);
            CharSequence t = a.getText(R.styleable.K2GoHeaderView_k2goTitle);
            if (t != null) title.setText(t);
            a.recycle();
        }
    }

    /** Set the screen title, for a screen whose title is chosen at runtime (otherwise use app:k2goTitle). */
    public void setTitle(CharSequence s) { title.setText(s); }

    // Walk the context chain to the Activity (a ComponentActivity is the OnBackPressedDispatcherOwner)
    // and ask it to go back : equivalent to requireActivity().getOnBackPressedDispatcher().onBackPressed().
    private void dispatchBack() {
        for (Context c = getContext(); c instanceof ContextWrapper; c = ((ContextWrapper) c).getBaseContext()) {
            if (c instanceof OnBackPressedDispatcherOwner) {
                ((OnBackPressedDispatcherOwner) c).getOnBackPressedDispatcher().onBackPressed();
                return;
            }
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
