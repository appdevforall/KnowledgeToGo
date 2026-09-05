/*
 * ============================================================================
 * Name        : SegmentedTabs.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-385 (pill roles Q6, light variant). The Connect / Clone mode strips stay a
 *               two-segment teal strip (active = teal pill + on-teal text), but read as a
 *               single-select radio group to accessibility services: each tab is announced as a
 *               checkable RadioButton, checked when active, and marked selected. One definition
 *               instead of the identical paintTab that lived in both ConnectFragment and
 *               CloneFragment. Touch ripple comes from android:foreground on the tab in the layout.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.appdevforall.k2go.R;

public final class SegmentedTabs {

    private SegmentedTabs() {}

    /** Paint one segment of a two-way mode strip and expose it as a checked/unchecked radio. */
    public static void paint(TextView tab, boolean checked) {
        tab.setBackgroundResource(checked ? R.drawable.k2go_primary_bg : 0);
        tab.setTextColor(ContextCompat.getColor(tab.getContext(),
                checked ? R.color.k2go_on_teal : R.color.k2go_muted));
        tab.setSelected(checked);
        ViewCompat.setAccessibilityDelegate(tab, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
    }
}
