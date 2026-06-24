/*
 * ============================================================================
 * Name        : GestureWebView.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : WebView subclass that guarantees multi-touch delivery (map tilt) + optional gesture logging.
 * ============================================================================
 */
package org.iiab.controller.portal.presentation;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.webkit.WebView;

/**
 * WebView that ensures multi-finger gestures (e.g. two-finger drag = map tilt in
 * MapLibre) reach the web content:
 *  - while 2+ pointers are down it asks ancestors NOT to intercept the gesture,
 *    so no parent scroll/swipe steals it;
 *  - optional debug logging reports pointer counts to logcat (TAG below), which —
 *    together with the page's console touch logging — pinpoints where a gesture is lost.
 */
public class GestureWebView extends WebView {

    public static final String TAG = "IIAB-GestureWV";

    private boolean gestureLogging = false;

    public GestureWebView(Context context) { super(context); }
    public GestureWebView(Context context, AttributeSet attrs) { super(context, attrs); }
    public GestureWebView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    /** Enable verbose touch logging (debug builds only). */
    public void setGestureLogging(boolean enabled) { this.gestureLogging = enabled; }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int pointers = event.getPointerCount();

        // Two or more fingers: keep the gesture for the web content (the map),
        // so an ancestor (pager/scroll) can't hijack a tilt/rotate/pinch.
        if (pointers >= 2) {
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }

        if (gestureLogging) {
            Log.d(TAG, "onTouchEvent action=" + event.getActionMasked() + " pointers=" + pointers);
        }

        return super.onTouchEvent(event);
    }
}
