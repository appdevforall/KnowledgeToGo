/*
 * ============================================================================
 * Name        : LiveLogPanel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-374. One reusable "Show details" live-log panel: a tappable header (label +
 *               rotating chevron) over a scrollable monospace terminal. Replaces the three hand-copied
 *               boxes (MapsPreparingFragment, ModuleInstallFragment, DashboardDetailFragment), which each
 *               re-scrolled to the bottom on every update and so fought a user who had scrolled up. The
 *               data source stays with each consumer; this view owns only presentation, the toggle, and
 *               a smart auto-scroll (pin to the bottom only when already there — see LogScroll).
 * ============================================================================
 */
package org.appdevforall.k2go.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;

/**
 * Usable from XML ({@code <org.appdevforall.k2go.widget.LiveLogPanel .../>}) or code. Feed it with
 * {@link #setContent(CharSequence)} each time the log changes; expand/collapse is handled internally,
 * and {@link #setOnExpandListener(OnExpandListener)} lets a consumer seed the log the moment it opens.
 *
 * <p>XML attrs: {@code app:logMaxHeightDp} (log area height, default 180) and
 * {@code app:hideUntilContent} (keep the toggle hidden until there is content, default false).
 */
public class LiveLogPanel extends LinearLayout {

    /** Fired when the panel is expanded — the consumer seeds/refreshes its content here. */
    public interface OnExpandListener { void onExpand(); }

    private TextView label;
    private ImageView chevron;
    private ScrollView scroll;
    private TextView logText;
    private LinearLayout header;

    private boolean expanded;
    private boolean hideUntilContent;
    private int autoScrollThresholdPx;
    @Nullable private OnExpandListener onExpandListener;

    public LiveLogPanel(Context context) { super(context); init(context, null); }
    public LiveLogPanel(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(context, attrs); }
    public LiveLogPanel(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context c, @Nullable AttributeSet attrs) {
        setOrientation(VERTICAL);
        final float d = getResources().getDisplayMetrics().density;

        int logMaxHeightDp = 180;
        hideUntilContent = false;
        if (attrs != null) {
            TypedArray ta = c.obtainStyledAttributes(attrs, R.styleable.LiveLogPanel);
            logMaxHeightDp = ta.getInt(R.styleable.LiveLogPanel_logMaxHeightDp, logMaxHeightDp);
            hideUntilContent = ta.getBoolean(R.styleable.LiveLogPanel_hideUntilContent, false);
            ta.recycle();
        }
        autoScrollThresholdPx = Math.round(24 * d);

        // Header: label + rotating chevron, the whole row toggles.
        header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        header.setClickable(true);
        header.setFocusable(true);
        TypedValue bg = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true);
        header.setBackgroundResource(bg.resourceId);
        int hpad = Math.round(10 * d);
        header.setPadding(hpad, hpad, hpad, hpad);
        header.setOnClickListener(v -> toggle());

        label = new TextView(c);
        label.setText(R.string.k2go_maps_log_show);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        label.setTextColor(ContextCompat.getColor(c, R.color.k2go_teal));
        header.addView(label);

        chevron = new ImageView(c);
        int cs = Math.round(20 * d);
        LayoutParams chevLp = new LayoutParams(cs, cs);
        chevLp.leftMargin = Math.round(4 * d);
        chevron.setLayoutParams(chevLp);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setColorFilter(ContextCompat.getColor(c, R.color.k2go_teal));
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(chevron);

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (hideUntilContent) header.setVisibility(GONE);

        // Scrollable monospace terminal, collapsed by default. Override dispatchTouchEvent so a touch-
        // down inside the terminal tells the outer page ScrollView not to intercept — this runs BEFORE
        // the child TextView, so it works even though the selectable text consumes the DOWN (that was
        // why a plain OnTouchListener never fired and the page scrolled instead). A touch OUTSIDE the
        // terminal reaches the page directly, so the page still scrolls there.
        scroll = new ScrollView(c) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        LayoutParams scrollLp = new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(logMaxHeightDp * d));
        scrollLp.topMargin = Math.round(8 * d);
        scroll.setLayoutParams(scrollLp);
        int p = Math.round(10 * d);
        scroll.setPadding(p, p, p, p);
        scroll.setBackgroundColor(ContextCompat.getColor(c, R.color.k2go_terminal_bg));
        // K2GO-374: keep the scrollbar visible as a position indicator — with the auto-scroll now
        // pinning only when at the bottom, a persistent bar shows the reader where they are in the log.
        // Tint the thumb with the terminal text colour so it reads on the dark background.
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setScrollBarSize(Math.round(3 * d));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
            thumb.setColor(ContextCompat.getColor(c, R.color.k2go_terminal_text));
            thumb.setCornerRadius(Math.round(2 * d));
            scroll.setVerticalScrollbarThumbDrawable(thumb);
        }
        scroll.setVisibility(GONE);

        logText = new TextView(c);
        logText.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logText.setTextColor(ContextCompat.getColor(c, R.color.k2go_terminal_text));
        logText.setTextIsSelectable(true);
        scroll.addView(logText);
        addView(scroll);
    }

    /** Replace the shown log text. Reveals the toggle once there is content (when {@code hideUntilContent});
     *  while expanded, keeps the view pinned to the bottom only if the user was already there. */
    public void setContent(@Nullable CharSequence content) {
        // Measure "was at bottom" against the OLD content, before it is replaced.
        boolean wasAtBottom = LogScroll.isAtBottom(
                scroll.getScrollY(), scroll.getHeight(), logText.getHeight(), autoScrollThresholdPx);
        logText.setText(content);
        if (hideUntilContent && header.getVisibility() != VISIBLE
                && content != null && content.length() > 0) {
            header.setVisibility(VISIBLE);
        }
        if (expanded && wasAtBottom) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    public boolean isExpanded() { return expanded; }

    /** Expand/collapse. On expand, fires {@link OnExpandListener} (seed point) and jumps to the bottom. */
    public void setExpanded(boolean value) {
        expanded = value;
        label.setText(value ? R.string.k2go_maps_log_hide : R.string.k2go_maps_log_show);
        chevron.setRotation(value ? 90f : 0f);
        scroll.setVisibility(value ? VISIBLE : GONE);
        if (value) {
            if (onExpandListener != null) onExpandListener.onExpand();
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    public void toggle() { setExpanded(!expanded); }

    public void setOnExpandListener(@Nullable OnExpandListener l) { onExpandListener = l; }

    /** Programmatic equivalent of the {@code hideUntilContent} XML attr (for consumers built in code):
     *  when true the toggle stays hidden until there is content; when false it is always shown. */
    public void setHideUntilContent(boolean value) {
        hideUntilContent = value;
        if (value && logText.getText().length() == 0) {
            header.setVisibility(GONE);
        } else if (!value) {
            header.setVisibility(VISIBLE);
        }
    }

    /** Collapse, clear the text, and (when {@code hideUntilContent}) hide the toggle again — so a panel
     *  reused across runs starts clean instead of showing the previous run's log. */
    public void reset() {
        setExpanded(false);
        logText.setText("");
        if (hideUntilContent) header.setVisibility(GONE);
    }
}
