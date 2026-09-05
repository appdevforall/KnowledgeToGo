package org.appdevforall.k2go.diskguard.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.appdevforall.k2go.diskguard.DiskGuard;

/**
 * DEBUG-ONLY. K2GO-386 device-verify hook. Forces one disk-guard check with an injected floor so the
 * protective path (reap the box -> reclaim the runaway log -> restart) can be verified on device WITHOUT
 * first filling ~58 GB. Lives in src/debug, so it never ships in release.
 *
 * <p>Exported (it is the whole point — an adb-reachable surface, unlike the app's non-exported
 * services), mirroring {@link org.appdevforall.k2go.delivery.debug.DebugDeliveryReceiver}. A huge
 * floor makes any real free-space reading CRITICAL, tripping the guard for real. Example:
 *
 * <pre>
 * adb shell am broadcast \
 *   -a org.appdevforall.k2go.DEBUG_DISK_GUARD \
 *   -n org.appdevforall.k2go/org.appdevforall.k2go.diskguard.debug.DebugDiskGuardReceiver \
 *   --el floor_bytes 999999999999
 * </pre>
 *
 * Watch it act in logcat: {@code adb logcat -s K2Go-DiskGuard}. The debug hook runs the FORCED path,
 * which always CONTAINs: it reaps and reclaims, then leaves the server desired=UP and asks the
 * reconciler to relaunch a fresh box. It never advances the real escalation count, so repeated
 * triggers cannot stop the box.
 */
public final class DebugDiskGuardReceiver extends BroadcastReceiver {

    private static final String TAG = "K2Go-DiskGuard";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final long floor = intent.getLongExtra("floor_bytes", Long.MAX_VALUE);
        Log.w(TAG, "K2GO-386: debug disk-guard test hook fired (floor_bytes=" + floor + ")");
        new Thread(() -> {
            try {
                DiskGuard.checkWithFloor(app, floor);
            } catch (Throwable t) {
                Log.w(TAG, "K2GO-386: debug disk-guard test hook failed", t);
            }
        }, "debug-disk-guard").start();
    }
}
