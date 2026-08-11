/*
 * ============================================================================
 * Name        : BooksDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850. Foreground download manager for Books, following the ZIM pattern
 *               (CLAUDE.md): sequential, ONE AT A TIME (kind to Project Gutenberg), continuing
 *               past a failed item, per-item retry, "Finish" to clear the session. Each book is
 *               its own durable REST job: POST /api/books/download {items:[{id,title,url}]} then
 *               poll /api/books/jobs/:id (the server downloads the EPUB from Gutenberg and
 *               uploads it into Calibre-Web). The device only POSTs + polls.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class BooksDownloadService extends Service {

    private static final String CHANNEL_ID = "books_download_channel";
    private static final int NOTIFICATION_ID = 6;
    private static final String BASE = BoxEndpoints.API + "/books";
    private static final long POLL_MS = 1000L;

    public static final String ACTION_START = "org.iiab.controller.BOOKS_DOWNLOAD_START";
    public static final String ACTION_RETRY = "org.iiab.controller.BOOKS_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.BOOKS_DOWNLOAD_CANCEL";
    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_URLS = "urls";

    public static final int PENDING = 0, ACTIVE = 1, ADDING = 2, DONE = 3, FAILED = 4;

    public interface Listener { void onUpdate(); }

    // ---- shared session state (observed by the Downloads screen) ----
    private static volatile boolean sRunning = false;
    private static String[] sIds = new String[0];
    private static String[] sTitles = new String[0];
    private static String[] sUrls = new String[0];
    private static int[] sStatus = new int[0];
    private static int sIndex = 0;
    private static Listener sListener;

    public static boolean isRunning() { return sRunning; }
    public static boolean hasSession() { return sIds.length > 0; }
    public static boolean isComplete() {
        if (sIds.length == 0 || sRunning) return false;
        for (int st : sStatus) if (st == PENDING || st == ACTIVE || st == ADDING) return false;
        return true;
    }
    public static String[] titles() { return sTitles; }
    public static int[] status() { return sStatus; }
    public static int index() { return sIndex; }
    public static void setListener(Listener l) { sListener = l; }

    public static void start(Context ctx, String[] ids, String[] titles, String[] urls) {
        Intent i = new Intent(ctx, BooksDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_IDS, ids).putExtra(EXTRA_TITLES, titles).putExtra(EXTRA_URLS, urls);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void retry(Context ctx, int i) {
        if (i < 0 || i >= sStatus.length) return;
        sStatus[i] = PENDING;
        if (!sRunning) ContextCompat.startForegroundService(ctx,
                new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RETRY));
    }

    public static void finishSession() {
        sIds = new String[0]; sTitles = new String[0]; sUrls = new String[0]; sStatus = new int[0];
        sIndex = 0; sRunning = false;
    }

    private static final int MAX_ATTEMPTS = 3;      // total tries per book before marking it failed
    private static final long RETRY_DELAY_MS = 4000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String currentJobId;
    private volatile boolean canceled = false;
    private int attempts = 0;                        // attempts for the item currently in flight

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            canceled = true; sRunning = false; publish();
            main.post(() -> { stopForeground(true); stopSelf(); });
            return START_NOT_STICKY;
        }
        if (sRunning) return START_NOT_STICKY;

        if (ACTION_RETRY.equals(action)) {
            if (!hasSession()) { stopSelf(); return START_NOT_STICKY; }
        } else {
            String[] ids = intent.getStringArrayExtra(EXTRA_IDS);
            if (ids == null || ids.length == 0) { stopSelf(); return START_NOT_STICKY; }
            sIds = ids;
            sTitles = intent.getStringArrayExtra(EXTRA_TITLES);
            sUrls = intent.getStringArrayExtra(EXTRA_URLS);
            if (sTitles == null) sTitles = ids;
            if (sUrls == null) sUrls = new String[ids.length];
            sStatus = new int[ids.length];
            sIndex = 0;
        }
        canceled = false;
        sRunning = true;
        startForeground(NOTIFICATION_ID, buildNotification(currentTitle()));
        processNext();
        return START_NOT_STICKY;
    }

    private static int firstPending() {
        for (int i = 0; i < sStatus.length; i++) if (sStatus[i] == PENDING) return i;
        return -1;
    }

    private String currentTitle() { return sIndex >= 0 && sIndex < sTitles.length ? sTitles[sIndex] : ""; }

    private void processNext() {
        int i = firstPending();
        if (i < 0) { sessionComplete(); return; }
        sIndex = i; sStatus[i] = ACTIVE;
        attempts = 0;
        publish();
        updateNotification(sTitles[i]);
        AppExecutors.get().io().execute(() -> startJob(i));
    }

    /** Transient failure (nginx 502 while the engine warms up, a flaky Gutenberg fetch, etc.):
     *  retry the same book a couple times before giving up, so the wizard doesn't stall on a blip. */
    private void retryOrFail(int i) {
        attempts++;
        if (attempts < MAX_ATTEMPTS) {
            android.util.Log.w("K2Go-Provision", "books job [" + i + "] transient failure, retry " + attempts + "/" + (MAX_ATTEMPTS - 1));
            main.postDelayed(() -> AppExecutors.get().io().execute(() -> startJob(i)), RETRY_DELAY_MS);
        } else {
            android.util.Log.w("K2Go-Provision", "books job [" + i + "] failed after " + attempts + " attempts");
            fail(i);
        }
    }

    private void startJob(int i) {
        android.util.Log.i("K2Go-Provision", "books job start [" + i + "] id=" + sIds[i] + " title='" + sTitles[i] + "' url=" + sUrls[i]);
        try {
            JSONObject item = new JSONObject().put("id", sIds[i]).put("title", sTitles[i]).put("url", sUrls[i]);
            JSONObject body = new JSONObject().put("items", new JSONArray().put(item));
            JSONObject resp = httpJson("POST", BASE + "/download", body);
            String id = resp.optString("id", "");
            if (id.isEmpty()) { android.util.Log.w("K2Go-Provision", "books job [" + i + "] no job id in response: " + resp); retryOrFail(i); return; }
            currentJobId = id;
            main.postDelayed(() -> poll(i), POLL_MS);
        } catch (Exception e) {
            android.util.Log.w("K2Go-Provision", "books job [" + i + "] POST failed: " + e.getMessage());
            retryOrFail(i);
        }
    }

    private void poll(int i) {
        if (canceled) return;
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject j = httpJson("GET", BASE + "/jobs/" + currentJobId, null);
                String phase = j.optString("phase", "");
                switch (phase) {
                    case "done":
                        android.util.Log.i("K2Go-Provision", "books job done [" + i + "]");
                        sStatus[i] = DONE; publish(); main.post(BooksDownloadService.this::processNext); return;
                    case "error":
                    case "canceled":
                        android.util.Log.w("K2Go-Provision", "books job [" + i + "] phase=" + phase + " err=" + j.optString("error"));
                        retryOrFail(i); return;
                    case "processing":
                        if (sStatus[i] != ADDING) { sStatus[i] = ADDING; publish(); }
                        break;
                    default: break; // queued
                }
                main.postDelayed(() -> poll(i), POLL_MS);
            } catch (Exception e) {
                main.postDelayed(() -> poll(i), POLL_MS); // tolerate transient blips
            }
        });
    }

    private void fail(int i) {
        sStatus[i] = FAILED; publish();
        main.post(this::processNext);
    }

    private void sessionComplete() {
        sRunning = false; publish();
        main.post(() -> { stopForeground(true); stopSelf(); });
    }

    private void publish() { main.post(() -> { if (sListener != null) sListener.onUpdate(); }); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_books_dl_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title) {
        // ADFA-4987: the redesign progress screen, not the legacy UI. ADFA-5074: the index, not
        // this stream's detail — it is the only surface that can end the run.
        Intent openI = new Intent(this, SetupProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openI,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, BooksDownloadService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_books_dl_notif_title))
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_zim_notif_cancel), cancel)
                .build();
    }

    private void updateNotification(String title) {
        if (!sRunning) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildNotification(title));
    }

    private static JSONObject httpJson(String method, String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) throw new Exception("HTTP " + code + ": " + text);
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
