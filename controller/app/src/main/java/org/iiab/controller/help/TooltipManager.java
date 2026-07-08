/*
 * ============================================================================
 * Name        : TooltipManager.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Core of the K2Go three-tier help system. Reads tooltip content
 *               from a prebuilt read-only SQLite database shipped in assets,
 *               and shows a long-press popup with tier-1 (summary), tier-2
 *               (detail via "See more"), and tier-3 (links to full help pages).
 *               Tier-3 links degrade gracefully when the help server is absent.
 *               Mechanism mirrors Code On the Go's idetooltips module.
 * ============================================================================
 */
package org.iiab.controller.help;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.iiab.controller.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TooltipManager {

    private static final String TAG = "TooltipManager";
    private static final String ASSET_DB = "help.db";
    private static final String LOCAL_DB = "help.db";

    // TODO(ADFA-4594): the tier-3 help server host/port is defined by the tier-3
    // proposal. Until it exists, tier-3 links degrade gracefully if unreachable.
    private static final String TIER3_BASE_URL = "http://127.0.0.1:8114/help/";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PopupWindow activePopup;
    private static final int DB_ASSET_VERSION = 2;
    private static volatile boolean copiedThisProcess = false;

    private TooltipManager() {}

    /** Handles a tier-3 link tap. */
    public interface HelpLinkHandler {
        void open(Context context, String url, String label);
    }

    // ---- public entry points --------------------------------------------------

    public static void showTooltip(Context context, View anchor, String category, String tag) {
        showTooltip(context, anchor, category, tag, new HelpLinkHandler() {
            @Override
            public void open(Context c, String url, String label) {
                openHelpPage(c, url, label);
            }
        });
    }

    public static void showTooltip(final Context context, final View anchor,
                                   final String category, final String tag,
                                   final HelpLinkHandler linkHandler) {
        if (context == null || anchor == null) return;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final TooltipItem item = getTooltip(context, category, tag);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (item == null) {
                            Log.w(TAG, "No tooltip for category='" + category + "', tag='" + tag + "'");
                            if (isDebuggable(context)) {
                                // Debug-only: show a placeholder so the wiring is verifiable
                                // even before content exists. Release builds stay silent.
                                TooltipItem placeholder = new TooltipItem(category, tag,
                                        "<i>(no help yet \u2014 " + tag + ")</i>", "", new ArrayList<HelpLink>());
                                showPopup(context, anchor, 0, placeholder, linkHandler);
                            }
                            return;
                        }
                        showPopup(context, anchor, 0, item, linkHandler);
                    }
                });
            }
        });
    }

    // ---- data -----------------------------------------------------------------

    /** Blocking DB read; call off the main thread. */
    public static TooltipItem getTooltip(Context context, String category, String tag) {
        SQLiteDatabase db = null;
        try {
            db = openDatabase(context);
            if (db == null) return null;

            String lang = Locale.getDefault().getLanguage();
            TooltipItem item = queryTooltip(db, category, tag, lang);
            if (item == null && !"en".equals(lang)) {
                item = queryTooltip(db, category, tag, "en"); // fall back to the source language
            }
            return item;
        } catch (Exception e) {
            Log.e(TAG, "getTooltip failed: " + e.getMessage());
            return null;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static TooltipItem queryTooltip(SQLiteDatabase db, String category, String tag, String lang) {
        String summary = null;
        String detail = null;
        long tooltipId = -1;
        try (Cursor c = db.rawQuery(
                "SELECT T.id, T.summary, T.detail FROM Tooltips T, TooltipCategories TC "
                        + "WHERE T.categoryId = TC.id AND T.tag = ? COLLATE NOCASE "
                        + "AND TC.category = ? COLLATE NOCASE AND T.lang = ?",
                new String[]{tag, category, lang})) {
            if (!c.moveToFirst()) return null;
            tooltipId = c.getLong(0);
            summary = c.getString(1);
            detail = c.getString(2);
        }
        List<HelpLink> links = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT description, uri FROM TooltipButtons WHERE tooltipId = ? ORDER BY buttonNumberId",
                new String[]{String.valueOf(tooltipId)})) {
            while (c.moveToNext()) {
                links.add(new HelpLink(c.getString(0), c.getString(1)));
            }
        }
        return new TooltipItem(category, tag, summary, detail, links);
    }

    private static SQLiteDatabase openDatabase(Context context) {
        try {
            File dbFile = ensureDatabase(context);
            if (dbFile == null || !dbFile.exists()) return null;
            return SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            Log.e(TAG, "openDatabase failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Assets are compressed in the APK, so SQLite cannot open help.db directly.
     * Copy it to files dir once per app version.
     */
    private static synchronized File ensureDatabase(Context context) {
        File out = new File(context.getFilesDir(), LOCAL_DB);
        try {
            if (copiedThisProcess && out.exists()) return out;

            boolean debuggable = (context.getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            SharedPreferences sp = context.getSharedPreferences("help_db", Context.MODE_PRIVATE);
            int stored = sp.getInt("db_asset_version", -1);
            // Debug builds always refresh (content changes without a version bump);
            // release builds refresh only when the bundled DB version changes.
            if (out.exists() && !debuggable && stored == DB_ASSET_VERSION) {
                copiedThisProcess = true;
                return out;
            }

            try (InputStream in = context.getAssets().open(ASSET_DB);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            sp.edit().putInt("db_asset_version", DB_ASSET_VERSION).apply();
            copiedThisProcess = true;
            return out;
        } catch (Exception e) {
            Log.e(TAG, "ensureDatabase failed: " + e.getMessage());
            return out.exists() ? out : null;
        }
    }

    // ---- UI -------------------------------------------------------------------

    private static void showPopup(final Context context, final View anchor, final int level,
                                  final TooltipItem item, final HelpLinkHandler linkHandler) {
        try {
            dismissActive();

            View root = LayoutInflater.from(context).inflate(R.layout.tooltip_window, null);
            WebView web = root.findViewById(R.id.help_webview);
            TextView seeMore = root.findViewById(R.id.help_see_more);
            LinearLayout linksBox = root.findViewById(R.id.help_links);

            String content = (level == 0) ? item.summary : item.detail;
            web.loadDataWithBaseURL(null, wrapHtml(context, content), "text/html", "utf-8", null);

            final PopupWindow popup = new PopupWindow(root,
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            activePopup = popup;

            boolean showLinks;
            if (level == 0 && item.hasDetail()) {
                seeMore.setVisibility(View.VISIBLE);
                seeMore.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        showPopup(context, anchor, 1, item, linkHandler);
                    }
                });
                showLinks = false; // links appear at the detail level
            } else {
                seeMore.setVisibility(View.GONE);
                showLinks = true;
            }

            linksBox.removeAllViews();
            if (showLinks && item.hasLinks()) {
                for (final HelpLink link : item.links) {
                    Button b = new Button(context);
                    b.setText(link.label);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            popup.dismiss();
                            if (linkHandler != null) {
                                linkHandler.open(context, TIER3_BASE_URL + link.uri, link.label);
                            }
                        }
                    });
                    linksBox.addView(b);
                }
                linksBox.setVisibility(View.VISIBLE);
            } else {
                linksBox.setVisibility(View.GONE);
            }

            showPopupSmart(popup, anchor);
        } catch (Exception e) {
            Log.e(TAG, "showPopup failed: " + e.getMessage());
        }
    }

    private static boolean isDebuggable(Context context) {
        try {
            return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // Show the popup below the anchor, or above it when the anchor sits low on
    // screen (e.g. a bottom navigation/close button) so it isn't clipped off the
    // bottom. Anchor-position based, so it works even while the WebView content
    // is still laying out.
    private static void showPopupSmart(PopupWindow popup, View anchor) {
        try {
            int screenH = anchor.getResources().getDisplayMetrics().heightPixels;
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            int anchorTop = loc[1];
            if (anchorTop > screenH * 0.55f) {
                popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.START, loc[0], screenH - anchorTop);
            } else {
                popup.showAsDropDown(anchor);
            }
        } catch (Exception e) {
            try { popup.showAsDropDown(anchor); } catch (Exception ignored) {}
        }
    }

    public static void dismissActive() {
        try {
            if (activePopup != null) {
                activePopup.dismiss();
                activePopup = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Default tier-3 handler: load the local help page in a WebView dialog.
     * If the tier-3 server/content isn't present, degrade to a friendly message.
     */
    public static void openHelpPage(final Context context, String url, String label) {
        try {
            final WebView web = new WebView(context);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    view.loadData(
                            "<html><body style='font-family:sans-serif;padding:16px'>"
                                    + context.getString(R.string.tooltip_help_unavailable)
                                    + "</body></html>", "text/html", "utf-8");
                }
            });
            new AlertDialog.Builder(context)
                    .setTitle(label)
                    .setView(web)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .show();
            web.loadUrl(url);
        } catch (Exception e) {
            Log.e(TAG, "openHelpPage failed: " + e.getMessage());
        }
    }

    private static String wrapHtml(Context context, String body) {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String fg = dark ? "#ECECEC" : "#1A1A1A";
        String bg = dark ? "#2B2B2B" : "#FFFFFF";
        String safe = (body == null) ? "" : body;
        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>body{font-family:sans-serif;font-size:14px;margin:0;padding:12px;"
                + "color:" + fg + ";background:" + bg + ";max-width:280px}a{color:#1E88E5}</style>"
                + "</head><body>" + safe + "</body></html>";
    }
}
