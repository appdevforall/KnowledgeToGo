package org.iiab.controller.env.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import org.iiab.controller.redesign.ZimDownloadService;

/**
 * DEBUG-ONLY (lives in src/debug; never ships in release). Reproduces the ADFA-5146 "stale busy
 * flag" case deterministically, which is otherwise hard to trigger by hand: a content download's
 * heartbeat goes cold because its poll loop died while the process stayed alive.
 *
 * <p>This is NOT the same as "start a download and turn off Wi-Fi" — with Wi-Fi off the poll loop
 * keeps running (it polls the in-server engine over localhost) and keeps the heartbeat fresh, so
 * the flag stays busy by design. This hook instead seeds a session marked active with a fresh
 * heartbeat but no loop behind it, so nothing refreshes it and it self-expires after
 * {@code Freshness.STALE_MS} (~30 s).
 *
 * <pre>
 * adb shell am broadcast \
 *   -a org.iiab.controller.DEBUG_STALE_DL \
 *   -n org.iiab.controller/org.iiab.controller.env.debug.DebugStaleDownloadReceiver
 * </pre>
 *
 * Expected: right after the broadcast, Clone &gt; Send reports busy (fresh heartbeat); after ~30 s
 * without touching anything, Clone &gt; Send proceeds (the flag expired). Watch:
 * {@code adb logcat -s IIAB-DebugStaleDL}.
 */
public final class DebugStaleDownloadReceiver extends BroadcastReceiver {

    private static final String TAG = "IIAB-DebugStaleDL";

    @Override
    public void onReceive(Context context, Intent intent) {
        ZimDownloadService.debugSeedStaleCandidate();
        Log.i(TAG, "Seeded a fresh fake ZIM session (no poll loop). It blocks now and should "
                + "self-expire after Freshness.STALE_MS. Retry Clone > Send after ~30 s.");
        Toast.makeText(context.getApplicationContext(),
                "Debug: stale busy flag seeded — expires in ~30 s", Toast.LENGTH_SHORT).show();
    }
}
