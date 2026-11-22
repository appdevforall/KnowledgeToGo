/*
 * ============================================================================
 * Name        : PendingRestartsOnOpen.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. At app open, apply privacy changes that wait for a service restart.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.presentation;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.delivery.data.OnlineConsent;
import org.appdevforall.k2go.privacy.data.EnvironmentLockGate;
import org.appdevforall.k2go.privacy.data.PrivacyRepositoryImpl;
import org.appdevforall.k2go.privacy.domain.ApplyPendingRestartsUseCase;
import org.appdevforall.k2go.privacy.domain.KeepBoxOfflineUseCase;
import org.appdevforall.k2go.redesign.RestReadiness;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.List;

/** "Later" and the one-time off on update apply the next time the app opens. Waits for the box API,
 *  then asks the lifecycle owner to restart only the services the box reports as pending.
 *  K2GO-588: with Keep offline, every outbound switch is first set off, so the restart applies it. */
public final class PendingRestartsOnOpen {

    private static final String TAG = "K2Go-Privacy";
    private static final long POLL_MS = 3000L;
    private static final long WAIT_MS = 180_000L;

    private PendingRestartsOnOpen() {}

    public static void run(Context context) {
        final Context app = context.getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            try {
                if (!waitForBox()) return;   // box not up: next open
                PrivacyRepositoryImpl repository = new PrivacyRepositoryImpl();
                keepOfflineIfChosen(app, repository);
                List<String> restarted = new ApplyPendingRestartsUseCase(
                        repository, new EnvironmentLockGate(app)).execute();
                if (!restarted.isEmpty()) Log.i(TAG, "pending privacy changes: restart " + restarted);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, "pending privacy restarts skipped: " + e.getMessage());   // older dash-node, etc.
            }
        });
    }

    /** K2GO-588: the user chose Keep offline now. Switch every outbound service off and restart only
     *  those, so the change applies in this session (a "Later" restart of another switch stays for
     *  the next open). */
    public static void keepOfflineNow(Context context) {
        final Context app = context.getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            try {
                if (!waitForBox()) return;   // box not up: the next open applies it
                PrivacyRepositoryImpl repository = new PrivacyRepositoryImpl();
                List<String> off = keepOfflineIfChosen(app, repository);
                if (new EnvironmentLockGate(app).isBusy()) return;   // the next open restarts them
                for (String service : off) repository.requestRestart(service);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, "keep offline now skipped: " + e.getMessage());
            }
        });
    }

    private static boolean waitForBox() throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!RestReadiness.apiReady()) {
            if (System.currentTimeMillis() > deadline) return false;
            Thread.sleep(POLL_MS);
        }
        return true;
    }

    /** With Keep offline, sets every outbound switch off. A failed service is logged and tried
     *  again at the next open; the others still go off and restart. */
    private static List<String> keepOfflineIfChosen(Context app, PrivacyRepositoryImpl repository) {
        if (OnlineConsent.isEnabled(app)) return java.util.Collections.emptyList();
        try {
            List<String> off = new KeepBoxOfflineUseCase(repository).execute();
            if (!off.isEmpty()) Log.i(TAG, "keep offline: outbound off for " + off);
            return off;
        } catch (Exception e) {
            Log.w(TAG, "keep offline: a service did not switch off (" + e.getMessage() + ")");
            return java.util.Collections.emptyList();
        }
    }
}
