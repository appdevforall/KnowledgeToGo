/*
 * ============================================================================
 * Name        : FqrController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4879. In-app Full-Quality Region (FQR) maps, hosted in the shared PortalActivity
 *               WebView but GATED to the box's /maps/ page (no-op on every other card). The upstream
 *               map is a <maps-black> web component with a shadow root; on drawing a region it shows
 *               a MapLibre popup whose <pre> holds "tile-extract.py extract <name> <box>". Instead of
 *               the copy-paste-into-a-terminal flow, we:
 *                 1) inject a small script that watches the shadow-root popup, parses {name, box},
 *                    hides the raw command, and hands it to native (JS bridge "K2GoFQR");
 *                 2) fetch a size estimate and ask for consent (how much + a free-space bar);
 *                 3) run the durable REST download (MapsRegionClient) with a floating progress
 *                    overlay that can be minimized; on success we reload so the region draws.
 *               No sudo/shell on the device — the box's localhost REST does the work.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FqrController {

    private final Activity activity;
    private final WebView webView;
    private final MapsRegionClient client = new MapsRegionClient();

    private volatile boolean active = false;   // written on UI thread, read on the WebView binder thread
    private AlertDialog dialog;       // "calculating" / consent (one at a time)
    private View overlay;             // floating progress card (null when hidden)
    private ProgressBar overlayBar;
    private TextView overlayPct, overlayTitle;
    private boolean overlayMinimized = false;

    // UI validation mirrors what the map's own name field enforces (lower case, digits, _).
    private static final Pattern NAME_RE = Pattern.compile("^[a-z0-9_]{1,34}$");
    private static final Pattern BOX_RE =
            Pattern.compile("^-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?$");

    public FqrController(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** Attach the JS bridge. Call once, BEFORE the first loadUrl. */
    public void attach() {
        webView.addJavascriptInterface(this, "K2GoFQR");
    }

    /** Host activity is going away: stop polling and drop UI, but let the durable server job keep
     *  running (no cancel). Call from PortalActivity#onDestroy. */
    public void detach() {
        active = false;
        client.stopPolling();
        hideOverlay();
        dismissDialog();
    }

    /** Arm (or disarm) on each page load, based on whether this is the /maps/ page. */
    public void onPageFinished(String url) {
        active = isMapsPage(url);
        if (active) webView.evaluateJavascript(BRIDGE_JS, null);
    }

    static boolean isMapsPage(String url) {
        if (url == null) return false;
        try {
            String path = Uri.parse(url).getPath();
            return path != null && (path.equals("/maps/") || path.equals("/maps"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- JS bridge (called from the injected observer, off the UI thread) --------------------
    @JavascriptInterface
    public void onExtractRequested(String name, String box) {
        if (!active) return;   // never act off the maps page
        activity.runOnUiThread(() -> handleExtract(name == null ? "" : name.trim(),
                box == null ? "" : box.replaceAll("\\s+", "")));
    }

    private void handleExtract(String name, String box) {
        if (!NAME_RE.matcher(name).matches() || !BOX_RE.matcher(box).matches()) {
            toast("That region name or area looks invalid.");
            return;
        }
        // "Calculating size…" while the server runs its dry-run.
        showCalculating();
        client.estimate(box, new MapsRegionClient.EstimateListener() {
            @Override public void onEstimate(long transfer, long archive, long free, long freeAfter) {
                dismissDialog();
                showConsent(name, box, transfer, archive, free, freeAfter);
            }
            @Override public void onError(String message) {
                dismissDialog();
                new AlertDialog.Builder(activity)
                        .setTitle("Can't download this region")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    // ---- Consent -----------------------------------------------------------------------------
    private void showCalculating() {
        dismissDialog();
        LinearLayout box = card(dp(20));
        ProgressBar spin = new ProgressBar(activity);
        box.addView(spin, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = new TextView(activity);
        t.setText("Calculating download size…");
        t.setPadding(dp(14), 0, 0, 0);
        t.setTextColor(Color.WHITE);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(t);
        dialog = new AlertDialog.Builder(activity).setView(box).setCancelable(true).show();
    }

    private void showConsent(String name, String box, long transfer, long archive, long free, long freeAfter) {
        LinearLayout root = card(dp(20));

        TextView title = new TextView(activity);
        title.setText("Download this region?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(activity);
        sub.setText(String.format(Locale.US, "“%s” — %s to download, %s on disk.",
                name, human(transfer), human(archive)));
        sub.setTextColor(0xFFB9C4BF);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        // Free-space bar: how much of the current free space this region takes.
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        long denom = Math.max(free, archive + freeAfter);
        if (denom <= 0) denom = 1;
        View used = new View(activity);
        used.setBackgroundColor(0xFF4CAF7D);   // this region (leaf)
        View freeSeg = new View(activity);
        freeSeg.setBackgroundColor(0xFF223029); // free after
        int h = dp(12);
        bar.addView(used, new LinearLayout.LayoutParams(0, h, Math.max(1f, archive)));
        bar.addView(freeSeg, new LinearLayout.LayoutParams(0, h, Math.max(1f, Math.max(0, denom - archive))));
        root.addView(bar);

        TextView legend = new TextView(activity);
        legend.setText(String.format(Locale.US, "This region: %s  ·  Free after: %s", human(archive), human(freeAfter)));
        legend.setTextColor(0xFF8FA39B);
        legend.setTextSize(11);
        legend.setPadding(0, dp(8), 0, 0);
        root.addView(legend);

        dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Not now", (d, w) -> d.dismiss())
                .setPositiveButton("Download", (d, w) -> startDownload(name))
                .setCancelable(true)
                .show();

        // Kick off the actual download only after consent; the box already re-validates.
        // (startDownload is invoked from the positive button above.)
        pendingBox = box;
    }

    private String pendingBox;   // box paired with the name at consent time

    private void startDownload(String name) {
        final String box = pendingBox;
        if (box == null) return;
        showOverlay(name);
        client.download(name, box, new MapsRegionClient.DownloadListener() {
            @Override public void onProgress(int percent) { updateOverlay(percent); }
            @Override public void onDone() {
                updateOverlay(100);
                overlayTitle.setText("Region added");
                webView.postDelayed(() -> { hideOverlay(); webView.reload(); }, 1200);
            }
            @Override public void onError(String message) {
                hideOverlay();
                if (!"canceled".equals(message)) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Download failed").setMessage(message)
                            .setPositiveButton("OK", null).show();
                }
            }
        });
    }

    // ---- Floating progress overlay -----------------------------------------------------------
    private void showOverlay(String name) {
        hideOverlay();
        LinearLayout cardV = card(dp(16));
        cardV.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        overlayTitle = new TextView(activity);
        overlayTitle.setText(String.format(Locale.US, "Downloading “%s”", name));
        overlayTitle.setTextColor(Color.WHITE);
        overlayTitle.setTypeface(overlayTitle.getTypeface(), Typeface.BOLD);
        top.addView(overlayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView min = new TextView(activity);
        min.setText("–");   // minimize
        min.setTextColor(0xFF8FA39B);
        min.setTextSize(20);
        min.setPadding(dp(12), 0, dp(8), 0);
        min.setOnClickListener(v -> toggleMinimize());
        top.addView(min);
        cardV.addView(top);

        overlayBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        overlayBar.setMax(100);
        overlayBar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        cardV.addView(overlayBar, blp);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        overlayPct = new TextView(activity);
        overlayPct.setText("Starting…");
        overlayPct.setTextColor(0xFF8FA39B);
        row.addView(overlayPct, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button cancel = new Button(activity);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> { client.cancel(); hideOverlay(); });
        row.addView(cancel);
        cardV.addView(row, rlp);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        params.setMargins(dp(12), dp(12), dp(12), 0);
        overlay = cardV;
        overlayMinimized = false;
        activity.addContentView(overlay, params);
    }

    private void toggleMinimize() {
        if (overlay == null) return;
        overlayMinimized = !overlayMinimized;
        LinearLayout cardV = (LinearLayout) overlay;
        // Keep the title row (index 0) always; hide bar (1) and controls row (2) when minimized.
        for (int i = 1; i < cardV.getChildCount(); i++) {
            cardV.getChildAt(i).setVisibility(overlayMinimized ? View.GONE : View.VISIBLE);
        }
    }

    private void updateOverlay(int percent) {
        if (overlayBar == null || overlayPct == null) return;
        if (percent < 0) {
            overlayBar.setIndeterminate(true);
            overlayPct.setText("Working…");
        } else {
            overlayBar.setIndeterminate(false);
            overlayBar.setProgress(percent);
            overlayPct.setText(percent + "%");
        }
    }

    private void hideOverlay() {
        if (overlay == null) return;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) parent.removeView(overlay);
        overlay = null; overlayBar = null; overlayPct = null; overlayTitle = null;
        overlayMinimized = false;
    }

    // ---- helpers -----------------------------------------------------------------------------
    private LinearLayout card(int pad) {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(pad, pad, pad, pad);
        l.setBackgroundColor(0xFF16201B);
        l.setElevation(dp(8));
        return l;
    }

    private void dismissDialog() {
        if (dialog != null) { try { dialog.dismiss(); } catch (Exception ignore) { } dialog = null; }
    }

    private void toast(String m) { Toast.makeText(activity, m, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }

    private static String human(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / 1_000_000.0;
        if (mb < 1000) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1000.0);
    }

    // Injected once per maps page load. Idempotent (guards window.__k2goFqr). Pierces the
    // <maps-black> shadow root, watches for the extract popup's <pre>, parses name+box, hides the
    // raw command, and calls the native bridge. Delete (delete <name>) is intentionally ignored
    // here — that flow lands in a later PR.
    private static final String BRIDGE_JS =
            "(function(){try{" +
            "if(window.__k2goFqr)return;window.__k2goFqr=true;" +
            "var host=document.querySelector('maps-black');if(!host||!host.shadowRoot){console.log('K2Go-FQR no shadow');return;}" +
            "var sr=host.shadowRoot;" +
            "var RE=/tile-extract\\.py\\s+extract\\s+([a-z0-9_]{1,34})\\s+(-?[\\d.]+,-?[\\d.]+,-?[\\d.]+,-?[\\d.]+)/;" +
            "function handle(pre){try{var t=(pre.textContent||'');var m=t.match(RE);if(!m)return false;" +
            "var pop=pre.closest?pre.closest('.maplibregl-popup'):null;if(pop){pop.style.display='none';}" +
            "if(window.K2GoFQR&&K2GoFQR.onExtractRequested){K2GoFQR.onExtractRequested(m[1],m[2]);}return true;}catch(e){return false;}}" +
            "var ex=sr.querySelector('.maplibregl-popup pre');if(ex)handle(ex);" +
            "var mo=new MutationObserver(function(ms){ms.forEach(function(mu){var a=mu.addedNodes||[];for(var i=0;i<a.length;i++){var n=a[i];if(!n||n.nodeType!==1)continue;" +
            "var pre=(n.matches&&n.matches('pre'))?n:(n.querySelector?n.querySelector('.maplibregl-popup pre, pre'):null);" +
            "if(pre)handle(pre);}});});" +
            "mo.observe(sr,{childList:true,subtree:true});" +
            "console.log('K2Go-FQR bridge armed');" +
            "}catch(e){try{console.log('K2Go-FQR fatal '+e);}catch(_){}}})();";
}
