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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class FqrController {

    private final Activity activity;
    private final WebView webView;
    private final MapsRegionClient client = new MapsRegionClient();

    private volatile boolean active = false;   // written on UI thread, read on the WebView binder thread
    private AlertDialog dialog;       // "calculating" / consent (one at a time)
    private View overlay;             // floating progress card (null when hidden)
    private ProgressBar overlayBar;
    private TextView overlayPct, overlayTitle, overlayMin;
    private boolean overlayMinimized = false;

    // Delete: unified list bottom-sheet (~55%) fed by the manual trash tool.
    private View deleteSheet;
    private EditText searchField;
    private LinearLayout listContainer;
    private final List<Region> regions = new ArrayList<>();
    private String highlight;   // region to float to the top / tint (the one just clicked on the map)
    private boolean deleteToolOn = false;   // mirrors the map's trash tool on/off so a 2nd press closes
    // The delete list is a bottom sheet covering this fraction of the screen; fly-to reserves the
    // same fraction as bottom padding so a picked region centers in the VISIBLE map above the sheet.
    private static final float DELETE_SHEET_FRACTION = 0.40f;
    private static final class Region {
        final String name; final double[] b;   // b = ui_bounds [minLon,minLat,maxLon,maxLat] or null
        Region(String n, double[] bb) { name = n; b = bb; }
    }

    // UI validation mirrors what the map's own name field enforces (lower case, digits, _).
    private static final Pattern NAME_RE = Pattern.compile("^[a-z0-9_]{1,34}$");
    private static final Pattern BOX_RE =
            Pattern.compile("^-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?$");

    static boolean validName(String name) { return name != null && NAME_RE.matcher(name).matches(); }
    static boolean validBox(String box) { return box != null && BOX_RE.matcher(box).matches(); }

    public FqrController(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** Add the JS bridge only for the /maps/ page and remove it elsewhere, to keep the interface off
     *  non-maps pages (defense in depth; the bridge methods also guard on {@code active}). Call before
     *  each load (initial + navigations) — addJavascriptInterface takes effect on the next load. */
    public void prepareForUrl(String url) {
        if (isMapsPage(url)) webView.addJavascriptInterface(this, "K2GoFQR");
        else webView.removeJavascriptInterface("K2GoFQR");
    }

    /** Host activity is going away: stop polling and drop UI, but let the durable server job keep
     *  running (no cancel). Call from PortalActivity#onDestroy. */
    public void detach() {
        active = false;
        deleteToolOn = false;
        client.stopPolling();
        hideOverlay();
        hideDeleteSheet();
        dismissDialog();
    }

    /** Arm (or disarm) on each page load, based on whether this is the /maps/ page. */
    public void onPageFinished(String url) {
        active = isMapsPage(url);
        if (active) { deleteToolOn = false; webView.evaluateJavascript(BRIDGE_JS, null); }
    }

    /** True when the URL's path is the box's maps page. Pure string parsing (no android.net.Uri) so
     *  it is unit-testable and dependency-free. */
    static boolean isMapsPage(String url) {
        if (url == null) return false;
        String u = url;
        int hash = u.indexOf('#'); if (hash >= 0) u = u.substring(0, hash);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int scheme = u.indexOf("://");
        if (scheme >= 0) { int slash = u.indexOf('/', scheme + 3); u = slash >= 0 ? u.substring(slash) : "/"; }
        return u.equals("/maps/") || u.equals("/maps");
    }

    // ---- JS bridge (called from the injected observer, off the UI thread) --------------------
    @JavascriptInterface
    public void onExtractRequested(String name, String box) {
        if (!active) return;   // never act off the maps page
        activity.runOnUiThread(() -> handleExtract(name == null ? "" : name.trim(),
                box == null ? "" : box.replaceAll("\\s+", "")));
    }

    /** The map's trash tool was activated → open the (searchable) region list so the user doesn't
     *  have to hunt a tiny rectangle on the map. */
    @JavascriptInterface
    public void onDeleteToolOpened() {
        if (!active) return;
        // The map's trash tool toggles on every click, so mirror it: open on activate, close on
        // deactivate — a second press dismisses the list (feels native). Closing via the sheet's own
        // X does NOT flip this flag, so the following deactivate press correctly leaves it closed.
        activity.runOnUiThread(() -> {
            deleteToolOn = !deleteToolOn;
            if (deleteToolOn) openDeleteList(null);
            else hideDeleteSheet();
        });
    }

    /** The user clicked a region with the trash tool → open the list with that region on top. */
    @JavascriptInterface
    public void onDeleteRequested(String name) {
        if (!active) return;
        final String n = name == null ? "" : name.trim();
        activity.runOnUiThread(() -> openDeleteList(n));
    }

    private void handleExtract(String name, String box) {
        if (!validName(name) || !validBox(box)) {
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
        pendingArchive = archive;
    }

    private String pendingBox;      // box paired with the name at consent time
    private long pendingArchive;    // on-disk size (bytes) to show in the overlay header

    private void startDownload(String name) {
        final String box = pendingBox;
        if (box == null) return;
        showOverlay(name, pendingArchive);
        client.download(name, box, new MapsRegionClient.DownloadListener() {
            @Override public void onProgress(int percent, long speed) { updateOverlay(percent, speed); }
            @Override public void onDone() {
                updateOverlay(100, 0);
                if (overlayTitle != null) overlayTitle.setText("Region added");   // may be gone if hidden
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
    private void showOverlay(String name, long sizeBytes) {
        hideOverlay();
        LinearLayout cardV = card(dp(16));
        cardV.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        overlayTitle = new TextView(activity);
        final String size = sizeBytes > 0 ? "  ·  " + human(sizeBytes) : "";   // "Downloading “x” · 2.1 GB"
        overlayTitle.setText(String.format(Locale.US, "Downloading “%s”%s", name, size));
        overlayTitle.setTextColor(Color.WHITE);
        overlayTitle.setTypeface(overlayTitle.getTypeface(), Typeface.BOLD);
        top.addView(overlayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        overlayMin = new TextView(activity);
        overlayMin.setText("Hide");   // minimize to a compact card; tap again to Show
        overlayMin.setTextColor(0xFF8FA39B);
        overlayMin.setTextSize(13);
        overlayMin.setPadding(dp(12), 0, dp(4), 0);
        overlayMin.setOnClickListener(v -> toggleMinimize());
        top.addView(overlayMin);
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
        // Minimized = keep the title + the slim advancing bar; hide only the percent/cancel row,
        // so progress stays visible even when collapsed (the point of minimizing is minimal chrome).
        View controls = cardV.getChildAt(cardV.getChildCount() - 1);
        controls.setVisibility(overlayMinimized ? View.GONE : View.VISIBLE);
        if (overlayMin != null) overlayMin.setText(overlayMinimized ? "Show" : "Hide");
    }

    private void updateOverlay(int percent, long speed) {
        if (overlayBar == null || overlayPct == null) return;
        final String rate = speed > 0 ? "  ·  " + humanRate(speed) : "";
        if (percent < 0) {
            overlayBar.setIndeterminate(true);
            overlayPct.setText("Working…" + rate);
        } else {
            overlayBar.setIndeterminate(false);
            overlayBar.setProgress(percent);
            overlayPct.setText(percent + "%" + rate);
        }
    }

    /** bytes/sec -> "2.4 MB/s". Empty for non-positive (speed not reported yet). */
    private static String humanRate(long bps) {
        if (bps <= 0) return "";
        final String[] u = {"B", "KB", "MB", "GB"};
        double v = bps; int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return (i == 0 ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v)) + " " + u[i] + "/s";
    }

    private void hideOverlay() {
        if (overlay == null) return;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) parent.removeView(overlay);
        overlay = null; overlayBar = null; overlayPct = null; overlayTitle = null; overlayMin = null;
        overlayMinimized = false;
    }

    // ---- Delete: unified list bottom-sheet + manual capture ----------------------------------
    private void openDeleteList(String hl) {
        if (hl != null && !hl.isEmpty()) this.highlight = hl;   // keep last highlight otherwise
        if (deleteSheet == null) buildDeleteSheet();
        refreshRegions();
    }

    private void buildDeleteSheet() {
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(0xFF0F1512);
        sheet.setElevation(dp(12));
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("Downloaded regions");
        title.setTextColor(Color.WHITE);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(16);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(0xFF8FA39B);
        close.setTextSize(18);
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setOnClickListener(v -> hideDeleteSheet());
        header.addView(close);
        sheet.addView(header);

        searchField = new EditText(activity);
        searchField.setHint("Search by name");
        searchField.setSingleLine(true);
        searchField.setTextColor(Color.WHITE);
        searchField.setHintTextColor(0xFF6E7F78);
        LinearLayout.LayoutParams sflp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sflp.topMargin = dp(8);
        sheet.addView(searchField, sflp);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { renderRows(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });

        ScrollView sv = new ScrollView(activity);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listContainer);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svlp.topMargin = dp(8);
        sheet.addView(sv, svlp);

        // ~55% of the screen so the map stays visible + pannable above the sheet.
        int h = Math.round(activity.getResources().getDisplayMetrics().heightPixels * DELETE_SHEET_FRACTION);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        params.gravity = Gravity.BOTTOM;
        deleteSheet = sheet;
        activity.addContentView(deleteSheet, params);
    }

    private void refreshRegions() {
        client.listRegions(new MapsRegionClient.RegionsListener() {
            @Override public void onRegions(JSONObject regs) {
                regions.clear();
                for (Iterator<String> it = regs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    regions.add(new Region(k, boundsOf(regs.optJSONObject(k))));
                }
                renderRows(searchField != null ? searchField.getText().toString() : "");
            }
            @Override public void onError(String m) { toast(m); }
        });
    }

    static double[] boundsOf(JSONObject o) {
        if (o == null) return null;
        JSONArray a = o.optJSONArray("ui_bounds");
        if (a == null) a = o.optJSONArray("render_bounds");
        if (a == null || a.length() < 4) return null;
        return new double[]{ a.optDouble(0), a.optDouble(1), a.optDouble(2), a.optDouble(3) };
    }

    private void renderRows(String filter) {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        final String f = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        List<Region> sorted = new ArrayList<>(regions);
        Collections.sort(sorted, (x, y) -> {
            boolean hx = x.name.equals(highlight), hy = y.name.equals(highlight);
            if (hx != hy) return hx ? -1 : 1;   // the just-selected region floats to the top
            return x.name.compareTo(y.name);
        });
        int shown = 0;
        for (Region r : sorted) {
            if (!f.isEmpty() && !r.name.toLowerCase(Locale.US).contains(f)) continue;
            listContainer.addView(regionRow(r));
            shown++;
        }
        if (shown == 0) {
            TextView t = new TextView(activity);
            t.setText(regions.isEmpty() ? "No downloaded regions yet." : "No matches.");
            t.setTextColor(0xFF8FA39B);
            t.setPadding(0, dp(14), 0, 0);
            listContainer.addView(t);
        }
    }

    private View regionRow(Region r) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));
        if (r.name.equals(highlight)) row.setBackgroundColor(0x334CAF7D);
        TextView name = new TextView(activity);
        name.setText(r.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(14);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> flyTo(r));   // tapping a row flies the map behind to that region
        TextView x = new TextView(activity);
        x.setText("✕");
        x.setTextColor(0xFFE05353);
        x.setTextSize(16);
        x.setPadding(dp(16), dp(2), dp(6), dp(2));
        x.setOnClickListener(v -> confirmDelete(r.name));
        row.addView(x);
        return row;
    }

    private void flyTo(Region r) {
        if (r.b == null) return;
        webView.evaluateJavascript(String.format(Locale.US,
                "window.__k2goFlyTo&&window.__k2goFlyTo(%s,%s,%s,%s,%s);",
                r.b[0], r.b[1], r.b[2], r.b[3], DELETE_SHEET_FRACTION), null);
    }

    private void confirmDelete(String name) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete “" + name + "”?")
                .setMessage("This full-quality region will be removed from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> client.deleteRegion(name, new MapsRegionClient.DeleteListener() {
                    @Override public void onOk() {
                        toast("Deleted “" + name + "”");
                        highlight = null;
                        webView.reload();     // map redraws without the region
                        refreshRegions();     // and the list drops it
                    }
                    @Override public void onError(String m) {
                        new AlertDialog.Builder(activity).setTitle("Delete failed").setMessage(m)
                                .setPositiveButton("OK", null).show();
                    }
                }))
                .show();
    }

    private void hideDeleteSheet() {
        if (deleteSheet == null) return;
        ViewGroup parent = (ViewGroup) deleteSheet.getParent();
        if (parent != null) parent.removeView(deleteSheet);
        deleteSheet = null; searchField = null; listContainer = null; highlight = null;
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

    // Injected once per maps page load (idempotent). Pierces the <maps-black> shadow root and bridges
    // to native: EXTRACT fires when the user presses Next (the command <pre> is rebuilt on every
    // keystroke, so we can't fire from the observer); DELETE is caught by a MutationObserver that runs
    // ONLY while the trash tool is on; the trash tool also opens our list; __k2goFlyTo flies the map.
    private static final String BRIDGE_JS =
            "(function(){try{" +
            "if(window.__k2goFqr)return;window.__k2goFqr=true;" +
            "var host=document.querySelector('maps-black');if(!host||!host.shadowRoot){console.log('K2Go-FQR no shadow');return;}" +
            "var sr=host.shadowRoot;" +
            "var EX=/tile-extract\\.py\\s+extract\\s+([a-z0-9_]{1,34})\\s+(-?[\\d.]+,-?[\\d.]+,-?[\\d.]+,-?[\\d.]+)/;" +
            "var DE=/tile-extract\\.py\\s+delete\\s+([a-z0-9_]{1,34})/;" +
            "function hidePop(pre){var p=pre.closest?pre.closest('.maplibregl-popup'):null;if(p){p.style.display='none';}}" +
            // Extract: the command <pre> is rebuilt on EVERY keystroke, so firing from the observer
            // captured after the first letter. Fire only when the user presses Next (name is final);
            // read the <pre> then — it's already updated live with the full name.
            "function fireExtract(){try{var pre=sr.querySelector('.maplibregl-popup pre');if(!pre)return;" +
            "var m=(pre.textContent||'').match(EX);if(!m)return;hidePop(pre);" +
            "if(window.K2GoFQR&&K2GoFQR.onExtractRequested){K2GoFQR.onExtractRequested(m[1],m[2]);}}catch(e){}}" +
            // Delete: the delete popup shows a complete command (no typing) -> observe + fire.
            "function handleDelete(pre){try{var d=(pre.textContent||'').match(DE);if(!d)return false;hidePop(pre);" +
            "if(window.K2GoFQR&&K2GoFQR.onDeleteRequested){K2GoFQR.onDeleteRequested(d[1]);}return true;}catch(e){return false;}}" +
            "var mo=new MutationObserver(function(ms){ms.forEach(function(mu){var a=mu.addedNodes||[];for(var i=0;i<a.length;i++){var n=a[i];if(!n||n.nodeType!==1)continue;" +
            "var pre=(n.matches&&n.matches('pre'))?n:(n.querySelector?n.querySelector('.maplibregl-popup pre, pre'):null);" +
            "if(pre)handleDelete(pre);}});});" +
            // Connect the observer only while the trash tool is on -> no churn during normal map use.
            "var observing=false,deleteMode=false;" +
            "function setObserve(on){try{if(on&&!observing){mo.observe(sr,{childList:true,subtree:true});observing=true;}else if(!on&&observing){mo.disconnect();observing=false;}}catch(e){}}" +
            // Delegated clicks: the trash tool opens our list; "Next" finalizes the extract name.
            "sr.addEventListener('click',function(ev){try{var path=ev.composedPath?ev.composedPath():[];" +
            "for(var i=0;i<path.length;i++){var el=path[i];if(!el)continue;" +
            "if(el.title==='Choose region to delete'){deleteMode=!deleteMode;setObserve(deleteMode);if(window.K2GoFQR&&K2GoFQR.onDeleteToolOpened){K2GoFQR.onDeleteToolOpened();}break;}" +
            "if(el.tagName==='BUTTON'&&(el.textContent||'').trim()==='Next'){setTimeout(fireExtract,0);break;}" +
            "}}catch(e){}},true);" +
            // Native calls this to fly the map behind the list sheet to a picked region.
            "window.__k2goFlyTo=function(a,b,c,d,frac){try{var mm=document.querySelector('maps-black').map;" +
            "var el=mm.getContainer?mm.getContainer():null;var H=el?el.clientHeight:0;" +
            "var pb=Math.round(H*(frac||0))+40;" +   // reserve the sheet's area at the bottom
            "mm.fitBounds([[a,b],[c,d]],{padding:{top:40,left:40,right:40,bottom:pb},duration:600});}catch(e){}};" +
            "console.log('K2Go-FQR bridge armed');" +
            "}catch(e){try{console.log('K2Go-FQR fatal '+e);}catch(_){}}})();";
}
