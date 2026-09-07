package org.appdevforall.k2go.networkpolicy.presentation;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier;
import org.appdevforall.k2go.networkpolicy.data.SessionMeteredConsentStore;
import org.appdevforall.k2go.networkpolicy.domain.NetworkClass;
import org.appdevforall.k2go.networkpolicy.domain.NetworkTransition;
import org.appdevforall.k2go.sync.transport.NetworkStateLiveData;

/**
 * Process-wide watcher of the default-network cost class. Started once from
 * IIABApplication, mirroring {@code ServerLifecycleReconciler} (one process-scoped
 * owner). It REUSES the single existing default-network callback
 * ({@link NetworkStateLiveData}) instead of registering a second one -- one
 * source for the "network changed" fact (ADR-395).
 *
 * <p>Two jobs:
 * <ul>
 *   <li>Proactive alert: when the network crosses into metered, post a
 *       notification so the user knows further activity spends data -- even with
 *       no download pending.</li>
 *   <li>Consent lifecycle: clear the session metered-consent the moment the
 *       network leaves metered, so the next metered episode asks again (no stuck
 *       grant -- ADR-395).</li>
 * </ul>
 */
public final class MeteredNetworkObserver {

    private static final String CHANNEL_ID = "network_cost";
    private static final int NOTIF_ID = 0x4E50; // stable id: re-alert replaces, never stacks

    private MeteredNetworkObserver() {}

    private static NetworkClass last = null;

    /** Idempotent; call once from Application.onCreate on the main thread. */
    public static void start(@NonNull Application app) {
        ensureChannel(app);
        last = AndroidNetworkClassifier.classify(app);
        // observeForever keeps NetworkStateLiveData active for the process lifetime,
        // which is exactly the scope we want; no separate registration.
        NetworkStateLiveData.get(app).observeForever(token -> onNetworkChanged(app));
    }

    private static void onNetworkChanged(@NonNull Application app) {
        NetworkClass previous = last;
        NetworkClass next = AndroidNetworkClassifier.classify(app);
        if (next == previous) return;
        last = next;
        if (next != NetworkClass.METERED) {
            SessionMeteredConsentStore.get().clear();
        }
        if (NetworkTransition.shouldWarn(previous, next)) {
            notifyMetered(app);
        }
    }

    private static void notifyMetered(@NonNull Context ctx) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(ctx.getString(R.string.k2go_netpolicy_switched_title))
                .setContentText(ctx.getString(R.string.k2go_netpolicy_switched_msg))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(ctx.getString(R.string.k2go_netpolicy_switched_msg)))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted (Android 13+): the start-gate still protects cost.
        }
    }

    private static void ensureChannel(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.k2go_netpolicy_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager m = ctx.getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }
}
