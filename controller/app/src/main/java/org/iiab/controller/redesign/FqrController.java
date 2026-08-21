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
 *
 *               ADFA-5062: in the operation model (ADR-5061) this FQR region fetch is
 *               Operation.content("maps") — a CONTENT / LIVE operation. Unlike the banked ContentType
 *               members (ZIM, Books, Courses and the STOPPED maps-layer install of ContentType.MAPS),
 *               it is user-driven: it exists only when someone draws a region here and consents, so it
 *               is an Operation, not a ContentType. See ADR-5062-maps-two-operations.md.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import org.iiab.controller.util.M3Text;
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
    private boolean estimateCanceled; // ADFA-5043: set when the user backs out of "Calculating…" so a late estimate is dropped
    private View overlay;             // floating progress card (null when hidden)
    private LinearProgressIndicator overlayBar;
    private TextView overlayPct, overlayTitle;
    private TextView overlayMin;
    private boolean overlayMinimized = false;
    // ADFA-4896: the Stop/Retry control on the overlay. It drives the server pause/resume endpoints,
    // but there is no maps checkpoint yet (resume re-extracts from 0), so we present it honestly as
    // Stop -> Retry, not Pause -> Resume. overlayStopped is the last state the poll reported.
    private com.google.android.material.button.MaterialButton overlayStop;
    private boolean overlayStopped = false;

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
        if (active) { deleteToolOn = false; webView.evaluateJavascript(BRIDGE_JS, null); pushRegionNames(); }
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

    /** ADFA-5025: the bridge JS caught a duplicate name at "Next" and returned to the name field;
     *  tell the user why with a Snackbar (duration scaled to reading time, per the app convention). */
    @JavascriptInterface
    public void onNameTaken(String name) {
        if (!active) return;
        final String n = name == null ? "" : name.trim();
        activity.runOnUiThread(() -> snackbar(str(R.string.k2go_fqr_name_taken, n)));
    }

    private void handleExtract(String name, String box) {
        // ADFA-5025: reached only for a FREE name — the injected bridge JS filters out a name that
        // already exists (window.__k2goRegions) at "Next" and never calls onExtractRequested for it.
        if (!validName(name) || !validBox(box)) {
            toast(str(R.string.k2go_fqr_invalid));
            return;
        }
        // "Calculating size…" while the server runs its dry-run. ADFA-5043: arm the cancel flag so a
        // bail-out here (the dialog is cancelable) drops the late estimate instead of popping consent.
        estimateCanceled = false;
        showCalculating();
        client.estimate(box, new MapsRegionClient.EstimateListener() {
            @Override public void onEstimate(long transfer, long archive, long free, long freeAfter) {
                if (estimateCanceled) return;   // user backed out of "Calculating…"; map already reset
                dismissDialog();
                showConsent(name, box, transfer, archive, free, freeAfter);
            }
            @Override public void onError(String message) {
                if (estimateCanceled) return;
                dismissDialog();
                new MaterialAlertDialogBuilder(themed)
                        .setTitle(R.string.k2go_fqr_estimate_error_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    /** ADFA-5025: fetch the existing region names and hand them to the bridge JS, so the "Next"
     *  handler can reject a duplicate name synchronously (no round-trip, no raw-command flash). */
    private void pushRegionNames() {
        client.listRegions(new MapsRegionClient.RegionsListener() {
            @Override public void onRegions(JSONObject regions) {
                JSONArray names = new JSONArray();
                for (Iterator<String> it = regions.keys(); it.hasNext(); ) names.put(it.next());
                webView.evaluateJavascript("window.__k2goSetRegions&&window.__k2goSetRegions(" + names + ");", null);
            }
            @Override public void onError(String m) { /* leave the JS list as-is; the server still guards at download */ }
        });
    }

    /** ADFA-5043: tell the map to cancel the in-progress FQR selection (clear the drawn area + turn the
     *  extract tool off), mirroring the name dialog's own Cancel. Called when the user bails out of the
     *  native estimate/consent step, which otherwise leaves the crosshair + selection armed. No-op if the
     *  bridge isn't present (non-maps page). */
    private void resetMapSelection() {
        if (webView != null) {
            webView.evaluateJavascript("window.__k2goCancelExtract&&window.__k2goCancelExtract();", null);
        }
    }

    // ---- Consent -----------------------------------------------------------------------------
    private void showCalculating() {
        dismissDialog();
        LinearLayout row = dialogContent(dp(24));   // ADFA-5027: M3 dialog inset (4dp grid)
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        CircularProgressIndicator spin = new CircularProgressIndicator(themed);
        spin.setIndeterminate(true);
        spin.setIndicatorSize(dp(28));
        row.addView(spin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(themed);
        t.setText(R.string.k2go_fqr_calculating);
        t.setPadding(dp(16), 0, 0, 0);
        M3Text.apply(t, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, cOnSurface);
        row.addView(t);
        // ADFA-5043: canceling while estimating must drop the pending estimate AND clear the map's
        // selection — otherwise the crosshair lingers and a late estimate could still pop the consent.
        dialog = new MaterialAlertDialogBuilder(themed)
                .setView(row)
                .setCancelable(true)
                .setOnCancelListener(d -> { estimateCanceled = true; resetMapSelection(); })
                .show();
    }

    private void showConsent(String name, String box, long transfer, long archive, long free, long freeAfter) {
        // ADFA-5027: M3 dialog spacing — content aligned to the title (24dp) with 4dp-grid vertical
        // breathing room, so it isn't cramped against the edges/title/buttons.
        LinearLayout body = dialogContent(dp(24), dp(8));

        TextView sub = new TextView(themed);
        sub.setText(str(R.string.k2go_fqr_consent_sub, name, human(transfer), human(archive)));
        M3Text.apply(sub, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, cOnSurface);
        sub.setPadding(0, 0, 0, dp(16));
        body.addView(sub);

        // Free-space bar: how much of the current free space this region takes. Rounded into an M3
        // pill and clipped so the used/free segments follow the corners.
        LinearLayout bar = new LinearLayout(themed);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        long denom = Math.max(free, archive + freeAfter);
        if (denom <= 0) denom = 1;
        View used = new View(themed);
        used.setBackgroundColor(cPrimary);        // this region (leaf)
        View freeSeg = new View(themed);
        freeSeg.setBackgroundColor(cSurfaceHighest); // free after
        int h = dp(16);
        bar.setBackground(rounded(cSurfaceHighest, 8f, false));   // pill: half of the 16dp height
        bar.setClipToOutline(true);
        bar.addView(used, new LinearLayout.LayoutParams(0, h, Math.max(1f, archive)));
        bar.addView(freeSeg, new LinearLayout.LayoutParams(0, h, Math.max(1f, Math.max(0, denom - archive))));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        barLp.topMargin = dp(4);
        body.addView(bar, barLp);

        TextView legend = new TextView(themed);
        legend.setText(str(R.string.k2go_fqr_consent_legend, human(archive), human(Math.max(0, freeAfter))));
        M3Text.apply(legend, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, cOnSurfaceVariant);
        legend.setPadding(0, dp(12), 0, 0);
        body.addView(legend);

        // ADFA-4884: warn when the region wouldn't fit (negative free-after = disk almost full).
        if (freeAfter < 0) {
            TextView warn = new TextView(themed);
            warn.setText(str(R.string.k2go_fqr_consent_wont_fit, human(-freeAfter)));
            M3Text.apply(warn, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, cError);
            warn.setPadding(0, dp(12), 0, 0);
            body.addView(warn);
        }

        dialog = new MaterialAlertDialogBuilder(themed)
                .setTitle(R.string.k2go_fqr_consent_title)
                .setView(body)
                // ADFA-5043: bailing out here (Not now / tap-outside) must also clear the map's FQR
                // selection + tool — the same reset the name dialog's Cancel does — or the crosshair and
                // the drawn area linger with a stale "download this region" button on top.
                .setNegativeButton(R.string.k2go_fqr_not_now, (d, w) -> { d.dismiss(); resetMapSelection(); })
                .setPositiveButton(R.string.k2go_fqr_download, (d, w) -> startDownload(name))
                .setOnCancelListener(d -> resetMapSelection())
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
            @Override public void onProgress(int percent, long speed) {
                if (overlayStopped) {   // retried/running again: back to Stop
                    overlayStopped = false;
                    if (overlayStop != null) overlayStop.setText(R.string.k2go_clone_stop_confirm);
                }
                if (overlayStop != null) overlayStop.setEnabled(true);   // re-enable after a Stop/Retry tap
                updateOverlay(percent, speed);
            }
            @Override public void onPaused(int percent) {
                // Server phase is 'paused', but with no checkpoint this is a full stop: show "Stopped"
                // and offer Retry (which re-extracts from 0). No percent -- it would imply a resume
                // point that doesn't exist.
                overlayStopped = true;
                if (overlayStop != null) { overlayStop.setText(R.string.k2go_dl_retry); overlayStop.setEnabled(true); }
                if (overlayPct != null) overlayPct.setText(R.string.k2go_card_stopped);
                // #2: freeze the bar (determinate, no animation) so it doesn't keep animating under "Stopped".
                if (overlayBar != null && overlayBar.isIndeterminate()) setBarMode(false);
                if (overlayBar != null && percent >= 0) overlayBar.setProgressCompat(percent, false);
            }
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
        // ADFA-5027: M3 title role (carries its own medium weight — no manual BOLD).
        M3Text.apply(overlayTitle, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, cOnSurface);
        top.addView(overlayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        overlayMin = new TextView(themed);
        overlayMin.setText(R.string.k2go_hide);   // minimize to a compact card; tap again to Show
        M3Text.apply(overlayMin, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, cPrimary);
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
        M3Text.apply(overlayPct, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, cOnSurfaceVariant);
        row.addView(overlayPct, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // ADFA-4896: Stop/Retry beside Cancel. The label follows the reported state; the tap fires the
        // matching verb and the poll (onPaused/onProgress) is the source of truth.
        overlayStopped = false;
        overlayStop = new MaterialButton(themed, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        overlayStop.setText(R.string.k2go_clone_stop_confirm);
        overlayStop.setOnClickListener(v -> toggleStop());
        row.addView(overlayStop);
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

    /** ADFA-4896: fire Stop (server pause) or Retry (server resume — re-extracts from 0, no maps
     *  checkpoint). The poll (onPaused/onProgress) is the SOLE writer of the label/state; here we only
     *  fire the verb and disable the button until the next poll confirms, so the label never flickers
     *  between the tap and the server catching up. */
    private void toggleStop() {
        if (overlayStop != null) overlayStop.setEnabled(false);
        if (overlayStopped) client.resume(); else client.pause();
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
        overlayStop = null;
        overlayMinimized = false;
        overlayStopped = false;
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
        sheet.setPadding(dp(16), dp(16), dp(16), dp(12));   // ADFA-5027: 4dp grid

        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(themed);
        title.setText(R.string.k2go_fqr_delete_list_title);
        // ADFA-5027: M3 title role (medium weight built in — no manual BOLD/sp size).
        M3Text.apply(title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, cOnSurface);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // ADFA-5027: official M3 close icon instead of a "✕" text glyph — homologated with the Kiwix
        // manager (KiwixManageController), which already uses ic_close_24. Close ≠ delete.
        ImageView close = new ImageView(themed);
        close.setImageResource(R.drawable.ic_close_24);
        close.setColorFilter(cOnSurfaceVariant);
        int closePad = dp(6);
        close.setPadding(closePad, closePad, closePad, closePad);
        close.setContentDescription(activity.getString(R.string.k2go_cancel));
        close.setOnClickListener(v -> hideDeleteSheet());
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
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
        row.setPadding(dp(12), dp(12), dp(12), dp(12));   // ADFA-5027: 4dp grid
        if (r.name.equals(highlight)) row.setBackgroundColor(ColorUtils.setAlphaComponent(cPrimary, 0x33));
        TextView name = new TextView(themed);
        name.setText(r.name);
        // ADFA-5027: M3 list-item primary text role.
        M3Text.apply(name, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, cOnSurface);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> flyTo(r));   // tapping a row flies the map behind to that region
        // ADFA-5027: official M3 delete (trash) icon in the error colour instead of a red "✕" glyph —
        // homologated with the Kiwix manager's per-row delete (KiwixManageController uses ic_delete_24).
        ImageView del = new ImageView(themed);
        del.setImageResource(R.drawable.ic_delete_24);
        del.setColorFilter(cError);
        int delPad = dp(8);
        del.setPadding(delPad, delPad, delPad, delPad);
        del.setContentDescription(str(R.string.k2go_fqr_delete_confirm_title, r.name));
        del.setOnClickListener(v -> confirmDelete(r.name));
        row.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
    private LinearLayout dialogContent(int pad) { return dialogContent(pad, pad); }

    /** Vertical content holder with horizontal/vertical insets (4dp grid). */
    private LinearLayout dialogContent(int hpad, int vpad) {
        LinearLayout l = new LinearLayout(themed);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(hpad, vpad, hpad, vpad);
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

    /** ADFA-5025: standardized Snackbar with reading-time duration (util.SnackbarDuration). */
    private void snackbar(String m) {
        com.google.android.material.snackbar.Snackbar
                .make(webView, m, org.iiab.controller.util.SnackbarDuration.millisForText(m))
                .show();
    }

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
            // ADFA-5025: on Next, check the name against the existing regions (pushed from native via
            // __k2goSetRegions). Duplicate -> click the map's "Back" to return to the name field (so the
            // user just edits the name and retries) and tell native to show a Snackbar; the raw command
            // is never shown. Free -> hide the raw-command popup and hand off to native as before.
            "function fireExtract(){try{var pre=sr.querySelector('.maplibregl-popup pre');if(!pre)return;" +
            "var m=(pre.textContent||'').match(EX);if(!m)return;var nm=m[1];" +
            // The map's popup buttons have no stable id/class (verified live: FQRegionsControl builds
            // plain <button>s), so we match "Back" by text. If it isn't found, fall back to hiding the
            // popup so the raw tile-extract command is never left on screen.
            "if(window.__k2goRegions&&window.__k2goRegions.indexOf(nm)>=0){" +
            "var pop=pre.closest?pre.closest('.maplibregl-popup'):null;var backed=false;" +
            "if(pop){var bs=pop.querySelectorAll('button');for(var i=0;i<bs.length;i++){if((bs[i].textContent||'').trim()==='Back'){bs[i].click();backed=true;break;}}}" +
            "if(!backed)hidePop(pre);" +
            "if(window.K2GoFQR&&K2GoFQR.onNameTaken){K2GoFQR.onNameTaken(nm);}return;}" +
            "hidePop(pre);" +
            "if(window.K2GoFQR&&K2GoFQR.onExtractRequested){K2GoFQR.onExtractRequested(nm,m[2]);}}catch(e){}}" +
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
            // ADFA-5025: native pushes the existing region names here so fireExtract can reject a
            // duplicate name synchronously (see the Next handler).
            "window.__k2goSetRegions=function(arr){try{window.__k2goRegions=arr||[];}catch(e){}};" +
            // ADFA-5043: native calls this when the user bails out of the estimate/consent step, to cancel
            // the FQR selection the same way the name dialog's Cancel does. Buttons have no stable id/class
            // (same as fireExtract), so match by text. We hid the popup on Next, but a programmatic click
            // still fires on a display:none button. Prefer a direct Cancel; else go Back to the name stage
            // (where the working Cancel lives) and click it there; else fall back to the popup close (×).
            "window.__k2goCancelExtract=function(){try{" +
            "function pop(){var ps=sr.querySelectorAll('.maplibregl-popup');return ps.length?ps[ps.length-1]:null;}" +
            "function clickText(p,txts){if(!p)return false;var bs=p.querySelectorAll('button');" +
            "for(var j=0;j<bs.length;j++){var t=(bs[j].textContent||'').trim();for(var k=0;k<txts.length;k++){if(t===txts[k]){bs[j].click();return true;}}}return false;}" +
            "var p=pop();if(!p)return false;" +
            "if(clickText(p,['Cancel','Cancelar','Close']))return true;" +
            "if(clickText(p,['Back'])){setTimeout(function(){try{var q=pop();if(q&&!clickText(q,['Cancel','Cancelar','Close'])){var cb=q.querySelector('.maplibregl-popup-close-button');if(cb)cb.click();}}catch(e){}},50);return true;}" +
            "var cb=p.querySelector('.maplibregl-popup-close-button');if(cb){cb.click();return true;}" +
            "return false;}catch(e){return false;}};" +
            "console.log('K2Go-FQR bridge armed');" +
            "}catch(e){try{console.log('K2Go-FQR fatal '+e);}catch(_){}}})();";
}
