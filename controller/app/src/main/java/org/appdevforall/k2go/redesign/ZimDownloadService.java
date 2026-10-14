/*
 * ============================================================================
 * Name        : ZimDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849 / ADFA-4893 / K2GO-449. Foreground shell for the ZIM stream. The generic
 *               service behavior (onStartCommand dispatch, notification, channel, Host) now lives in
 *               ContentDownloadServiceBase; this subclass provides only the ZIM bits: the session
 *               singleton, the action strings, the notification title, the START item (file/label/byte
 *               arrays, the {ids:[..]} body, byte-weighted percent), the wishlist cleanup on cancel/done,
 *               and the catalog self-heal on item error. The heavy work runs on the server; the device
 *               only POSTs + polls.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ZimDownloadService extends ContentDownloadServiceBase {

    private static final String CHANNEL_ID = "zim_download_channel";
    private static final int NOTIFICATION_ID = org.appdevforall.k2go.notifications.NotificationIds.ZIM_DOWNLOAD;

    public static final String ACTION_START = "org.iiab.controller.ZIM_DOWNLOAD_START";
    public static final String ACTION_RETRY = "org.iiab.controller.ZIM_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.ZIM_DOWNLOAD_CANCEL";
    public static final String ACTION_PAUSE = "org.iiab.controller.ZIM_DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "org.iiab.controller.ZIM_DOWNLOAD_RESUME";
    public static final String EXTRA_FILES = "files";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_BYTES = "bytes";
    public static final String EXTRA_KEYS = "keys";   // ADFA-4897: wishlist keys, one per file

    // Per-item status — same values the UI/checklist already use, sourced from the shared session.
    public static final int PENDING = ContentDownloadSession.PENDING;
    public static final int ACTIVE = ContentDownloadSession.ACTIVE;
    public static final int INDEXING = ContentDownloadSession.PROCESSING;
    public static final int DONE = ContentDownloadSession.DONE;
    public static final int FAILED = ContentDownloadSession.FAILED;

    /** Kept for the callers that pass {@code this::render}; adapts to the session listener. */
    public interface Listener { void onUpdate(); }

    private static final ContentDownloadSession SESSION = new ContentDownloadSession("kiwix");
    // The whole per-item snapshot (files-as-bodies/labels/sizes) lives in SESSION, so status and the
    // metadata the UI indexes off it stay length-consistent by construction (ADFA-4893).

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
    public static int percent() { return SESSION.percent(); }
    public static long speed() { return SESSION.speed(); }
    public static int overallPercent() { return SESSION.overallPercent(); }
    public static String[] labels() { return SESSION.labels(); }
    public static long[] bytes() { return SESSION.sizes(); }
    public static void setListener(Listener l) { SESSION.setListener(l == null ? null : l::onUpdate); }

    public static void start(Context ctx, String[] keys, String[] files, String[] labels, long[] bytes) {
        Intent i = new Intent(ctx, ZimDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_KEYS, keys).putExtra(EXTRA_FILES, files)
                .putExtra(EXTRA_LABELS, labels).putExtra(EXTRA_BYTES, bytes);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void pause(Context ctx) {
        if (!SESSION.isRunning()) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_RESUME));
    }

    /** ADFA-4893: re-queue ALL failed items and resume : the status-screen Retry (Pause morphs into it). */
    public static void retryFailed(Context ctx) {
        if (SESSION.requeueFailed() && !SESSION.isRunning()) {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_RETRY));
        }
    }

    public static void finishSession() { SESSION.purge(); }

    // ---- per-module hooks for ContentDownloadServiceBase --------------------------------------
    @Override protected ContentDownloadSession session() { return SESSION; }
    @Override protected int notificationId() { return NOTIFICATION_ID; }
    @Override protected String channelId() { return CHANNEL_ID; }
    @Override protected String channelName() { return getString(R.string.k2go_zim_dl_channel_name); }
    @Override protected String notifTitle() { return getString(R.string.k2go_zim_dl_notif_title); }
    @Override protected String notifText() { return ""; }   // zim threads the current item label via notify(label)
    @Override protected String actionPause() { return ACTION_PAUSE; }
    @Override protected String actionResume() { return ACTION_RESUME; }
    @Override protected String actionCancel() { return ACTION_CANCEL; }
    @Override protected String actionRetry() { return ACTION_RETRY; }

    @Override
    protected void beginFromIntent(Intent intent) {
        String[] files = intent.getStringArrayExtra(EXTRA_FILES);
        if (files == null || files.length == 0) { stopSelf(); return; }
        String[] labels = intent.getStringArrayExtra(EXTRA_LABELS);
        long[] bytes = intent.getLongArrayExtra(EXTRA_BYTES);
        String[] keys = intent.getStringArrayExtra(EXTRA_KEYS);
        if (labels == null) labels = files;
        JSONObject[] bodies = new JSONObject[files.length];
        for (int i = 0; i < files.length; i++) {
            try { bodies[i] = new JSONObject().put("ids", new JSONArray().put(files[i])); }
            catch (Exception e) { bodies[i] = new JSONObject(); }
        }
        SESSION.begin(keys, labels, bytes, bodies);   // session owns the snapshot from here on
    }

    /** ADFA-4897: Cancel abandons the order -> drop the remaining wishlist so a later drain does not re-download. */
    @Override protected void onCancel() {
        ZimWishlist.clear(getApplicationContext());
    }

    /** Tap the notification -> the shared content-download progress screen (K2GO-382). */
    @Override protected Intent returnIntent() { return OpReturnNavigator.contentDownload(this); }

    /** ADFA-4897: item confirmed downloaded -> drop it from the durable wishlist, so a process death
     *  after this point never re-downloads it. */
    @Override public void onItemDone(String key) {
        if (key != null && !key.isEmpty()) ZimWishlist.remove(getApplicationContext(), key);
    }

    // K2GO-390: bound the self-heal. On a failure we force a catalog refresh; the drain then re-resolves
    // the date-free key ("project|lang|flavour") to the current dated file and retries. After this many
    // failed drains for one key, it is genuinely gone (or there is no fresh source): drop it so it stops
    // re-draining. ~5 * the ~2s drain cadence gives the refresh time to land. See ADR-390.
    private static final int MAX_HEAL_ATTEMPTS = 5;

    /** ADR-390: item gave up (likely a stale catalog -> 404). Force a freshness check and bound retries,
     *  so a rolled-over ZIM heals to its current file and a genuinely-gone one stops looping. */
    @Override public void onItemError(String key) {
        if (key == null || key.isEmpty()) return;
        Context app = getApplicationContext();
        KiwixCatalog.forceRefresh(app);   // network-constrained; offline is a silent no-op
        // Count failures against the current catalog version (KiwixCatalog owns what that is). A refresh
        // that changes the catalog resets the budget (see ZimWishlist.bumpAttempts); only an unchanging
        // catalog climbs to the cap = genuinely gone / no fresh source.
        int attempts = ZimWishlist.bumpAttempts(app, key, KiwixCatalog.catalogVersionTag(app));
        if (attempts >= MAX_HEAL_ATTEMPTS) {
            android.util.Log.w("K2Go-Provision",
                    "kiwix item still failing after " + attempts + " attempts; dropping " + key);
            ZimWishlist.remove(app, key);
        }
    }
}
