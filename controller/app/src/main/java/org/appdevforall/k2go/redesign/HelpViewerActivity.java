/*
 * ============================================================================
 * Name        : HelpViewerActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-406. In-app manual viewer -- a themed Material 3 WebView that serves the
 *               bundled Help&Manual site from assets/help/ via WebViewAssetLoader.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MenuItem;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.material.appbar.MaterialToolbar;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.portal.data.PdfViewerCatalog;
import org.appdevforall.k2go.portal.domain.PdfPolicy;
import org.appdevforall.k2go.portal.domain.PdfViewerBuild;
import org.appdevforall.k2go.portal.domain.PdfViewerRouter;
import org.appdevforall.k2go.portal.domain.PdfViewerUrl;
import org.appdevforall.k2go.portal.domain.WebViewVersion;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.Collections;
import java.util.List;

/**
 * The in-app manual (K2GO-406). Serves the bundled Help&Manual site from {@code assets/help/}
 * through {@link WebViewAssetLoader}: a same-origin {@code https://appassets.androidplatform.net}
 * context, so the manual's full-text search works without the {@code file://} XHR block and
 * without the insecure file-URL flags a raw {@code file://} load would need.
 *
 * <p>PDF links reuse the portal's box-served, version-aware pdf.js router + download fallback
 * (the documentation team requires PDF + JS inside the doc viewer). That logic is the shared
 * {@code portal.domain}/{@code portal.data} module, so PortalActivity is untouched and nothing is
 * duplicated; a bundled manual has no PDFs today, but the mechanism is present for when the docs
 * add one and for the rootfs-served docs. In-manual links stay in the WebView; external links open
 * in the system browser.
 */
public class HelpViewerActivity extends AppCompatActivity {

    private static final String TAG = "K2Go-Help";
    private static final String APPASSETS_HOST = "appassets.androidplatform.net";
    private static final String HELP_ENTRY =
            "https://" + APPASSETS_HOST + "/assets/help/index.html";

    private WebView webView;
    // pdf.js builds advertised by the box's /pdfjs/manifest.json (loaded off the main thread);
    // empty until loaded / when no box is present, in which case a PDF falls back to download.
    private volatile List<PdfViewerBuild> pdfViewerBuilds = Collections.emptyList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_k2go_help);

        MaterialToolbar toolbar = findViewById(R.id.k2go_help_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.k2go_settings_help));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = findViewById(R.id.k2go_help_webview);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        // Learn which pdf.js builds the box serves so a PDF link opens in the box's viewer (same
        // dual-build routing as PortalActivity). Offline-safe: returns empty when no box is up.
        AppExecutors.get().io().execute(() -> pdfViewerBuilds = PdfViewerCatalog.fetch());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // In-manual navigation (the appassets origin) stays in the WebView.
                if (APPASSETS_HOST.equals(request.getUrl().getHost())) {
                    return false;
                }
                // External link: hand to the system browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception e) {
                    Log.e(TAG, "No app to open external link: " + request.getUrl());
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String html = "<html><body style='font-family:sans-serif;text-align:center;"
                            + "padding:48px 24px;'><h2>" + getString(R.string.k2go_portal_error_title)
                            + "</h2><p>" + getString(R.string.k2go_portal_error_body)
                            + "</p></body></html>";
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }
        });

        // PDF (doc-team requirement): route to the box's pdf.js when a compatible build is served,
        // else download. Non-PDF downloads (rare in a manual) also fall through to the downloader.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (PdfPolicy.isPdf(url, mimetype, contentDisposition)) {
                PdfViewerBuild build =
                        PdfViewerRouter.pick(WebViewVersion.chromeMajor(userAgent), pdfViewerBuilds);
                if (build != null) {
                    String viewerUrl = PdfViewerUrl.forPdf(BoxEndpoints.BASE + build.getViewerPath(), url);
                    if (viewerUrl != null) {
                        webView.loadUrl(viewerUrl);
                        return;
                    }
                }
            }
            downloadFile(Uri.parse(url), contentDisposition, mimetype);
        });

        webView.loadUrl(HELP_ENTRY);
    }

    private void downloadFile(Uri uri, String contentDisposition, String mimetype) {
        try {
            String fileName = URLUtil.guessFileName(uri.toString(), contentDisposition, mimetype);
            DownloadManager.Request req = new DownloadManager.Request(uri);
            if (mimetype != null && !mimetype.isEmpty()) {
                req.setMimeType(mimetype);
            }
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(this, R.string.portal_download_failed, Toast.LENGTH_LONG).show();
                return;
            }
            dm.enqueue(req);
            Toast.makeText(this, getString(R.string.portal_download_started, fileName), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Download failed to start: " + uri, e);
            Toast.makeText(this, R.string.portal_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
