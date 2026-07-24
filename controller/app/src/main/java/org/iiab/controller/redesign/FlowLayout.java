/*
 * ============================================================================
 * Name        : FlowLayout.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848. Minimal wrapping container: lays children left-to-right and
 *               wraps to the next row when they don't fit the width — so segmented pills
 *               that overflow on a small screen wrap to 2+ rows instead of being clipped
 *               (or hidden behind a non-obvious horizontal scroll). No external dependency.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public class FlowLayout extends ViewGroup {

    private final int hGap;
    private final int vGap;

    public FlowLayout(Context c, int hGap, int vGap) {
        super(c);
        this.hGap = hGap;
        this.vGap = vGap;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int avail = width - getPaddingLeft() - getPaddingRight();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChild(ch, childWidthSpec, childHeightSpec);
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();
            if (x > 0 && x + cw > avail) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            x += cw + hGap;
            rowH = Math.max(rowH, chh);
        }
        int totalH = getPaddingTop() + getPaddingBottom() + y + rowH;
        setMeasuredDimension(width, resolveSize(totalH, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left = getPaddingLeft();
        int avail = getWidth() - left - getPaddingRight();
        int x = left, y = getPaddingTop(), rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();
            if (x > left && x + cw > left + avail) {
                x = left;
                y += rowH + vGap;
                rowH = 0;
            }
            ch.layout(x, y, x + cw, y + chh);
            x += cw + hGap;
            rowH = Math.max(rowH, chh);
        }
    }
}
