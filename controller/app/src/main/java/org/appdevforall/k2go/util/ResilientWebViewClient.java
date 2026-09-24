/*
 * ============================================================================
 * Name        : ResilientWebViewClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-419. One containment line for WebView render-process death.
 * ============================================================================
 */
package org.appdevforall.k2go.util;

import android.os.Build;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RequiresApi;

/**
 * Base WebViewClient that keeps the app alive when a WebView render process dies.
 *
 * <p>A render process can die for reasons outside the app: system memory pressure, or a Chromium
 * internal abort (K2GO-419 saw the renderer crash inside libwebviewchromium.so, no app frames). If
 * no WebViewClient handles that death, the framework crashes the whole host app. See
 * https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)
 *
 * <p>This client handles it in one place: it detaches and destroys the dead WebView (the object is
 * unusable after a renderer death) and returns true, so the app survives, then hands the screen its
 * own recovery through {@link #onRendererGone(boolean)}. Subclass this instead of WebViewClient.
 *
 * <p>onRenderProcessGone exists since API 26. On API 24-25 the framework never calls it and the app
 * still dies there, which cannot be helped from the app side.
 */
public abstract class ResilientWebViewClient extends WebViewClient {

    private static final String TAG = "ResilientWebView";

    /**
     * Shared anti-loop window. When a second renderer death lands within this of a recovery, the
     * recovering screen must stop reloading and bail (leave/close), so a page that reliably kills the
     * renderer cannot spin an endless reload. One source for the value, used by every recovery path.
     */
    public static final long RECOVERY_WINDOW_MS = 15_000L;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public final boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        // didCrash()==false means the system reclaimed the renderer under memory pressure, not a bug.
        boolean crashed = detail != null && detail.didCrash();
        Log.w(TAG, "WebView renderer gone (didCrash=" + crashed + "); containing to keep the app alive");
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
        view.destroy();
        try {
            onRendererGone(crashed);
        } catch (Throwable t) {
            Log.e(TAG, "renderer recovery failed", t);
        }
        return true;   // handled: the framework must NOT crash the host app
    }

    /**
     * Recover the screen after the dead WebView has been detached and destroyed. Typical actions:
     * rebuild the screen and reload the same page, or dismiss a transient popup or dialog. The
     * crashed flag is false when the system evicted the renderer under memory pressure rather than a
     * real crash. Called on the main thread.
     */
    protected abstract void onRendererGone(boolean crashed);
}
