/*
 * ============================================================================
 * Name        : KolibriSeedService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. Foreground service that seeds the chosen Kolibri
 *               channels through the in-server REST job engine: one job per
 *               channel, SEQUENTIALLY, CONTINUING past a failed one. The heavy
 *               work runs on the live Debian/proot server, so the device only
 *               POSTs and polls. Session state lives in KolibriSeedRepository.
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.iiab.controller.kolibri.data.KolibriRestClient;
import org.iiab.controller.kolibri.domain.ChannelSelection;
import org.iiab.controller.kolibri.domain.SeedPlan;
import org.iiab.controller.redesign.SetupProgressActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the seeding session.
 *
 * <p><b>One job per channel, not one job for the batch.</b> The REST client can
 * post the whole {@link SeedPlan} at once and the server would walk it, but then
 * a single failing channel ends the run and there is nothing to retry but the
 * lot. Per-channel jobs give the same per-item status, per-item retry and
 * continue-past-failure the ZIM and Books services provide, which is what
 * {@code ProvisioningChecklist} and the progress index already expect.
 *
 * <p>Unlike its three siblings the session is not in {@code static} fields; it
 * lives in {@link KolibriSeedRepository}. See ADR-4954 D7.
 */
public final class KolibriSeedService extends Service {

    private static final String TAG = "K2Go-Provision";
    private static final String CHANNEL_ID = "kolibri_seed_channel";
    private static final int NOTIFICATION_ID = 7;

    public static final String ACTION_START = "org.iiab.controller.KOLIBRI_SEED_START";
    public static final String ACTION_RETRY = "org.iiab.controller.KOLIBRI_SEED_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.KOLIBRI_SEED_CANCEL";

    public static final String EXTRA_CHANNEL_IDS = "channelIds";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_BYTES = "bytes";
    /** Node ids per channel, joined with commas; empty means the whole channel. */
    public static final String EXTRA_NODE_IDS = "nodeIds";

    /** Total tries per channel before it is marked failed and the batch moves on. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 4000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private KolibriRestClient client;
    private int attempts;

    // ---- entry points ------------------------------------------------------

    /** Starts a fresh session. Arrays must be the same length. */
    public static void start(Context ctx, String[] channelIds, String[] labels,
                             long[] bytes, String[] nodeIdsCsv) {
        Intent i = new Intent(ctx, KolibriSeedService.class).setAction(ACTION_START)
                .putExtra(EXTRA_CHANNEL_IDS, channelIds)
                .putExtra(EXTRA_LABELS, labels)
                .putExtra(EXTRA_BYTES, bytes)
                .putExtra(EXTRA_NODE_IDS, nodeIdsCsv);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Re-queues a failed channel; resumes the loop if it had already stopped. */
    public static void retry(Context ctx, int index) {
        KolibriSeedRepository repo = KolibriSeedRepository.get();
        repo.retryItem(index);
        if (!repo.isRunning()) {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, KolibriSeedService.class).setAction(ACTION_RETRY));
        }
    }

    /** Clears the session so a new selection can start clean. */
    public static void finishSession() {
        KolibriSeedRepository.get().clearSession();
    }

    // ---- lifecycle ---------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        KolibriSeedRepository repo = KolibriSeedRepository.get();

        if (ACTION_CANCEL.equals(action)) {
            if (client != null) {
                client.cancel();
            }
            repo.sessionStopped();
            main.post(() -> {
                stopForeground(true);
                stopSelf();
            });
            return START_NOT_STICKY;
        }

        if (repo.isRunning()) {
            return START_NOT_STICKY;
        }

        if (ACTION_RETRY.equals(action)) {
            if (!repo.hasSession()) {
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            if (!beginSession(intent, repo)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(currentLabel()));
        processNext();
        return START_NOT_STICKY;
    }

    /** Reads the extras into a new session. False when there is nothing to do. */
    private boolean beginSession(Intent intent, KolibriSeedRepository repo) {
        if (intent == null) {
            return false;
        }
        String[] ids = intent.getStringArrayExtra(EXTRA_CHANNEL_IDS);
        if (ids == null || ids.length == 0) {
            return false;
        }
        String[] labels = intent.getStringArrayExtra(EXTRA_LABELS);
        long[] bytes = intent.getLongArrayExtra(EXTRA_BYTES);
        String[] nodes = intent.getStringArrayExtra(EXTRA_NODE_IDS);

        List<KolibriSeedState.Item> queued = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String label = labels != null && i < labels.length ? labels[i] : ids[i];
            long size = bytes != null && i < bytes.length ? bytes[i] : 0L;
            // The selection travels on the item, not in a field of this service:
            // the service stops itself when the queue drains, so a Retry runs in a
            // fresh instance and anything held here would be gone.
            List<String> selected = splitCsv(nodes != null && i < nodes.length ? nodes[i] : null);
            queued.add(KolibriSeedState.Item.pending(ids[i], label, size, selected));
        }
        repo.startSession(queued);
        return true;
    }

    // ---- the loop ----------------------------------------------------------

    private void processNext() {
        KolibriSeedRepository repo = KolibriSeedRepository.get();
        int i = repo.current().firstPending();
        if (i < 0) {
            sessionComplete();
            return;
        }
        attempts = 0;
        repo.itemStarted(i);
        updateNotification(labelAt(i));
        startItem(i);
    }

    private void startItem(final int index) {
        final KolibriSeedRepository repo = KolibriSeedRepository.get();
        final KolibriSeedState.Item item = itemAt(index);
        if (item == null) {
            sessionComplete();
            return;
        }

        SeedPlan plan = planFor(item);
        if (plan.isEmpty()) {
            // A selection that produced nothing would have Kolibri report success
            // with zero transferred. Fail it here instead of shipping the no-op.
            Log.w(TAG, "kolibri seed [" + index + "] unusable selection; skipping");
            repo.itemFinished(index, false);
            processNext();
            return;
        }

        Log.i(TAG, "kolibri seed start [" + index + "] " + item.channelId());
        client = new KolibriRestClient();
        client.seed(plan, new KolibriRestClient.Listener() {
            @Override
            public void onProgress(int percent, String speed) {
                repo.itemProgress(index, percent, parseRate(speed));
                updateNotification(labelAt(index));
            }

            @Override
            public void onIndexing() {
                // Kolibri reports no percentage while it writes the content DB;
                // -1 is the indeterminate convention the checklist understands.
                repo.itemProgress(index, -1, 0L);
            }

            @Override
            public void onLog(String line) {
                Log.d(TAG, "kolibri seed [" + index + "] " + line);
            }

            @Override
            public void onDone() {
                Log.i(TAG, "kolibri seed done [" + index + "]");
                repo.itemFinished(index, true);
                processNext();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "kolibri seed [" + index + "] error: " + message);
                retryOrFail(index);
            }
        });
    }

    /**
     * A transient failure — the server still warming up, a flaky mirror — should
     * not cost the channel. Retry a couple of times, then mark it failed and
     * carry on with the rest: a partly seeded device is better than none.
     */
    private void retryOrFail(int index) {
        attempts++;
        if (attempts < MAX_ATTEMPTS) {
            Log.w(TAG, "kolibri seed [" + index + "] transient failure, retry "
                    + attempts + "/" + (MAX_ATTEMPTS - 1));
            main.postDelayed(() -> startItem(index), RETRY_DELAY_MS);
            return;
        }
        Log.w(TAG, "kolibri seed [" + index + "] failed after " + attempts + " attempts");
        KolibriSeedRepository.get().itemFinished(index, false);
        processNext();
    }

    private void sessionComplete() {
        KolibriSeedRepository.get().sessionStopped();
        main.post(() -> {
            stopForeground(true);
            stopSelf();
        });
    }

    // ---- helpers -----------------------------------------------------------

    private SeedPlan planFor(KolibriSeedState.Item item) {
        try {
            ChannelSelection sel = item.isWholeChannel()
                    ? ChannelSelection.wholeChannel(item.channelId())
                    : ChannelSelection.ofSubtrees(item.channelId(), item.nodeIds());
            Map<String, Long> sizes = new HashMap<>();
            sizes.put(sel.channelId(), item.bytes());
            return SeedPlan.of(Collections.singletonList(sel), sizes);
        } catch (IllegalArgumentException bad) {
            Log.w(TAG, "kolibri seed: " + bad.getMessage());
            return SeedPlan.empty();
        }
    }

    private static KolibriSeedState.Item itemAt(int index) {
        List<KolibriSeedState.Item> items = KolibriSeedRepository.get().current().items();
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private static String labelAt(int index) {
        KolibriSeedState.Item i = itemAt(index);
        return i == null ? "" : i.label();
    }

    private static String currentLabel() {
        KolibriSeedState s = KolibriSeedRepository.get().current();
        int i = s.firstPending();
        return i < 0 ? "" : labelAt(i);
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** "3.4 MB" -> bytes/sec. Returns 0 for anything unparseable. */
    static long parseRate(String s) {
        if (s == null) {
            return 0L;
        }
        try {
            String t = s.trim();
            int sp = t.indexOf(' ');
            if (sp < 0) {
                return 0L;
            }
            double v = Double.parseDouble(t.substring(0, sp));
            String u = t.substring(sp + 1);
            double m = "GB".equals(u) ? 1024d * 1024 * 1024
                    : "MB".equals(u) ? 1024d * 1024
                    : "KB".equals(u) ? 1024d : 1d;
            return Math.round(v * m);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---- notification ------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.k2go_kolibri_dl_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String label) {
        // ADFA-5074: the index, not this stream's detail — it is the only surface that can end
        // the run, and a notification is how someone comes back to ask whether it is going well.
        Intent open = new Intent(this, SetupProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent cancel = new Intent(this, KolibriSeedService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_kolibri_dl_notif_title))
                .setContentText(label)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_kolibri_notif_cancel), cancelIntent)
                .build();
    }

    private void updateNotification(String label) {
        if (!KolibriSeedRepository.get().isRunning()) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(label));
        }
    }
}
