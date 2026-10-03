/*
 * ============================================================================
 * Name        : AddonsDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-443 / K2GO-449. Foreground shell for the Code on the Go add-ons gallery download on
 *               the durable job engine (a single-item session, type "code-addons"). The generic service
 *               behavior (onStartCommand dispatch, notification, Host) lives in ContentDownloadServiceBase;
 *               this subclass only provides the module bits: the session singleton, the action strings,
 *               the notification text, and the START item (a sentinel, since the box runner reads the
 *               catalog itself). The heavy work (aria2, resume, verify, swap) runs on the box.
 * ============================================================================
 */
package org.appdevforall.k2go.addons.presentation;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.redesign.ContentDownloadServiceBase;
import org.appdevforall.k2go.redesign.ContentDownloadSession;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AddonsDownloadService extends ContentDownloadServiceBase {

    private static final String CHANNEL_ID = "code_addons_download_channel";
    private static final int NOTIFICATION_ID = 11;   // distinct: 8 is ForgejoSeedService / CloneShareService (may run concurrently)

    public static final String ACTION_START = "org.appdevforall.k2go.CODE_ADDONS_DOWNLOAD_START";
    public static final String ACTION_PAUSE = "org.appdevforall.k2go.CODE_ADDONS_DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "org.appdevforall.k2go.CODE_ADDONS_DOWNLOAD_RESUME";
    public static final String ACTION_CANCEL = "org.appdevforall.k2go.CODE_ADDONS_DOWNLOAD_CANCEL";
    public static final String ACTION_RETRY = "org.appdevforall.k2go.CODE_ADDONS_DOWNLOAD_RETRY";

    // The box runner reads the catalog itself and ignores the job items, so a single sentinel
    // satisfies POST /code-addons/download (which requires a non-empty items/ids).
    private static final String SENTINEL = "add-ons";

    private static final ContentDownloadSession SESSION = new ContentDownloadSession("code-addons");

    /** Adapts to the session listener; the UI passes {@code this::render}. */
    public interface Listener { void onUpdate(); }

    // ---- static API the UI observes (delegates to the shared session) --------------------------
    public static boolean isRunning() { return SESSION.isRunning(); }
    public static boolean isPaused() { return SESSION.isPaused(); }
    public static boolean hasSession() { return SESSION.hasSession(); }
    public static boolean isComplete() { return SESSION.isComplete(); }
    public static boolean hasFailed() { return SESSION.hasFailed(); }
    public static int percent() { return SESSION.percent(); }
    public static long speed() { return SESSION.speed(); }
    public static int reconnectAttempt() { return SESSION.reconnectAttempt(); }
    public static int reconnectTotal() { return SESSION.reconnectTotal(); }
    public static void setListener(Listener l) { SESSION.setListener(l == null ? null : l::onUpdate); }

    public static void start(Context ctx) { send(ctx, ACTION_START); }
    public static void pause(Context ctx) { if (SESSION.isRunning()) send(ctx, ACTION_PAUSE); }
    public static void resume(Context ctx) { send(ctx, ACTION_RESUME); }
    public static void cancel(Context ctx) { send(ctx, ACTION_CANCEL); }

    /** Re-queue the failed item and resume (the box resumes the partial via aria2 --continue). */
    public static void retry(Context ctx) {
        if (SESSION.requeueFailed() && !SESSION.isRunning()) send(ctx, ACTION_RETRY);
    }

    public static void finishSession() { SESSION.purge(); }

    private static void send(Context ctx, String action) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, AddonsDownloadService.class).setAction(action));
    }

    // ---- per-module hooks for ContentDownloadServiceBase ---------------------------------------
    @Override protected ContentDownloadSession session() { return SESSION; }
    @Override protected int notificationId() { return NOTIFICATION_ID; }
    @Override protected String channelId() { return CHANNEL_ID; }
    @Override protected String channelName() { return getString(R.string.k2go_card_code_addons); }
    @Override protected String notifTitle() { return getString(R.string.k2go_code_addons_updating); }
    @Override protected String notifText() { return getString(R.string.k2go_card_code_addons); }
    @Override protected String actionPause() { return ACTION_PAUSE; }
    @Override protected String actionResume() { return ACTION_RESUME; }
    @Override protected String actionCancel() { return ACTION_CANCEL; }
    @Override protected String actionRetry() { return ACTION_RETRY; }

    @Override
    protected void beginFromIntent(Intent intent) {
        String[] keys = { "code-addons" };
        String[] labels = { getString(R.string.k2go_card_code_addons) };
        long[] sizes = { 0L };   // count-based: the UI shows the item percent directly
        JSONObject[] bodies = new JSONObject[1];
        try { bodies[0] = new JSONObject().put("ids", new JSONArray().put(SENTINEL)); }
        catch (Exception e) { bodies[0] = new JSONObject(); }
        SESSION.begin(keys, labels, sizes, bodies);
    }
}
