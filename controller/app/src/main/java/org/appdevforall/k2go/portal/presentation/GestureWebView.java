/*
 * ============================================================================
 * Name        : GestureWebView.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : WebView subclass that guarantees multi-touch delivery (map tilt) + optional gesture logging.
 * ============================================================================
 */
package org.appdevforall.k2go.portal.presentation;

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
    private Runnable onUserInteraction;   // ADFA-4887: notified on each touch (nav-bar auto-hide)

    public GestureWebView(Context context) { super(context); }
    public GestureWebView(Context context, AttributeSet attrs) { super(context, attrs); }
    public GestureWebView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    /** Enable verbose touch logging (debug builds only). */
    public void setGestureLogging(boolean enabled) { this.gestureLogging = enabled; }

    /** Called on every touch so the host can reset UI timers (e.g. nav-bar auto-hide from last touch). */
    public void setOnUserInteraction(Runnable r) { this.onUserInteraction = r; }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int pointers = event.getPointerCount();

        // Reset the host's UI timers on touch start/end only (not every MOVE) so a pan/tilt doesn't
        // re-post the auto-hide dozens of times a second; ACTION_UP effectively marks the last touch.
        final int action = event.getActionMasked();
        if (onUserInteraction != null && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
            onUserInteraction.run();
        }

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
