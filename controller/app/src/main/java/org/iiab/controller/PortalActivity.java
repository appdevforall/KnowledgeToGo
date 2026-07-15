/*
 * ============================================================================
 * Name        : PortalActivity.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Webview portal activity
 * ============================================================================
 */
package org.iiab.controller;

import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;


import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;
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
    // pdf.js builds advertised by /pdfjs/manifest.json (loaded off the main thread).
    // Empty until loaded / when the box serves none -> PDFs fall back to download.
    private volatile List<PdfViewerBuild> pdfViewerBuilds = Collections.emptyList();
    private PortalViewModel vm;
    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_portal);
        org.iiab.controller.help.TooltipWiring.wireAll(getWindow().getDecorView());

        vm = new ViewModelProvider(this).get(PortalViewModel.class);

        // 1. Basic WebView configuration
        webView = findViewById(R.id.myWebView);
        // Learn which pdf.js builds the box serves so we can route PDFs per WebView version.
        AppExecutors.get().io().execute(() -> pdfViewerBuilds = PdfViewerCatalog.fetch());
        webView.setGestureLogging(BuildConfig.DEBUG);

        LinearLayout bottomNav = findViewById(R.id.bottomNav);
        Button btnHandle = findViewById(R.id.btnHandle); // The new handle
        Button btnHideNav = findViewById(R.id.btnHideNav); // Button to close

        Button btnBack = findViewById(R.id.btnBack);
        Button btnHome = findViewById(R.id.btnHome);
        Button btnReload = findViewById(R.id.btnReload);
        Button btnExit = findViewById(R.id.btnExit);
        Button btnForward = findViewById(R.id.btnForward);

        // --- PREPARE HIDDEN BAR ---
        bottomNav.post(() -> {
            bottomNav.setTranslationY(bottomNav.getHeight()); // Move outside the screen
            bottomNav.setVisibility(View.VISIBLE);
        });

        // --- AUTO-HIDE TIMER ---
        Handler hideHandler = new Handler(Looper.getMainLooper());

        Runnable hideRunnable = () -> {
            bottomNav.animate().translationY(bottomNav.getHeight()).setDuration(250);
            btnHandle.setVisibility(View.VISIBLE);
            btnHandle.animate().alpha(1f).setDuration(150);
        };

        Runnable resetTimer = () -> {
            hideHandler.removeCallbacks(hideRunnable);
            hideHandler.postDelayed(hideRunnable, 5000);
        };

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

        btnHome.setOnClickListener(v -> {
            webView.loadUrl(finalTargetUrl);
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
                btnReload.setText("✕"); // Change to Stop
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                vm.setLoading(false);
                btnReload.setText("↻"); // Back to Reload
                view.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

                // Touch diagnostics + best-effort MapLibre pitch enablement.
                view.evaluateJavascript(TOUCH_PROBE_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String customErrorHtml = "<html><body style='background-color:#1A1A1A;color:#FFFFFF;text-align:center;padding-top:50px;font-family:sans-serif;'>"
                            + "<h2>⚠️ Connection Failed</h2>"
                            + "<p>Unable to reach the secure environment.</p>"
                            + "<p style='color:#888;font-size:12px;'>Error: " + error.getDescription() + "</p>"
                            + "</body></html>";
                    view.loadData(customErrorHtml, "text/html", "UTF-8");
                    vm.setLoading(false);
                    btnReload.setText("↻");
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
                    String viewerUrl = PdfViewerUrl.forPdf("http://localhost:8085" + build.getViewerPath(), url);
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

        // Native architecture: content is served locally; load it directly.
        webView.loadUrl(finalTargetUrl);
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
