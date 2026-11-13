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

import org.appdevforall.k2go.privacy.data.EnvironmentLockGate;
import org.appdevforall.k2go.privacy.data.PrivacyRepositoryImpl;
import org.appdevforall.k2go.privacy.domain.ApplyPendingRestartsUseCase;
import org.appdevforall.k2go.redesign.RestReadiness;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.List;

/** "Later" and the one-time off on update apply the next time the app opens. Waits for the box API,
 *  then asks the lifecycle owner to restart only the services the box reports as pending. */
public final class PendingRestartsOnOpen {

    private static final String TAG = "K2Go-Privacy";
    private static final long POLL_MS = 3000L;
    private static final long WAIT_MS = 180_000L;

    private PendingRestartsOnOpen() {}

    public static void run(Context context) {
        final Context app = context.getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            try {
                while (!RestReadiness.apiReady()) {
                    if (System.currentTimeMillis() > deadline) return;   // box not up: next open
                    Thread.sleep(POLL_MS);
                }
                List<String> restarted = new ApplyPendingRestartsUseCase(
                        new PrivacyRepositoryImpl(), new EnvironmentLockGate(app)).execute();
                if (!restarted.isEmpty()) Log.i(TAG, "pending privacy changes: restart " + restarted);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, "pending privacy restarts skipped: " + e.getMessage());   // older dash-node, etc.
            }
        });
    }
}
