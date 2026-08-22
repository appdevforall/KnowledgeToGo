/*
 * ============================================================================
 * Name        : PortalActivity.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Webview portal activity
 * ============================================================================
 */
package org.iiab.controller;

import org.iiab.controller.config.BoxEndpoints;

import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;


import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.iiab.controller.portal.domain.NavigationPolicy;
import org.iiab.controller.portal.domain.PdfPolicy;
import org.iiab.controller.portal.domain.PdfViewerUrl;
import org.iiab.controller.portal.domain.PdfViewerBuild;
import org.iiab.controller.portal.domain.PdfViewerRouter;
import org.iiab.controller.portal.domain.WebViewVersion;
import org.iiab.controller.portal.data.PdfViewerCatalog;
import org.iiab.controller.util.AppExecutors;
import java.util.Collections;
import java.util.List;
import org.iiab.controller.portal.presentation.GestureWebView;
import org.iiab.controller.portal.presentation.PortalViewModel;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.URLUtil;
import android.widget.Toast;

public class PortalActivity extends AppCompatActivity {
    private static final String TAG = "IIAB-Portal";

    /**
     * Injected after each page load. (1) logs touch-point counts the web content
     * receives (captured via onConsoleMessage -> logcat) so a lost multi-finger
     * gesture can be diagnosed; (2) best-effort enables MapLibre two-finger pitch
     * if a map instance is exposed on the page.
     */
    private static final String TOUCH_PROBE_JS =
            "(function(){if(window.__iiabTouchProbe)return;window.__iiabTouchProbe=true;" +
            "['touchstart','touchmove'].forEach(function(t){document.addEventListener(t,function(e){" +
            "try{console.log('IIAB-TOUCH '+t+' touches='+(e.touches?e.touches.length:0));}catch(_){}}," +
            "{passive:true,capture:true});});" +
            "try{var m=window.map||window.__map||(window.maplibregl&&window.maplibregl.__map);" +
            "if(m&&m.touchPitch&&m.touchPitch.enable){m.touchPitch.enable();" +
            "if(m.touchZoomRotate&&m.touchZoomRotate.enable){m.touchZoomRotate.enable();}" +
            "console.log('IIAB-TOUCH pitch-enabled');}else{console.log('IIAB-TOUCH no-map-instance');}}" +
            "catch(err){console.log('IIAB-TOUCH pitch-error '+err);}})();";

    private GestureWebView webView;
    private org.iiab.controller.redesign.FqrController fqr;   // ADFA-4879: FQR maps (only on /maps/)
    private org.iiab.controller.redesign.KiwixManageController kiwixMgr;   // ADFA-5004: ZIM delete (only on /kiwix/)
    private static final long AUTO_HIDE_MS = 4000L;   // ADFA-4887: nav-bar auto-hide after inactivity
    private boolean fullscreenOn = false;             // ADFA-4887: Home button toggles fullscreen
    private Handler hideHandler;                      // ADFA-4887: nav-bar auto-hide (cleared in onDestroy)
    private Runnable hideRunnable;
    // pdf.js builds advertised by /pdfjs/manifest.json (loaded off the main thread).
    // Empty until loaded / when the box serves none -> PDFs fall back to download.
    private volatile List<PdfViewerBuild> pdfViewerBuilds = Collections.emptyList();
    private PortalViewModel vm;
    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 100;

    @Override
    protected void onResume() {
        super.onResume();
        // ADFA-5051 follow-up: if the dashboard was rebuilt to a NEW version while the app stayed
        // alive, its served JS/HTML changed under the WebView — clear the cache once and reload so we
        // don't keep running the old copy. The version comes from a rootfs file read (local,
        // offline-safe; no network, no proot); onResume is hot, so do the read OFF the main thread and
        // only touch the WebView back on the UI thread. onCreate sets the baseline synchronously, so
        // this never fires spuriously on the first resume.
        AppExecutors.get().io().execute(() -> {
            final String v = org.iiab.controller.redesign.DashboardVersion.installed(this);
            if (v == null || v.isEmpty()
                    || v.equals(org.iiab.controller.redesign.UpdateStatusCache.cacheClearedVersion(this))) {
                return;
            }
            runOnUiThread(() -> {
                if (webView == null) return;
                webView.clearCache(true);
                org.iiab.controller.redesign.UpdateStatusCache.setCacheClearedVersion(this, v);
                if (webView.getUrl() != null) webView.reload();
            });
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_portal);
        org.iiab.controller.help.TooltipWiring.wireAll(getWindow().getDecorView());

        vm = new ViewModelProvider(this).get(PortalViewModel.class);

        // 1. Basic WebView configuration
        webView = findViewById(R.id.myWebView);
        // ADFA-5051 follow-up: start each cold launch with a clean WebView cache so a stale copy of the
        // box's served JS (e.g. the /maps/ page that drives the FQR controls) can't break in-page
        // features — offline users can't just know to tap Reload. The box is on the local hotspot, so
        // refetching is cheap. Record the installed dashboard version as the baseline for onResume.
        webView.clearCache(true);
        org.iiab.controller.redesign.UpdateStatusCache.setCacheClearedVersion(
                this, org.iiab.controller.redesign.DashboardVersion.installed(this));
        // Learn which pdf.js builds the box serves so we can route PDFs per WebView version.
        AppExecutors.get().io().execute(() -> pdfViewerBuilds = PdfViewerCatalog.fetch());
        webView.setGestureLogging(BuildConfig.DEBUG);

        LinearLayout bottomNav = findViewById(R.id.bottomNav);
        ImageButton btnHandle = findViewById(R.id.btnHandle); // The new handle
        ImageButton btnHideNav = findViewById(R.id.btnHideNav); // Button to close

        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnHome = findViewById(R.id.btnHome);
        ImageButton btnReload = findViewById(R.id.btnReload);
        ImageButton btnExit = findViewById(R.id.btnExit);
        ImageButton btnForward = findViewById(R.id.btnForward);

        // --- NAV BAR (auto-hide after inactivity) ---
        // Visible on entry; hides after AUTO_HIDE_MS with no interaction, measured from the LAST
        // touch. The handle (⌃) brings it back; btnHideNav hides it manually.
        bottomNav.post(() -> {
            bottomNav.setTranslationY(0);
            bottomNav.setVisibility(View.VISIBLE);
        });
        btnHandle.setVisibility(View.GONE);

        hideHandler = new Handler(Looper.getMainLooper());

        hideRunnable = () -> {
            bottomNav.animate().translationY(bottomNav.getHeight()).setDuration(250);
            btnHandle.setVisibility(View.VISIBLE);
            btnHandle.animate().alpha(1f).setDuration(150);
        };

        // Reschedule the hide on every interaction -> auto-hide from the LAST touch, not the first.
        Runnable resetTimer = () -> {
            hideHandler.removeCallbacks(hideRunnable);
            hideHandler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        };
        webView.setOnUserInteraction(resetTimer);   // content touches count as interaction too
        resetTimer.run();                            // start the initial countdown

        // --- HANDLE LOGIC (Show Bar) ---
        btnHandle.setOnClickListener(v -> {
            btnHandle.animate().alpha(0f).setDuration(150).withEndAction(() -> btnHandle.setVisibility(View.GONE));
            bottomNav.animate().translationY(0).setDuration(250);
            resetTimer.run();
        });

        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
            resetTimer.run();
        });

        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
            resetTimer.run();
        });

        // Resolve the target URL once (domain), surviving rotation via the ViewModel.
        final String finalTargetUrl = vm.targetUrl(getIntent().getStringExtra("TARGET_URL"));

        // ADFA-4887: there is no in-WebView home page anymore, so Home is a Fullscreen toggle.
        // Enters full immersive (system bars hidden) and hides our bar too — but the bar stays
        // recoverable via the handle. Press again to exit.
        btnHome.setOnClickListener(v -> {
            fullscreenOn = !fullscreenOn;
            androidx.core.view.WindowInsetsControllerCompat wic =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (fullscreenOn) {
                wic.setSystemBarsBehavior(
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                hideRunnable.run();   // hide our bar too (still recoverable via the handle)
            } else {
                wic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                btnHandle.animate().alpha(0f).setDuration(150).withEndAction(() -> btnHandle.setVisibility(View.GONE));
                bottomNav.animate().translationY(0).setDuration(250);
            }
            resetTimer.run();
        });

        // Dual logic: Forced reload or Stop
        btnReload.setOnClickListener(v -> {
            if (vm.isLoading()) {
                webView.stopLoading();
            } else {
                webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
                webView.clearCache(true);
                webView.reload();
            }
            resetTimer.run();
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();

                // Internal server link stays in the WebView (and travels through the proxy).
                if (NavigationPolicy.isInternalHost(host)) {
                    if (fqr != null) fqr.prepareForUrl(url);   // ADFA-4879: add the FQR bridge only on /maps/
                    return false;
                }

                // External link: hand to the system browser / appropriate app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception e) {
                    Log.e(TAG, "No app installed to open: " + url);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                vm.setLoading(true);
                btnReload.setImageResource(R.drawable.ic_stop); // Change to Stop
                btnReload.setContentDescription(getString(R.string.k2go_nav_stop));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                vm.setLoading(false);
                btnReload.setImageResource(R.drawable.ic_refresh); // Back to Reload
                btnReload.setContentDescription(getString(R.string.k2go_nav_reload));
                view.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

                // Touch diagnostics + best-effort MapLibre pitch enablement.
                view.evaluateJavascript(TOUCH_PROBE_JS, null);

                // ADFA-4879: arm/disarm in-app FQR maps depending on whether this is /maps/.
                if (fqr != null) fqr.onPageFinished(url);
                // ADFA-5004: arm/disarm in-app ZIM manager depending on whether this is /kiwix/.
                if (kiwixMgr != null) kiwixMgr.onPageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String customErrorHtml = "<html><body style='background-color:#1A1A1A;color:#FFFFFF;text-align:center;padding-top:50px;font-family:sans-serif;'>"
                            + "<h2>⚠️ " + getString(R.string.k2go_portal_error_title) + "</h2>"
                            + "<p>" + getString(R.string.k2go_portal_error_body) + "</p>"
                            + "<p style='color:#888;font-size:12px;'>"
                            + getString(R.string.k2go_portal_error_detail, error.getDescription()) + "</p>"
                            + "</body></html>";
                    view.loadData(customErrorHtml, "text/html", "UTF-8");
                    vm.setLoading(false);
                    btnReload.setImageResource(R.drawable.ic_refresh);
                    btnReload.setContentDescription(getString(R.string.k2go_nav_reload));
                }
            }
        });

        // --- MANUALLY CLOSE BAR LOGIC ---
        btnHideNav.setOnClickListener(v -> {
            hideHandler.removeCallbacks(hideRunnable);
            hideRunnable.run();
        });

        btnExit.setOnClickListener(v -> finish());

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<android.net.Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (PortalActivity.this.filePathCallback != null) {
                    PortalActivity.this.filePathCallback.onReceiveValue(null);
                }
                PortalActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                } catch (android.content.ActivityNotFoundException e) {
                    PortalActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                // Surfaces in-page diagnostics (incl. IIAB-TOUCH probes) to logcat.
                Log.d(TAG, "WebConsole: " + consoleMessage.message());
                return true;
            }
        });

        // Downloads (ADFA-4512): a WebView never downloads on its own. Navigation routing
        // above is unchanged (internal host stays in-view, external -> system browser);
        // this listener ONLY fires for downloadable files. Files served by the local box are
        // handed to the system DownloadManager (APKs via the installer flow, anything else as
        // a plain download, ADFA-4710); downloads from an external host are left untouched.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String lastSeg = uri.getLastPathSegment();

            // PDFs served by the local box (ADFA-4708): the Android WebView has no built-in
            // PDF viewer. Pick the pdf.js build this device's WebView can run (from the served
            // manifest) and open it in-view; if none qualifies (very old WebView or no build
            // served) fall back to downloading the PDF. Non-PDFs fall through to the APK path.
            if (NavigationPolicy.isInternalHost(host) && PdfPolicy.isPdf(url, mimetype, contentDisposition)) {
                PdfViewerBuild build =
                        PdfViewerRouter.pick(WebViewVersion.chromeMajor(userAgent), pdfViewerBuilds);
                if (build != null) {
                    String viewerUrl = PdfViewerUrl.forPdf(BoxEndpoints.BASE + build.getViewerPath(), url);
                    if (viewerUrl != null) {
                        Log.d(TAG, "PDF routed to pdf.js build '" + build.getId() + "': " + url);
                        webView.loadUrl(viewerUrl);
                        return;
                    }
                }
                Log.d(TAG, "No compatible pdf.js build for this WebView; downloading PDF: " + url);
                downloadServedFile(uri, contentDisposition, mimetype);
                return;
            }
            // Only files served by the local box are downloaded here; external downloads are
            // left untouched (external navigation already opens in the system browser).
            if (!NavigationPolicy.isInternalHost(host)) {
                Log.d(TAG, "Download ignored (external host): " + url);
                return;
            }
            boolean looksApk = "application/vnd.android.package-archive".equalsIgnoreCase(mimetype)
                    || (lastSeg != null && lastSeg.toLowerCase().endsWith(".apk"))
                    || (contentDisposition != null && contentDisposition.toLowerCase().contains(".apk"));
            if (looksApk) {
                // APK: keep the installer flow (system "install unknown apps" consent).
                downloadServedApk(uri, contentDisposition, mimetype);
            } else {
                // Any other box file (archives, docs, ...): plain system download.
                downloadServedFile(uri, contentDisposition, mimetype);
            }
        });

        // ADFA-4879: FQR maps live inside this shared WebView but activate only on the box's
        // /maps/ page (gated in FqrController#onPageFinished). Attach the bridge before loadUrl.
        fqr = new org.iiab.controller.redesign.FqrController(this, webView);
        fqr.prepareForUrl(finalTargetUrl);

        // ADFA-5004: ZIM manager lives in this same shared WebView but activates only on /kiwix/
        // (gated in KiwixManageController#onPageFinished).
        kiwixMgr = new org.iiab.controller.redesign.KiwixManageController(this, webView);

        // ADFA-5043: Books (Calibre-Web) / Courses (Kolibri) auto-login as box admin — fetch a session
        // cookie, inject it into the WebView CookieManager, THEN load, so the card opens already
        // authenticated. Degrades gracefully: if the service isn't installed/ready, just load without it.
        String authService = getIntent().getStringExtra("AUTH_SERVICE");
        if (authService != null && !authService.isEmpty()) {
            autoLoginThenLoad(authService, finalTargetUrl);
        } else {
            // Native architecture: content is served locally; load it directly.
            webView.loadUrl(finalTargetUrl);
        }
    }

    /** ADFA-5043: get an admin session cookie for the service, inject it, then load the page. On any
     *  failure (service absent/not ready) load without a cookie — the card still opens. */
    private void autoLoginThenLoad(String service, String targetUrl) {
        showAuthOverlay();
        org.iiab.controller.redesign.AuthClient.session(service, new org.iiab.controller.redesign.AuthClient.SessionCb() {
            @Override public void onOk(String cookie) {
                if (isFinishing() || isDestroyed()) return;   // ADFA-5043: left mid-sign-in; don't touch dead views
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                cm.setAcceptCookie(true);
                // Host-wide on the box origin so every request under the service prefix carries it.
                for (String pair : cookie.split(";")) {
                    String p = pair.trim();
                    if (!p.isEmpty()) cm.setCookie(BoxEndpoints.BASE + "/", p + "; path=/");
                }
                cm.flush();
                hideAuthOverlay();
                webView.loadUrl(targetUrl);
            }
            @Override public void onErr() {
                if (isFinishing() || isDestroyed()) return;   // ADFA-5043: left mid-sign-in; don't touch dead views
                hideAuthOverlay();
                webView.loadUrl(targetUrl);
            }
        });
    }

    private android.view.View authOverlay;

    private void showAuthOverlay() {
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        f.setClickable(true);   // swallow taps while signing in
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        f.addView(pb, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        authOverlay = f;
        addContentView(f, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void hideAuthOverlay() {
        if (authOverlay == null) return;
        android.view.ViewParent parent = authOverlay.getParent();
        if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(authOverlay);
        authOverlay = null;
    }

    /**
     * Fallback for a PDF we cannot show in-view (no pdf.js build fits this WebView, or none is
     * served): hand the file to the system DownloadManager with the server-provided name, so
     * the user still gets it. Generic file download will be unified under ADFA-4710.
     */
    private void downloadServedFile(Uri uri, String contentDisposition, String mimetype) {
        try {
            String fileName = URLUtil.guessFileName(uri.toString(), contentDisposition, mimetype);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            if (mimetype != null && !mimetype.isEmpty()) {
                request.setMimeType(mimetype);
            }
            request.setTitle(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(this, R.string.portal_download_failed, Toast.LENGTH_LONG).show();
                return;
            }
            dm.enqueue(request);
            Toast.makeText(this, getString(R.string.portal_download_started, fileName), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "File download failed to start: " + uri, e);
            Toast.makeText(this, R.string.portal_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Downloads an APK served by the local IIAB box via the system DownloadManager, keeping
     * the server-provided filename (so both the current arm64-v8a build and a future
     * armeabi-v7a one work without code changes). The completed notification opens the
     * system package installer, which enforces the "install unknown apps" consent — we do
     * not install silently. See ADFA-4512.
     */
    private void downloadServedApk(Uri uri, String contentDisposition, String mimetype) {
        try {
            String fileName = uri.getLastPathSegment();
            if (fileName == null || !fileName.toLowerCase().endsWith(".apk")) {
                fileName = URLUtil.guessFileName(uri.toString(), contentDisposition, mimetype);
            }
            if (fileName == null || !fileName.toLowerCase().endsWith(".apk")) {
                fileName = "iiab-code.apk";
            }

            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setMimeType("application/vnd.android.package-archive");
            request.setTitle(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(this, R.string.portal_download_failed, Toast.LENGTH_LONG).show();
                return;
            }
            dm.enqueue(request);
            Toast.makeText(this, getString(R.string.portal_download_started, fileName), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "APK download failed to start: " + uri, e);
            Toast.makeText(this, R.string.portal_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // ADFA-4879: stop FQR polling + drop its overlay/dialog so we don't leak the activity.
        // The durable server job (if any) keeps running and shows up on the next /maps/ reload.
        if (fqr != null) fqr.detach();
        if (kiwixMgr != null) kiwixMgr.detach();   // ADFA-5004
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);   // ADFA-4887
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback == null) return;

            android.net.Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new android.net.Uri[]{android.net.Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new android.net.Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
