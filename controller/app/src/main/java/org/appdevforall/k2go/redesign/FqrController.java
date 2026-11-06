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
 *               confirm/error dialogs use the shared BrandDialog (K2GO-385), buttons MaterialButton, and
 *               the progress bars the Material progress indicators. All user-facing text lives in string
 *               resources.
 *
 *               ADFA-5062: in the operation model (ADR-5061) this FQR region fetch is
 *               Operation.content("maps") — a CONTENT / LIVE operation. Unlike the banked ContentType
 *               members (ZIM, Books, Courses and the STOPPED maps-layer install of ContentType.MAPS),
 *               it is user-driven: it exists only when someone draws a region here and consents, so it
 *               is an Operation, not a ContentType. See ADR-5062-maps-two-operations.md.
 *
 *               K2GO-541: the progress overlay is a compact, DRAGGABLE card with a circular ring on
 *               the left (the ring is the percent; ETA + percent stack inside it over a ghost track)
 *               and a text column on the right (region name, "size : speed", controls). A tap on the
 *               ring minimizes it to just the ring, a floating button; dragging moves it anywhere on
 *               the map. The action button is Material vector icons: pause/resume while running, and
 *               Retry (refresh arrow) after a failure, next to a cancel X. A failed download is
 *               retryable, not fatal: phone networks drop often, so onError keeps the panel and offers
 *               Retry instead of dismissing (the server resumes from its checkpoint, K2GO-317). Type
 *               uses the K2Go type scale (TextAppearance.K2Go.*, K2GO-529) with the Atkinson face
 *               applied explicitly (the tokens set it via appcompat fontFamily, which framework
 *               setTextAppearance drops on a plain TextView); colours stay the M3 theme attributes
 *               above, so it follows light/dark.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import androidx.core.content.res.ResourcesCompat;
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
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate;
import org.appdevforall.k2go.ui.dialog.BrandDialog;
import org.appdevforall.k2go.util.M3Text;
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
    private final int cSurface, cSurfaceContainer, cSurfaceHighest, cOnSurface, cOnSurfaceVariant, cPrimary, cOnPrimary, cError;
    // K2GO-385 (C1): state colours for the download bar -- amber = stopped (halted, not live), leaf = done.
    // Fixed tokens (same in day/night), so a halted or finished job reads the same on either theme.
    private final int cAmber, cLeaf, cRingTrack;

    private volatile boolean active = false;   // written on UI thread, read on the WebView binder thread
    private AlertDialog dialog;       // "calculating" / consent (one at a time)
    private boolean estimateCanceled; // ADFA-5043: set when the user backs out of "Calculating…" so a late estimate is dropped
    // K2GO-541: a compact, DRAGGABLE progress card with a circular ring (the ring is the percent);
    // minimizes to just the ring. null when hidden. The drag position lives on the view's translation
    // (persists across minimize/expand, re-clamped to the screen), and resets when the overlay is rebuilt.
    private View overlay;
    private CircularProgressIndicator overlayRing;              // the ring = the percent
    private TextView overlayEta, overlayPct;                    // inside the ring: ETA + percent
    private TextView overlayStatus, overlaySize;   // header action word + the "size : speed" line
    private View overlayHeader, overlayMeta;       // chrome, hidden when minimized
    private String overlaySizeStr = "";                        // the size half of the "size : speed" line
    private boolean overlayMinimized = false;
    // ADFA-4896 / K2GO-317 / K2GO-541: Pause/Resume is an icon on the ring card. It drives the server
    // pause/resume endpoints; pause stops gracefully (SIGTERM) so pmtiles flushes its resume journal, and
    // resume continues from that checkpoint instead of restarting. overlayStopped (true = paused) is the
    // last state the poll reported and the SOLE writer of the icon; the tap only fires the verb.
    private ImageView overlayStopIcon;                          // pause (two bars) <-> resume (play)
    private View overlayStopBtn;
    private boolean overlayStopped = false;
    // K2GO-541: a failed download is retryable, not fatal. overlayFailed parks the panel in a "failed +
    // Retry" state; the poll (or Retry) clears it. dlName/dlBox keep the current download so Retry can
    // re-invoke it, and one reusable listener serves both the first attempt and every retry.
    private boolean overlayFailed = false;
    private String dlName, dlBox;
    private MapsRegionClient.DownloadListener dlListener;
    // K2GO-541: the K2Go type tokens carry Atkinson via appcompat `fontFamily`, which framework
    // setTextAppearance ignores on a plain TextView; applyOverlayType re-applies this face explicitly.
    private Typeface overlayFont;
    private View overlayGrip;   // K2GO-541: top drag handle (affordance); the whole card drags
    private int touchSlop;                                      // drag-vs-tap threshold

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
        this.cOnPrimary       = attr(com.google.android.material.R.attr.colorOnPrimary, 0xFF0B2A20);
        this.cError           = attr(androidx.appcompat.R.attr.colorError, 0xFFE05353);
        this.cAmber           = androidx.core.content.ContextCompat.getColor(themed, R.color.k2go_amber);
        this.cLeaf            = androidx.core.content.ContextCompat.getColor(themed, R.color.k2go_leaf);
        // K2GO-541: the ring's ghost track, visible from 0% so the shape reads before it fills.
        this.cRingTrack       = ColorUtils.blendARGB(cSurfaceContainer, cOnSurfaceVariant, 0.3f);
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
        String u = org.appdevforall.k2go.util.WebPath.pathOf(url);   // K2GO-395: one URL->path source
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
     *  second press dismisses the list (feels native); the sheet's X goes the other way — it disarms
     *  the tool (see closeDeleteSheetAndTool), which routes back here with on=false, so the two stay
     *  in lockstep. */
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
                errorDialog(R.string.k2go_fqr_estimate_error_title, message);
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
        // hpad 0: BrandDialog's content host already sits at the card's horizontal inset (double-inset
        // otherwise). Vertical 8dp keeps the 4dp grid (ADFA-5027).
        LinearLayout row = dialogContent(0, dp(8));
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
        dialog = new BrandDialog(themed)
                .setContentView(row)
                .setCancelable(true)
                .setOnCancel(() -> { estimateCanceled = true; resetMapSelection(); })
                .show()
                .getDialog();
    }

    private void showConsent(String name, String box, long transfer, long archive, long free, long freeAfter) {
        // ADFA-5027: 4dp-grid vertical breathing room. hpad 0: BrandDialog's content host already
        // carries the card's horizontal inset (aligned to the title), so no inset is added here.
        LinearLayout body = dialogContent(0, dp(8));

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

        // ADFA-5043: bailing out here (Not now / tap-outside) must also clear the map's FQR selection
        // + tool -- the same reset the name dialog's Cancel does -- or the crosshair and the drawn area
        // linger with a stale "download this region" button on top.
        dialog = new BrandDialog(themed)
                .setTitle(R.string.k2go_fqr_consent_title)
                .setContentView(body)
                // K2GO-395 (ADR-395): after the storage consent, gate the actual REST start on metered
                // cost. FQR is a user-driven Operation, not a banked ContentType, so ContentAdmission
                // does not cover it -- the gate goes here. Declined/offline resets the map selection
                // (there is no queue to fall back on), matching the negative/cancel below.
                .setPositive(R.string.k2go_fqr_download, () -> NetworkPolicyGate.guardHeavyStart(
                        activity, () -> startDownload(name), this::resetMapSelection))
                .setNegative(R.string.k2go_fqr_not_now, () -> resetMapSelection())
                .setOnCancel(() -> resetMapSelection())
                .setCancelable(true)
                .show()
                .getDialog();

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
        dlName = name;
        dlBox = box;
        showOverlay(name, pendingArchive);
        client.download(name, box, downloadListener());
    }

    /** K2GO-541: one reusable listener, so Retry can re-invoke the download on the same overlay. The poll
     *  (onProgress/onPaused/onDone) stays the SOLE writer of the running/paused visuals. onError no longer
     *  cancels and dismisses: phone networks drop often (Wi-Fi, router, cell handoff), so a failure parks
     *  the panel in a retryable failed state instead of throwing the user out. */
    private MapsRegionClient.DownloadListener downloadListener() {
        if (dlListener != null) return dlListener;
        dlListener = new MapsRegionClient.DownloadListener() {
            @Override public void onProgress(int percent, long speed, int etaSeconds) {
                if (overlayFailed) clearFailed();   // a live poll arrived: recovered on its own
                if (overlayStopped) {   // resumed/running again: back to Pause, live teal ring
                    overlayStopped = false;
                    setStopIcon(false);
                    setRingState(cPrimary, true);
                    if (overlayStatus != null) overlayStatus.setText(R.string.k2go_maps_dl_title);
                }
                setStopEnabled(true);   // re-enable after a Pause/Resume tap
                updateOverlay(percent, speed, etaSeconds);
            }
            @Override public void onPaused(int percent) {
                // K2GO-317: paused keeps the partial + resume journal (SIGTERM checkpoint), so resume
                // continues from here. Freeze the ring at the reached percent in amber and offer Resume;
                // a paused job never looks live.
                overlayStopped = true;
                setStopIcon(true);
                setStopEnabled(true);
                if (overlayStatus != null) overlayStatus.setText(R.string.k2go_dl_paused_notif);
                if (overlayEta != null) overlayEta.setText("");   // a paused job must not advertise a time-to-finish
                if (overlayRing != null && percent >= 0) overlayRing.setProgressCompat(percent, false);
                setRingState(cAmber, false);   // amber + dimmed: a paused job must not look live
            }
            @Override public void onDone() {
                setRingState(cLeaf, true);     // done: a full leaf ring reads as success
                updateOverlay(100, 0, -1);
                if (overlayStatus != null) overlayStatus.setText(R.string.k2go_fqr_region_added); // may be gone if minimized
                webView.postDelayed(() -> { hideOverlay(); webView.reload(); }, 1200);
            }
            @Override public void onError(String message) {
                if ("canceled".equals(message)) { hideOverlay(); return; }
                showFailed();   // keep the panel; offer Retry instead of dismissing
            }
        };
        return dlListener;
    }

    // ---- Floating progress overlay -----------------------------------------------------------
    // A draggable card. Header: the action ("Downloading map data" / "Paused") + a minimize icon.
    // Body: a circular ring on the left (the ring is the percent; ETA + percent sit inside it, over a
    // ghost track that fills), and a text column on the right (region name, "size : speed", controls).
    // A tap on the ring (or the minimize icon) collapses it to just the ring, a floating button; tapping
    // the ring again expands it. Dragging anywhere on the card (except the buttons) moves it; a top handle
    // marks the affordance.
    // Expanded card width. 330dp (+10% over the first 300dp) leaves headroom for locales whose strings run
    // longer than Spanish, so the name / "size : speed" / title have more room before they ellipsize.
    private static final int CARD_W_DP = 330;
    private void showOverlay(String name, long sizeBytes) {
        hideOverlay();
        overlayMinimized = false;
        overlayStopped = false;
        overlayFailed = false;
        overlaySizeStr = sizeBytes > 0 ? human(sizeBytes) : "";   // constant for this overlay; computed once
        touchSlop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        // The family resource did not render; load the regular face directly (all overlay roles are regular).
        if (overlayFont == null) overlayFont = ResourcesCompat.getFont(themed, R.font.atkinson_hyperlegible_regular);

        LinearLayout card = surfaceCard(dp(16), 24f);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setElevation(dp(10));

        // K2GO-541: a top grab handle signals the card is draggable (the whole card drags, below); a thin
        // target is hard to hit, so the affordance only has to be visible, not the hit area. Hidden minimized.
        View grip = new View(themed);
        GradientDrawable gripBg = new GradientDrawable();
        gripBg.setShape(GradientDrawable.RECTANGLE);
        gripBg.setCornerRadius(dp(2));
        gripBg.setColor(cOnSurfaceVariant);
        grip.setBackground(gripBg);
        grip.setAlpha(0.6f);
        LinearLayout.LayoutParams gripLp = new LinearLayout.LayoutParams(dp(48), dp(4));
        gripLp.bottomMargin = dp(10);
        card.addView(grip, gripLp);
        overlayGrip = grip;

        // Header: action word (left) + minimize icon (right).
        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        overlayStatus = new TextView(themed);
        overlayStatus.setText(R.string.k2go_maps_dl_title);
        overlayStatus.setSingleLine(true);
        overlayStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Title in onSurface at the Title type scale (18sp, the consent dialog title size), regular weight:
        // bold saturated the compact header. Not the teal accent: the ring carries the state colour.
        applyOverlayType(overlayStatus, R.style.TextAppearance_K2Go_Title, cOnSurface);
        header.addView(overlayStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView minBtn = new ImageView(themed);
        minBtn.setImageResource(R.drawable.ic_expand_more);
        minBtn.setColorFilter(cOnSurfaceVariant);
        int mpad = dp(4);
        minBtn.setPadding(mpad, mpad, mpad, mpad);
        minBtn.setContentDescription(str(R.string.k2go_hide));
        minBtn.setOnClickListener(v -> toggleMinimize());
        header.addView(minBtn, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(8);
        card.addView(header, headerLp);
        overlayHeader = header;

        // Body: ring (left) + text column (right).
        LinearLayout body = new LinearLayout(themed);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);

        // Ring + ETA / percent centered inside it. Determinate from 0 so the ghost track shows at once.
        FrameLayout ringFrame = new FrameLayout(themed);
        overlayRing = new CircularProgressIndicator(themed);
        overlayRing.setIndicatorSize(dp(108));
        overlayRing.setTrackThickness(dp(8));
        overlayRing.setTrackCornerRadius(dp(4));
        overlayRing.setIndicatorColor(cPrimary);
        overlayRing.setTrackColor(cRingTrack);
        overlayRing.setIndeterminate(false);
        overlayRing.setProgressCompat(0, false);
        ringFrame.addView(overlayRing, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout center = new LinearLayout(themed);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        // K2GO-541: let the ETA wrap inside the ring ("10 s" / "restantes") instead of one wide line, so a
        // longer phrase in another language stacks over the percent rather than overflowing. maxWidth keeps
        // the wrapped lines within the ring's interior.
        overlayEta = new TextView(themed);
        overlayEta.setGravity(Gravity.CENTER);
        overlayEta.setMaxLines(2);
        overlayEta.setMaxWidth(dp(84));
        applyOverlayType(overlayEta, R.style.TextAppearance_K2Go_Caption, cOnSurfaceVariant);
        overlayEta.setText(R.string.k2go_fqr_starting);   // ETA present from the start, held until done
        overlayPct = ringText(R.style.TextAppearance_K2Go_Title, cOnSurface);
        overlayPct.setText(str(R.string.k2go_fqr_percent, 0));
        // WRAP_CONTENT, not the vertical-LinearLayout default (MATCH_PARENT): a WRAP column with
        // MATCH_PARENT children collapses its width and clips the single-line text inside the ring.
        center.addView(overlayEta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        center.addView(overlayPct, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ringFrame.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        body.addView(ringFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Right column: region name, "size : speed", controls. A long name ellipsizes.
        LinearLayout meta = new LinearLayout(themed);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView overlayName = new TextView(themed);   // set once here; no later reads, so a local
        overlayName.setText(name);
        overlayName.setSingleLine(true);
        overlayName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        applyOverlayType(overlayName, R.style.TextAppearance_K2Go_Body, cOnSurface);
        meta.addView(overlayName);
        overlaySize = new TextView(themed);
        overlaySize.setSingleLine(true);
        overlaySize.setText(sizeLine(0));
        applyOverlayType(overlaySize, R.style.TextAppearance_K2Go_Caption, cOnSurfaceVariant);
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeLp.topMargin = dp(4);
        meta.addView(overlaySize, sizeLp);

        LinearLayout controls = new LinearLayout(themed);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout stopBtn = new FrameLayout(themed);
        stopBtn.setBackground(circle(cPrimary));
        stopBtn.setContentDescription(str(R.string.k2go_dl_pause));
        stopBtn.setOnClickListener(v -> onAction());
        overlayStopIcon = new ImageView(themed);
        overlayStopIcon.setImageResource(R.drawable.ic_pause_24);
        overlayStopIcon.setColorFilter(cOnPrimary);
        stopBtn.addView(overlayStopIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        controls.addView(stopBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        overlayStopBtn = stopBtn;
        ImageView cancel = new ImageView(themed);
        cancel.setImageResource(R.drawable.ic_close_24);
        cancel.setColorFilter(cError);
        int cpad = dp(12);
        cancel.setPadding(cpad, cpad, cpad, cpad);
        cancel.setContentDescription(str(R.string.k2go_cancel));
        cancel.setOnClickListener(v -> confirmCancel());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        cancelLp.leftMargin = dp(16);
        controls.addView(cancel, cancelLp);
        LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctrlLp.topMargin = dp(8);
        meta.addView(controls, ctrlLp);

        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        metaLp.leftMargin = dp(16);
        body.addView(meta, metaLp);
        overlayMeta = meta;
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Drag: the ring taps to minimize and drags to move; the rest of the card is one big drag area (the
        // buttons stay tappable because a clickable child consumes the touch first). Position persists across
        // minimize and is clamped to the screen.
        attachDrag(ringFrame, true);
        attachDrag(card, false);

        // Accessibility: the ring toggles minimize/expand on tap, but a touch listener is invisible to
        // TalkBack, and when minimized the labeled chevron is gone : the ring would be the only control with
        // no way to operate it. Expose the ring as a focusable button with a click action that toggles.
        ringFrame.setContentDescription(str(R.string.k2go_maps_dl_title));
        ViewCompat.setAccessibilityDelegate(ringFrame, new AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
                info.setClickable(true);
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) { toggleMinimize(); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(CARD_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(16);
        overlay = card;
        activity.addContentView(overlay, params);
    }

    /** A centered single-line text for the ring interior (percent). */
    private TextView ringText(int appearance, int color) {
        TextView t = new TextView(themed);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        applyOverlayType(t, appearance, color);
        return t;
    }

    /** K2GO-541: route overlay text through the K2Go type token AND re-apply the Atkinson face. The tokens
     *  set the font via appcompat `fontFamily`, which framework setTextAppearance ignores on a plain
     *  TextView, so the text would fall back to the system font. Every overlay role is regular weight. */
    private void applyOverlayType(TextView t, int appearanceRes, int color) {
        M3Text.apply(t, appearanceRes, color);
        if (overlayFont != null) t.setTypeface(overlayFont);
    }

    /** ADFA-4896 / K2GO-317: fire Pause or Resume. The poll (onPaused/onProgress) is the SOLE writer of
     *  the icon/state; here we only fire the verb and disable the button until the next poll confirms, so
     *  the control never flickers between the tap and the server catching up. */
    private void toggleStop() {
        setStopEnabled(false);
        if (overlayStopped) client.resume(); else client.pause();
    }

    /** K2GO-541: the action button is Retry when the download failed, otherwise Pause/Resume. */
    private void onAction() {
        if (overlayFailed) retryDownload();
        else toggleStop();
    }

    /** K2GO-541: a failed download is retryable, not fatal. Keep the panel up, freeze the ring red at the
     *  reached percent, and turn the action button into Retry (refresh arrow). The X still cancels and
     *  dismisses. Retry re-invokes the download; the server resumes from its checkpoint (K2GO-317). */
    private void showFailed() {
        overlayFailed = true;
        overlayStopped = false;
        if (overlayMinimized) toggleMinimize();   // expand so Retry and the "failed" word are visible
        setStopEnabled(true);
        if (overlayStopIcon != null) overlayStopIcon.setImageResource(R.drawable.ic_refresh);
        if (overlayStopBtn != null) overlayStopBtn.setContentDescription(str(R.string.k2go_dl_retry));
        if (overlayStatus != null) overlayStatus.setText(R.string.k2go_fqr_download_failed);
        if (overlayEta != null) overlayEta.setText("");   // no time-to-finish on a stopped job
        setRingState(cError, false);   // red + dimmed: the job is not running
    }

    private void retryDownload() {
        setStopEnabled(false);   // guard a double tap until the first poll or error confirms
        clearFailed();
        if (overlayEta != null) overlayEta.setText(R.string.k2go_fqr_starting);
        client.download(dlName, dlBox, downloadListener());
    }

    /** Restore the running look (live teal ring, Pause icon, "Downloading" word) out of the failed state. */
    private void clearFailed() {
        overlayFailed = false;
        setStopIcon(false);
        if (overlayStatus != null) overlayStatus.setText(R.string.k2go_maps_dl_title);
        setRingState(cPrimary, true);
    }

    /** K2GO-317: canceling discards the partial, so the X first PAUSES the download (the partial and its
     *  resume journal stay safe, and no more data is spent while the user decides), then confirms. "Cancel"
     *  cancels from that paused point; "Keep downloading" resumes from it. pause/resume are no-ops when the
     *  job is not running, so a tap on the X of a paused or failed panel still just offers the choice. */
    private void confirmCancel() {
        client.pause();
        new BrandDialog(themed)
                .setTitle(R.string.k2go_dl_cancel_title)
                .setDestructive(R.string.k2go_cancel, () -> { client.cancel(); hideOverlay(); })
                .setNegative(R.string.k2go_dl_cancel_keep, () -> client.resume())
                .show();
    }

    private void setStopEnabled(boolean enabled) {
        if (overlayStopBtn != null) {
            overlayStopBtn.setEnabled(enabled);
            overlayStopBtn.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    /** paused=true shows Resume (play); false shows Pause (two bars). Called only by the poll. */
    private void setStopIcon(boolean paused) {
        if (overlayStopIcon != null) {
            overlayStopIcon.setImageResource(paused ? R.drawable.ic_play_arrow_24 : R.drawable.ic_pause_24);
        }
        if (overlayStopBtn != null) {
            overlayStopBtn.setContentDescription(str(paused ? R.string.k2go_dl_resume : R.string.k2go_dl_pause));
        }
    }

    /** Toggle expanded <-> minimized (only the ring). Minimized: the ring shrinks and shows just the
     *  percent; the grip, name/size and controls hide; the card becomes a circular floating button. */
    private void toggleMinimize() {
        if (overlay == null || overlayRing == null) return;
        int[] before = ringCenterOnScreen();   // keep the ring visually anchored across the size change
        overlayMinimized = !overlayMinimized;
        int chrome = overlayMinimized ? View.GONE : View.VISIBLE;
        if (overlayGrip != null) overlayGrip.setVisibility(chrome);
        if (overlayHeader != null) overlayHeader.setVisibility(chrome);
        if (overlayMeta != null) overlayMeta.setVisibility(chrome);
        if (overlayEta != null) overlayEta.setVisibility(chrome);   // minimized ring shows only the percent
        overlayRing.setIndicatorSize(dp(overlayMinimized ? 64 : 108));
        overlayRing.setTrackThickness(dp(overlayMinimized ? 6 : 8));
        applyOverlayType(overlayPct,
                overlayMinimized ? R.style.TextAppearance_K2Go_Label
                                 : R.style.TextAppearance_K2Go_Title,
                cOnSurface);
        ViewGroup.LayoutParams lp = overlay.getLayoutParams();
        lp.width = overlayMinimized ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(CARD_W_DP);
        overlay.setLayoutParams(lp);
        LinearLayout card = (LinearLayout) overlay;
        int pad = dp(overlayMinimized ? 8 : 16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(rounded(cSurfaceContainer, overlayMinimized ? 100f : 24f, false));
        // The card re-centers (CENTER_HORIZONTAL) and the ring resizes, so the same translation would land
        // the ring somewhere else. Shift the translation by the ring's on-screen move so it stays put, then
        // clamp back on-screen.
        overlay.post(() -> {
            if (overlay == null || overlayRing == null) return;
            int[] after = ringCenterOnScreen();
            overlay.setTranslationX(overlay.getTranslationX() + (before[0] - after[0]));
            overlay.setTranslationY(overlay.getTranslationY() + (before[1] - after[1]));
            clampToParent();
        });
    }

    /** The ring's center in screen coordinates (includes the current translation and the live ring size). */
    private int[] ringCenterOnScreen() {
        int[] p = new int[2];
        overlayRing.getLocationOnScreen(p);
        p[0] += overlayRing.getWidth() / 2;
        p[1] += overlayRing.getHeight() / 2;
        return p;
    }

    private void updateOverlay(int percent, long speed, int etaSeconds) {
        if (overlayRing == null) return;
        if (overlaySize != null) overlaySize.setText(sizeLine(speed));   // "708 MB : 1.3 MB/s"
        if (overlayEta != null) {
            String eta = etaPlain(etaSeconds);
            if (!eta.isEmpty()) overlayEta.setText(eta);        // hold the last known ETA; never blank mid-download
            else if (percent >= 100) overlayEta.setText("");    // done: clear the estimate
        }
        if (percent >= 0) {                                     // hold the ring + percent on an unknown poll
            overlayRing.setProgressCompat(percent, true);
            overlayPct.setText(str(R.string.k2go_fqr_percent, percent));
        }
    }

    /** The "size : speed" line under the name ("708 MB : 1.3 MB/s"); size alone until the speed arrives. */
    private String sizeLine(long speed) {
        String r = humanRate(speed);
        if (r.isEmpty()) return overlaySizeStr;
        return overlaySizeStr.isEmpty() ? r : overlaySizeStr + " · " + r;
    }

    /** K2GO-317: ETA as its own line inside the ring ("45 s left" / "~3 min"); empty when unknown (<0)
     *  or done (0). Reuses the shared eta strings, so no new copy or l10n debt. */
    private String etaPlain(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return str(R.string.k2go_eta_sec, seconds);
        return str(R.string.k2go_eta_minutes_fmt, (seconds + 30) / 60);
    }

    /** K2GO-385 (C1): colour the ring by state and dim it when the job is not live (teal = downloading,
     *  amber = paused, leaf = done), so a stopped job never looks like a running one. */
    private void setRingState(int color, boolean live) {
        if (overlayRing == null) return;
        overlayRing.setIndicatorColor(color);
        overlayRing.setAlpha(live ? 1f : 0.55f);
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    // K2GO-541: drag the overlay by moving its translation, clamped to the content area. The position
    // lives on the view (persists across minimize/expand) and resets when the overlay is rebuilt. A tap
    // (no drag) on the ring toggles minimize; the grip only drags.
    private void attachDrag(View handle, boolean tapToggles) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startTx, startTy;
            private boolean dragging;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (overlay == null) return false;
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startTx = overlay.getTranslationX(); startTy = overlay.getTranslationY();
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) < touchSlop) return true;
                        dragging = true;
                        overlay.setTranslationX(clampTx(startTx + dx));
                        overlay.setTranslationY(clampTy(startTy + dy));
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!dragging && tapToggles) toggleMinimize();   // a real lift, not a canceled gesture
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void clampToParent() {
        if (overlay == null) return;
        overlay.setTranslationX(clampTx(overlay.getTranslationX()));
        overlay.setTranslationY(clampTy(overlay.getTranslationY()));
    }

    private float clampTx(float tx) {
        if (!(overlay.getParent() instanceof View)) return tx;
        View parent = (View) overlay.getParent();
        float min = -overlay.getLeft();
        float max = parent.getWidth() - overlay.getRight();
        if (max < min) max = min;
        return Math.max(min, Math.min(max, tx));
    }

    private float clampTy(float ty) {
        if (!(overlay.getParent() instanceof View)) return ty;
        View parent = (View) overlay.getParent();
        float min = -overlay.getTop();
        float max = parent.getHeight() - overlay.getBottom();
        if (max < min) max = min;
        return Math.max(min, Math.min(max, ty));
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
        overlay = null; overlayRing = null; overlayEta = null; overlayPct = null;
        overlayStatus = null; overlaySize = null;
        overlayHeader = null; overlayMeta = null;
        overlayStopIcon = null; overlayStopBtn = null;
        overlayGrip = null;
        overlayMinimized = false;
        overlayStopped = false;
        overlayFailed = false;
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
        close.setOnClickListener(v -> closeDeleteSheetAndTool());
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
        // K2GO-385: a destructive region delete uses the shared BrandDialog (clay outlined destructive +
        // text Cancel), like the other from-scratch/erase confirms, instead of a flat MaterialAlertDialog
        // with two equal text buttons. themed (not the host activity) carries Theme_K2Go, which the dialog
        // frame needs -- PortalActivity's own theme is not K2Go.
        new BrandDialog(themed)
                .setTitle(str(R.string.k2go_fqr_delete_confirm_title, name))
                .setMessage(R.string.k2go_fqr_delete_confirm_msg)
                .setDestructive(R.string.k2go_fqr_delete, () -> client.deleteRegion(name, new MapsRegionClient.DeleteListener() {
                    @Override public void onOk() {
                        toast(str(R.string.k2go_fqr_deleted, name));
                        highlight = null;
                        webView.reload();     // map redraws without the region
                        refreshRegions();     // and the list drops it
                    }
                    @Override public void onError(String m) {
                        errorDialog(R.string.k2go_fqr_delete_failed, m);
                    }
                }))
                .setNegative(R.string.k2go_cancel, null)
                .show();
    }

    private void hideDeleteSheet() {
        if (deleteSheet == null) return;
        ViewGroup parent = (ViewGroup) deleteSheet.getParent();
        if (parent != null) parent.removeView(deleteSheet);
        deleteSheet = null; searchField = null; listContainer = null; highlight = null;
    }

    /** ADFA-4884 follow-up: closing the list via its X now also disarms the map's trash tool, so the
     *  tool and the list stay in lockstep (before, X hid the list but left the tool armed, so tapping
     *  a region reopened it). We ask the map to toggle its tool off — that fires onDeleteToolState(false),
     *  which hides the list — and also hide here directly so the list still closes if the JS can't reach
     *  the tool. deleteToolOn is cleared so native state matches even in that fallback. */
    private void closeDeleteSheetAndTool() {
        webView.evaluateJavascript("window.__k2goDisarmDelete&&window.__k2goDisarmDelete();", null);
        deleteToolOn = false;
        hideDeleteSheet();
    }

    // ---- helpers -----------------------------------------------------------------------------
    /** K2GO-385: a single-button (OK) error dialog on the shared BrandDialog, so the three FQR error
     *  cases (estimate / download / delete) share one recipe. */
    private void errorDialog(int titleRes, CharSequence message) {
        new BrandDialog(themed)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositive(android.R.string.ok, (BrandDialog.OnClick) null)
                .show();
    }

    /** Padded, transparent container for the dialog body (BrandDialog paints the surface). */
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
                .make(webView, m, org.appdevforall.k2go.util.SnackbarDuration.millisForText(m))
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
            // ADFA-4884: the map's trash-tool button, matched by its title in one place (the click
            // listener reads it; __k2goDisarmDelete finds it to toggle off).
            "var DEL_TITLE='Choose region to delete';" +
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
            "if(el.title===DEL_TITLE){setTimeout(function(){try{var on=host.map.getCanvas().style.cursor==='crosshair';setObserve(on);if(window.K2GoFQR&&K2GoFQR.onDeleteToolState){K2GoFQR.onDeleteToolState(on);}}catch(e){}},0);break;}" +
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
            // ADFA-4884 follow-up: native calls this when our list's X is pressed, to turn the map's
            // trash tool OFF too (before, X only closed our list and left the tool armed). Only act when
            // it's actually armed (crosshair), then click the same toolbar button the user would — that
            // reuses the existing click path, which fires onDeleteToolState(false).
            "window.__k2goDisarmDelete=function(){try{" +
            "var on=host.map&&host.map.getCanvas&&host.map.getCanvas().style.cursor==='crosshair';" +
            "if(!on)return false;" +
            "var btn=sr.querySelector('[title=\"'+DEL_TITLE+'\"]');" +
            "if(btn){btn.click();return true;}return false;" +
            "}catch(e){return false;}};" +
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
