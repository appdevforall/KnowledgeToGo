/*
 * ============================================================================
 * Name        : BooksDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850 / ADFA-4893 / K2GO-449. Foreground shell for the Books stream. The generic
 *               service behavior (onStartCommand dispatch, notification, channel, Host) now lives in
 *               ContentDownloadServiceBase; this subclass provides only the Books bits: the session
 *               singleton, the action strings, the notification title, the START item (id/title/url
 *               arrays, the {items:[{id,title,url}]} body, item-count percent), and the wishlist
 *               cleanup on cancel/done. The server downloads each EPUB from Gutenberg and uploads it
 *               into Calibre-Web; the device only POSTs + polls.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BooksDownloadService extends ContentDownloadServiceBase {

    private static final String CHANNEL_ID = "books_download_channel";
    private static final int NOTIFICATION_ID = org.appdevforall.k2go.notifications.NotificationIds.BOOKS_DOWNLOAD;

    public static final String ACTION_START = "org.iiab.controller.BOOKS_DOWNLOAD_START";
    public static final String ACTION_RETRY = "org.iiab.controller.BOOKS_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.BOOKS_DOWNLOAD_CANCEL";
    public static final String ACTION_PAUSE = "org.iiab.controller.BOOKS_DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "org.iiab.controller.BOOKS_DOWNLOAD_RESUME";
    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_URLS = "urls";

    // Per-item status — same values the UI/checklist already use, sourced from the shared session.
    public static final int PENDING = ContentDownloadSession.PENDING;
    public static final int ACTIVE = ContentDownloadSession.ACTIVE;
    public static final int ADDING = ContentDownloadSession.PROCESSING;
    public static final int DONE = ContentDownloadSession.DONE;
    public static final int FAILED = ContentDownloadSession.FAILED;

    /** Kept for the callers that pass {@code this::render}; adapts to the session listener. */
    public interface Listener { void onUpdate(); }

    private static final ContentDownloadSession SESSION = new ContentDownloadSession("books");
    // The whole per-item snapshot (id/title/url-as-bodies, titles-as-labels) lives in SESSION, so
    // status and the metadata the UI indexes off it stay length-consistent by construction (ADFA-4893).

    // ---- static API the UI observes (delegates to the shared session) -------------------------
    public static boolean isRunning() { return SESSION.isRunning(); }
    public static boolean isPaused() { return SESSION.isPaused(); }
    public static int reconnectAttempt() { return SESSION.reconnectAttempt(); }
    public static int reconnectTotal() { return SESSION.reconnectTotal(); }
    public static boolean hasSession() { return SESSION.hasSession(); }
    public static boolean isComplete() { return SESSION.isComplete(); }
    public static boolean isActiveNow() { return SESSION.isActiveNow(); }
    public static boolean hasFailed() { return SESSION.hasFailed(); }
    public static int[] status() { return SESSION.status(); }
    public static int index() { return SESSION.index(); }
    public static int overallPercent() { return SESSION.overallPercent(); }
    public static String[] titles() { return SESSION.labels(); }
    public static void setListener(Listener l) { SESSION.setListener(l == null ? null : l::onUpdate); }

    public static void start(Context ctx, String[] ids, String[] titles, String[] urls) {
        Intent i = new Intent(ctx, BooksDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_IDS, ids).putExtra(EXTRA_TITLES, titles).putExtra(EXTRA_URLS, urls);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void pause(Context ctx) {
        if (!SESSION.isRunning()) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RESUME));
    }

    /** ADFA-4893: re-queue ALL failed books and resume — the status-screen Retry (Pause morphs into it). */
    public static void retryFailed(Context ctx) {
        if (SESSION.requeueFailed() && !SESSION.isRunning()) {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RETRY));
        }
    }

    public static void finishSession() { SESSION.purge(); }

    // ---- per-module hooks for ContentDownloadServiceBase --------------------------------------
    @Override protected ContentDownloadSession session() { return SESSION; }
    @Override protected int notificationId() { return NOTIFICATION_ID; }
    @Override protected String channelId() { return CHANNEL_ID; }
    @Override protected String channelName() { return getString(R.string.k2go_books_dl_channel_name); }
    @Override protected String notifTitle() { return getString(R.string.k2go_books_dl_notif_title); }
    @Override protected String notifText() { return ""; }   // books threads the current book title via notify(label)
    @Override protected String actionPause() { return ACTION_PAUSE; }
    @Override protected String actionResume() { return ACTION_RESUME; }
    @Override protected String actionCancel() { return ACTION_CANCEL; }
    @Override protected String actionRetry() { return ACTION_RETRY; }

    @Override
    protected void beginFromIntent(Intent intent) {
        String[] ids = intent.getStringArrayExtra(EXTRA_IDS);
        if (ids == null || ids.length == 0) { stopSelf(); return; }
        String[] titles = intent.getStringArrayExtra(EXTRA_TITLES);
        String[] urls = intent.getStringArrayExtra(EXTRA_URLS);
        if (titles == null) titles = ids;
        if (urls == null) urls = new String[ids.length];
        JSONObject[] bodies = new JSONObject[ids.length];
        for (int i = 0; i < ids.length; i++) {
            try {
                JSONObject item = new JSONObject().put("id", ids[i]).put("title", titles[i]).put("url", urls[i]);
                bodies[i] = new JSONObject().put("items", new JSONArray().put(item));
            } catch (Exception e) { bodies[i] = new JSONObject(); }
        }
        // keys = ids: the book id is both the download id and the wishlist key (ADFA-4897).
        SESSION.begin(ids, titles, null, bodies);   // books: no per-item byte size -> count-based percent
    }

    /** ADFA-4897: Cancel abandons the order -> drop the remaining wishlist so a later drain does not re-add. */
    @Override protected void onCancel() {
        BooksWishlist.clear(getApplicationContext());
    }

    /** Tap the notification -> the shared content-download progress screen (K2GO-382). */
    @Override protected Intent returnIntent() { return OpReturnNavigator.contentDownload(this); }

    /** ADFA-4897: book confirmed added to the library -> drop it from the durable wishlist. */
    @Override public void onItemDone(String key) {
        if (key != null && !key.isEmpty()) BooksWishlist.remove(getApplicationContext(), key);
    }
}
