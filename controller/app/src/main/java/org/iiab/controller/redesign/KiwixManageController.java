/*
 * ============================================================================
 * Name        : KiwixManageController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5004. In-WebView management of installed Kiwix ZIMs, mirroring the Maps FQR
 *               region manager (FqrController) but for the "Read Wikipedia" page. Because that page
 *               is kiwix-serve (upstream) and carries no delete control of its own, this overlays a
 *               NATIVE trash button on the shared PortalActivity WebView, shown only while the
 *               /kiwix/ page is loaded. Tapping it opens a ~45% bottom sheet (title + search + the
 *               installed-ZIM list, each row with a "✕" delete in the M3 error colour) backed by the
 *               REST endpoints (KiwixClient GET /kiwix/library, POST /kiwix/delete). After a delete
 *               the WebView reloads so kiwix-serve stops showing the removed book.
 *
 *               Lifecycle mirrors FqrController: PortalActivity forwards onPageFinished(url) (arm on
 *               the kiwix page, disarm elsewhere) and onDestroy -> detach(). Theming uses a
 *               ContextThemeWrapper(Theme_K2Go) + Material 3 colour roles, like FqrController.
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
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.iiab.controller.R;
import org.iiab.controller.util.ByteFormatter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KiwixManageController {

    private final Activity activity;
    private final WebView webView;
    private final Context themed;
    private final int cSurface, cOnSurface, cOnSurfaceVariant, cError;

    private static final float SHEET_FRACTION = 0.45f;   // overlay height; content stays visible above

    private boolean active = false;
    private FloatingActionButton trigger;   // native trash button (only on the kiwix page)
    private View deleteSheet;
    private EditText searchField;
    private LinearLayout listContainer;
    private final List<JSONObject> items = new ArrayList<>();

    public KiwixManageController(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.themed = new ContextThemeWrapper(activity, R.style.Theme_K2Go);
        this.cSurface          = attr(com.google.android.material.R.attr.colorSurface, 0xFF16201B);
        this.cOnSurface        = attr(com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        this.cOnSurfaceVariant = attr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF8FA39B);
        this.cError            = attr(androidx.appcompat.R.attr.colorError, 0xFFE05353);
    }

    private int attr(int attrId, int fallback) {
        return MaterialColors.getColor(themed, attrId, fallback);
    }

    // ---- lifecycle (forwarded by PortalActivity) --------------------------------------------

    /** Arm on the box's /kiwix/ page, disarm (and drop UI) elsewhere. */
    public void onPageFinished(String url) {
        active = isKiwixPage(url);
        if (active) {
            showTrigger();
        } else {
            hideSheet();
            hideTrigger();
        }
    }

    /** Host activity going away: drop the button + sheet so we don't leak it. */
    public void detach() {
        hideSheet();
        hideTrigger();
    }

    /** True when the URL's path is under the box's kiwix reader. Pure string parsing (no
     *  android.net.Uri) so it is unit-testable and dependency-free. */
    static boolean isKiwixPage(String url) {
        if (url == null) return false;
        String u = url;
        int hash = u.indexOf('#'); if (hash >= 0) u = u.substring(0, hash);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int scheme = u.indexOf("://");
        if (scheme >= 0) { int slash = u.indexOf('/', scheme + 3); u = slash >= 0 ? u.substring(slash) : "/"; }
        return u.equals("/kiwix") || u.startsWith("/kiwix/");
    }

    // ---- native trigger button ---------------------------------------------------------------

    private void showTrigger() {
        if (trigger != null) return;
        FloatingActionButton fab = new FloatingActionButton(themed);
        fab.setImageResource(R.drawable.ic_delete_24);
        fab.setContentDescription(activity.getString(R.string.k2go_zim_manage_action));
        fab.setOnClickListener(v -> openSheet());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(88);   // clear the portal's bottom toolbar
        trigger = fab;
        activity.addContentView(fab, lp);
    }

    private void hideTrigger() {
        if (trigger == null) return;
        ViewGroup parent = (ViewGroup) trigger.getParent();
        if (parent != null) parent.removeView(trigger);
        trigger = null;
    }

    // ---- overlay list ------------------------------------------------------------------------

    private void openSheet() {
        hideTrigger();   // avoid overlapping the sheet; restored when the sheet closes
        if (deleteSheet == null) buildSheet();
        refresh();
    }

    private void buildSheet() {
        LinearLayout sheet = new LinearLayout(themed);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(rounded(cSurface, 16f, true));   // rounded top, flat bottom
        sheet.setElevation(dp(12));
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(themed);
        title.setText(R.string.k2go_zim_manage_title);
        title.setTextColor(cOnSurface);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(16);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(themed);
        close.setText("✕");
        close.setTextColor(cOnSurfaceVariant);
        close.setTextSize(18);
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setContentDescription(activity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> hideSheet());
        header.addView(close);
        sheet.addView(header);

        searchField = new EditText(themed);
        searchField.setHint(R.string.k2go_zim_search_hint);
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

        int h = Math.round(activity.getResources().getDisplayMetrics().heightPixels * SHEET_FRACTION);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        params.gravity = Gravity.BOTTOM;
        deleteSheet = sheet;
        activity.addContentView(deleteSheet, params);
    }

    private void refresh() {
        KiwixClient.library(new KiwixClient.ArrayCb() {
            @Override public void onOk(JSONArray rows) {
                if (activity.isFinishing() || deleteSheet == null) return;
                items.clear();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r != null) items.add(r);
                }
                renderRows(searchField != null ? searchField.getText().toString() : "");
            }
            @Override public void onErr(String message) {
                if (activity.isFinishing() || listContainer == null) return;
                listContainer.removeAllViews();
                listContainer.addView(note(activity.getString(R.string.k2go_zim_load_failed)));
            }
        });
    }

    private void renderRows(String filter) {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        final String f = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (JSONObject it : items) {
            String name = it.optString("name", "");
            if (!f.isEmpty() && !name.toLowerCase(Locale.US).contains(f)) continue;
            listContainer.addView(row(it));
            shown++;
        }
        if (shown == 0) {
            listContainer.addView(note(activity.getString(
                    items.isEmpty() ? R.string.k2go_zim_empty : R.string.k2go_zim_no_matches)));
        }
    }

    private View row(JSONObject item) {
        final String name = item.optString("name", "");
        long bytes = item.optLong("bytes", 0);

        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(12), dp(6), dp(12));

        LinearLayout col = new LinearLayout(themed);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tt = new TextView(themed);
        tt.setText(prettyName(name));
        tt.setTextColor(cOnSurface);
        tt.setTextSize(14);
        col.addView(tt);
        TextView sz = new TextView(themed);
        sz.setText(ByteFormatter.toHuman(bytes));
        sz.setTextColor(cOnSurfaceVariant);
        sz.setTextSize(12);
        col.addView(sz);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView x = new TextView(themed);
        x.setText("✕");
        x.setTextColor(cError);
        x.setTextSize(16);
        x.setPadding(dp(16), dp(4), dp(8), dp(4));
        x.setContentDescription(activity.getString(R.string.k2go_zim_delete));
        x.setOnClickListener(v -> confirmDelete(name));
        row.addView(x);
        return row;
    }

    private void confirmDelete(String name) {
        new MaterialAlertDialogBuilder(themed)
                .setTitle(activity.getString(R.string.k2go_zim_delete_confirm_title, prettyName(name)))
                .setMessage(R.string.k2go_zim_delete_confirm_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.k2go_zim_delete, (d, w) -> {
                    Toast.makeText(activity, R.string.k2go_zim_deleting, Toast.LENGTH_SHORT).show();
                    KiwixClient.delete(name, new KiwixClient.OkCb() {
                        @Override public void onOk() {
                            if (activity.isFinishing()) return;
                            Toast.makeText(activity, activity.getString(R.string.k2go_zim_deleted, prettyName(name)), Toast.LENGTH_SHORT).show();
                            webView.reload();   // kiwix-serve redraws its library without the ZIM
                            refresh();
                        }
                        @Override public void onErr(String message) {
                            if (activity.isFinishing()) return;
                            new MaterialAlertDialogBuilder(themed)
                                    .setMessage(R.string.k2go_zim_delete_failed)
                                    .setPositiveButton(android.R.string.ok, null).show();
                        }
                    });
                })
                .show();
    }

    private void hideSheet() {
        if (deleteSheet != null) {
            ViewGroup parent = (ViewGroup) deleteSheet.getParent();
            if (parent != null) parent.removeView(deleteSheet);
        }
        deleteSheet = null; searchField = null; listContainer = null;
        if (active) showTrigger();   // bring the button back
    }

    // ---- helpers -----------------------------------------------------------------------------

    private TextView note(String text) {
        TextView t = new TextView(themed);
        t.setText(text);
        t.setTextColor(cOnSurfaceVariant);
        t.setPadding(dp(10), dp(14), dp(10), dp(14));
        return t;
    }

    /** "wikipedia_en_all_maxi_2024-01.zim" -> "wikipedia en all maxi 2024-01" (display only). */
    private static String prettyName(String zim) {
        if (zim == null) return "";
        String n = zim.endsWith(".zim") ? zim.substring(0, zim.length() - 4) : zim;
        return n.replace('_', ' ');
    }

    private GradientDrawable rounded(int color, float radiusDp, boolean topOnly) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(radiusDp);
        if (topOnly) g.setCornerRadii(new float[]{ r, r, r, r, 0, 0, 0, 0 });
        else g.setCornerRadius(r);
        return g;
    }

    private int dp(float v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
}
