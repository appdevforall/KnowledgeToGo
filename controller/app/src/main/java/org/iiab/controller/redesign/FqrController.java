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
 *
 *               ADFA-4884: the overlays/dialogs (consent sheet, "calculating" dialog, floating
 *               progress overlay, delete list) are themed with Material 3 + system day/night by
 *               building them through a ContextThemeWrapper(Theme.K2Go) — a Material3.DayNight theme
 *               carrying the app palette — without touching PortalActivity's global theme. Colors come
 *               from theme attributes (colorSurface/onSurface/primary/error/…) so they follow light/dark;
 *               dialogs use MaterialAlertDialogBuilder, buttons MaterialButton, and the progress bars
 *               the Material progress indicators. All user-facing text lives in string resources.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.iiab.controller.R;
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

    // Material 3 + day/night for our own overlays/dialogs, without changing PortalActivity's global
    // theme: build every view/dialog through this wrapper so ?attr colors + the app font resolve.
    private final Context themed;
    private final int cSurface, cSurfaceContainer, cSurfaceHighest, cOnSurface, cOnSurfaceVariant, cPrimary, cError;

    private volatile boolean active = false;   // written on UI thread, read on the WebView binder thread
    private AlertDialog dialog;       // "calculating" / consent (one at a time)
    private View overlay;             // floating progress card (null when hidden)
    private LinearProgressIndicator overlayBar;
    private TextView overlayPct, overlayTitle;
    private TextView overlayMin;
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
        this.themed = new ContextThemeWrapper(activity, R.style.Theme_K2Go);
        this.cSurface         = attr(com.google.android.material.R.attr.colorSurface, 0xFF16201B);
        this.cSurfaceContainer= attr(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF16201B);
        this.cSurfaceHighest  = attr(com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF223029);
        this.cOnSurface       = attr(com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        this.cOnSurfaceVariant= attr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF8FA39B);
        // colorPrimary/colorError are declared by appcompat (not the Material R.attr), unlike the
        // Material3-specific surface roles above; read them from the appcompat namespace.
        this.cPrimary         = attr(androidx.appcompat.R.attr.colorPrimary, 0xFF4CAF7D);
        this.cError           = attr(androidx.appcompat.R.attr.colorError, 0xFFE05353);
    }

    private int attr(int attrId, int fallback) {
        return MaterialColors.getColor(themed, attrId, fallback);
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

    /** The map's trash tool was toggled; {@code on} is its REAL state (read from the map cursor, not a
     *  parallel flip that could desync/invert). Open the list when it turns on, close it when off. A
     *  second press dismisses the list (feels native); closing via the sheet's X doesn't change the
     *  tool, so the next deactivate correctly leaves it closed. */
    @JavascriptInterface
    public void onDeleteToolState(boolean on) {
        if (!active) return;
        activity.runOnUiThread(() -> {
            deleteToolOn = on;
            if (on) openDeleteList(null);
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
            toast(str(R.string.k2go_fqr_invalid));
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
                new MaterialAlertDialogBuilder(themed)
                        .setTitle(R.string.k2go_fqr_estimate_error_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    // ---- Consent -----------------------------------------------------------------------------
    private void showCalculating() {
        dismissDialog();
        LinearLayout row = dialogContent(dp(20));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        CircularProgressIndicator spin = new CircularProgressIndicator(themed);
        spin.setIndeterminate(true);
        spin.setIndicatorSize(dp(28));
        row.addView(spin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(themed);
        t.setText(R.string.k2go_fqr_calculating);
        t.setPadding(dp(14), 0, 0, 0);
        t.setTextColor(cOnSurface);
        row.addView(t);
        dialog = new MaterialAlertDialogBuilder(themed).setView(row).setCancelable(true).show();
    }

    private void showConsent(String name, String box, long transfer, long archive, long free, long freeAfter) {
        LinearLayout body = dialogContent(dp(4));

        TextView sub = new TextView(themed);
        sub.setText(str(R.string.k2go_fqr_consent_sub, name, human(transfer), human(archive)));
        sub.setTextColor(cOnSurface);
        sub.setPadding(0, 0, 0, dp(14));
        body.addView(sub);

        // Free-space bar: how much of the current free space this region takes.
        LinearLayout bar = new LinearLayout(themed);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        long denom = Math.max(free, archive + freeAfter);
        if (denom <= 0) denom = 1;
        View used = new View(themed);
        used.setBackgroundColor(cPrimary);        // this region (leaf)
        View freeSeg = new View(themed);
        freeSeg.setBackgroundColor(cSurfaceHighest); // free after
        int h = dp(12);
        bar.addView(used, new LinearLayout.LayoutParams(0, h, Math.max(1f, archive)));
        bar.addView(freeSeg, new LinearLayout.LayoutParams(0, h, Math.max(1f, Math.max(0, denom - archive))));
        body.addView(bar);

        TextView legend = new TextView(themed);
        legend.setText(str(R.string.k2go_fqr_consent_legend, human(archive), human(Math.max(0, freeAfter))));
        legend.setTextColor(cOnSurfaceVariant);
        legend.setTextSize(11);
        legend.setPadding(0, dp(8), 0, 0);
        body.addView(legend);

        // ADFA-4884: warn when the region wouldn't fit (negative free-after = disk almost full).
        if (freeAfter < 0) {
            TextView warn = new TextView(themed);
            warn.setText(str(R.string.k2go_fqr_consent_wont_fit, human(-freeAfter)));
            warn.setTextColor(cError);
            warn.setTextSize(12);
            warn.setPadding(0, dp(10), 0, 0);
            body.addView(warn);
        }

        dialog = new MaterialAlertDialogBuilder(themed)
                .setTitle(R.string.k2go_fqr_consent_title)
                .setView(body)
                .setNegativeButton(R.string.k2go_fqr_not_now, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.k2go_fqr_download, (d, w) -> startDownload(name))
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
                if (overlayTitle != null) overlayTitle.setText(R.string.k2go_fqr_region_added); // may be gone if hidden
                webView.postDelayed(() -> { hideOverlay(); webView.reload(); }, 1200);
            }
            @Override public void onError(String message) {
                hideOverlay();
                if (!"canceled".equals(message)) {
                    new MaterialAlertDialogBuilder(themed)
                            .setTitle(R.string.k2go_fqr_download_failed).setMessage(message)
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            }
        });
    }

    // ---- Floating progress overlay -----------------------------------------------------------
    private void showOverlay(String name, long sizeBytes) {
        hideOverlay();
        LinearLayout cardV = surfaceCard(dp(16), 16f);
        cardV.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(themed);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        overlayTitle = new TextView(themed);
        final String title = str(R.string.k2go_fqr_downloading, name)
                + (sizeBytes > 0 ? "  ·  " + human(sizeBytes) : "");   // "Downloading “x” · 2.1 GB"
        overlayTitle.setText(title);
        overlayTitle.setTextColor(cOnSurface);
        overlayTitle.setTypeface(overlayTitle.getTypeface(), Typeface.BOLD);
        top.addView(overlayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        overlayMin = new TextView(themed);
        overlayMin.setText(R.string.k2go_hide);   // minimize to a compact card; tap again to Show
        overlayMin.setTextColor(cPrimary);
        overlayMin.setTextSize(13);
        overlayMin.setPadding(dp(12), 0, dp(4), 0);
        overlayMin.setOnClickListener(v -> toggleMinimize());
        top.addView(overlayMin);
        cardV.addView(top);

        overlayBar = new LinearProgressIndicator(themed);
        overlayBar.setIndeterminate(true);
        overlayBar.setTrackCornerRadius(dp(2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        cardV.addView(overlayBar, blp);

        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        overlayPct = new TextView(themed);
        overlayPct.setText(R.string.k2go_fqr_starting);
        overlayPct.setTextColor(cOnSurfaceVariant);
        row.addView(overlayPct, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton cancel = new MaterialButton(themed, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(R.string.k2go_cancel);
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
        if (overlayMin != null) overlayMin.setText(overlayMinimized ? R.string.k2go_fqr_show : R.string.k2go_hide);
    }

    private void updateOverlay(int percent, long speed) {
        if (overlayBar == null || overlayPct == null) return;
        final String rate = speed > 0 ? "  ·  " + humanRate(speed) : "";
        if (percent < 0) {
            if (!overlayBar.isIndeterminate()) setBarMode(true);
            overlayPct.setText(str(R.string.k2go_fqr_working) + rate);
        } else {
            if (overlayBar.isIndeterminate()) setBarMode(false);
            overlayBar.setProgressCompat(percent, true);
            overlayPct.setText(str(R.string.k2go_fqr_percent, percent) + rate);
        }
    }

    /** Switch the Material progress indicator between indeterminate/determinate. The indicator forbids
     *  the switch while visible, so toggle visibility around it. */
    private void setBarMode(boolean indeterminate) {
        if (overlayBar == null) return;
        int vis = overlayBar.getVisibility();
        overlayBar.setVisibility(View.GONE);
        overlayBar.setIndeterminate(indeterminate);
        overlayBar.setVisibility(vis == View.GONE ? View.VISIBLE : vis);
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
        LinearLayout sheet = new LinearLayout(themed);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(rounded(cSurface, 16f, true));   // rounded top corners, flat bottom
        sheet.setElevation(dp(12));
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(themed);
        title.setText(R.string.k2go_fqr_delete_list_title);
        title.setTextColor(cOnSurface);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(16);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(themed);
        close.setText("✕");
        close.setTextColor(cOnSurfaceVariant);
        close.setTextSize(18);
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setOnClickListener(v -> hideDeleteSheet());
        header.addView(close);
        sheet.addView(header);

        searchField = new EditText(themed);
        searchField.setHint(R.string.k2go_fqr_search_hint);
        searchField.setSingleLine(true);
        searchField.setTextColor(cOnSurface);
        searchField.setHintTextColor(cOnSurfaceVariant);
        LinearLayout.LayoutParams sflp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sflp.topMargin = dp(8);
        sheet.addView(searchField, sflp);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { renderRows(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });

        ScrollView sv = new ScrollView(themed);
        listContainer = new LinearLayout(themed);
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
            TextView t = new TextView(themed);
            t.setText(regions.isEmpty() ? R.string.k2go_fqr_empty : R.string.k2go_fqr_no_matches);
            t.setTextColor(cOnSurfaceVariant);
            t.setPadding(0, dp(14), 0, 0);
            listContainer.addView(t);
        }
    }

    private View regionRow(Region r) {
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));
        if (r.name.equals(highlight)) row.setBackgroundColor(ColorUtils.setAlphaComponent(cPrimary, 0x33));
        TextView name = new TextView(themed);
        name.setText(r.name);
        name.setTextColor(cOnSurface);
        name.setTextSize(14);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> flyTo(r));   // tapping a row flies the map behind to that region
        TextView x = new TextView(themed);
        x.setText("✕");
        x.setTextColor(cError);
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
        new MaterialAlertDialogBuilder(themed)
                .setTitle(str(R.string.k2go_fqr_delete_confirm_title, name))
                .setMessage(R.string.k2go_fqr_delete_confirm_msg)
                .setNegativeButton(R.string.k2go_cancel, null)
                .setPositiveButton(R.string.k2go_fqr_delete, (d, w) -> client.deleteRegion(name, new MapsRegionClient.DeleteListener() {
                    @Override public void onOk() {
                        toast(str(R.string.k2go_fqr_deleted, name));
                        highlight = null;
                        webView.reload();     // map redraws without the region
                        refreshRegions();     // and the list drops it
                    }
                    @Override public void onError(String m) {
                        new MaterialAlertDialogBuilder(themed).setTitle(R.string.k2go_fqr_delete_failed).setMessage(m)
                                .setPositiveButton(android.R.string.ok, null).show();
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
    /** Padded, transparent container for MaterialAlertDialog setView (the dialog paints the surface). */
    private LinearLayout dialogContent(int pad) {
        LinearLayout l = new LinearLayout(themed);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(pad, pad, pad, pad);
        return l;
    }

    /** Opaque, rounded Material surface for the floating overlay / bottom sheet (added via
     *  addContentView, so it carries its own surface). */
    private LinearLayout surfaceCard(int pad, float radius) {
        LinearLayout l = new LinearLayout(themed);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(pad, pad, pad, pad);
        l.setBackground(rounded(cSurfaceContainer, radius, false));
        l.setElevation(dp(8));
        return l;
    }

    private GradientDrawable rounded(int color, float radiusDp, boolean topOnly) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(radiusDp);
        if (topOnly) g.setCornerRadii(new float[]{ r, r, r, r, 0, 0, 0, 0 });
        else g.setCornerRadius(r);
        return g;
    }

    private void dismissDialog() {
        if (dialog != null) { try { dialog.dismiss(); } catch (Exception ignore) { } dialog = null; }
    }

    private void toast(String m) { Toast.makeText(activity, m, Toast.LENGTH_SHORT).show(); }

    private String str(int resId, Object... args) {
        return args.length == 0 ? activity.getString(resId) : activity.getString(resId, args);
    }

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
            "var observing=false;" +
            "function setObserve(on){try{if(on&&!observing){mo.observe(sr,{childList:true,subtree:true});observing=true;}else if(!on&&observing){mo.disconnect();observing=false;}}catch(e){}}" +
            // Delegated clicks: the trash tool opens our list; "Next" finalizes the extract name.
            "sr.addEventListener('click',function(ev){try{var path=ev.composedPath?ev.composedPath():[];" +
            "for(var i=0;i<path.length;i++){var el=path[i];if(!el)continue;" +
            // Read the tool's REAL state from the map cursor (crosshair = active) after the click,
            // so open/close mirrors the tool exactly (no flip-based desync/inversion).
            "if(el.title==='Choose region to delete'){setTimeout(function(){try{var on=host.map.getCanvas().style.cursor==='crosshair';setObserve(on);if(window.K2GoFQR&&K2GoFQR.onDeleteToolState){K2GoFQR.onDeleteToolState(on);}}catch(e){}},0);break;}" +
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
