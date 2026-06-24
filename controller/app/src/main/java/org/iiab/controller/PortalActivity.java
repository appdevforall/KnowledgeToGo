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
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import java.util.concurrent.Executor;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.iiab.controller.portal.data.WebViewProxyConfigurator;
import org.iiab.controller.portal.domain.NavigationPolicy;
import org.iiab.controller.portal.presentation.GestureWebView;
import org.iiab.controller.portal.presentation.PortalViewModel;

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
    private PortalViewModel vm;
    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_portal);

        vm = new ViewModelProvider(this).get(PortalViewModel.class);

        // 1. Basic WebView configuration
        webView = findViewById(R.id.myWebView);
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

        Preferences prefs = new Preferences(this);
        boolean isVpnActive = prefs.getEnable();

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

        // Port and Mirror logic
        int tempPort = prefs.getSocksPort();
        if (tempPort <= 0) tempPort = 1080;
        final int finalProxyPort = tempPort;

        // Proxy block (ONLY IF VPN IS ACTIVE)
        if (isVpnActive) {
            if (WebViewProxyConfigurator.isSupported()) {
                Executor executor = ContextCompat.getMainExecutor(this);
                WebViewProxyConfigurator.applySocks(finalProxyPort, executor, () -> {
                    Log.d(TAG, "Proxy configured on port: " + finalProxyPort);
                    webView.loadUrl(finalTargetUrl); // load only when proxy is ready
                });
            } else {
                Log.w(TAG, "Proxy Override not supported");
                webView.loadUrl(finalTargetUrl);
            }
        } else {
            // VPN is OFF. Load localhost directly.
            webView.loadUrl(finalTargetUrl);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (WebViewProxyConfigurator.isSupported()) {
            WebViewProxyConfigurator.clear(() -> Log.d(TAG, "WebView proxy released"));
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
