package org.iiab.controller.pending.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import org.iiab.controller.kolibri.data.KolibriWishlist;
import org.iiab.controller.redesign.BooksWishlist;
import org.iiab.controller.redesign.ZimWishlist;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DEBUG-ONLY (lives in src/debug; never ships in release). Seeds a few banked content
 * orders — two ZIM, one Books, one Courses — so the Pending downloads screen (ADFA-5169,
 * finding 6) can be validated without arranging a real deferred drain.
 *
 * <pre>
 * adb shell am broadcast \
 *   -a org.iiab.controller.DEBUG_SEED_PENDING \
 *   -n org.iiab.controller/org.iiab.controller.pending.debug.DebugSeedPendingReceiver
 * </pre>
 *
 * Then open Settings &gt; Pending downloads to see them and Cancel each. Stay off the Home
 * tab while validating: Home's pump drains the wishlists on a live system, which would
 * clear the seed. Watch {@code adb logcat -s IIAB-DebugSeedPending}. Remove before merge.
 */
public final class DebugSeedPendingReceiver extends BroadcastReceiver {

    private static final String TAG = "IIAB-DebugSeedPending";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();

        Map<String, Long> zim = new LinkedHashMap<>();
        zim.put("wikipedia|en|maxi", 4_200_000_000L);
        zim.put("wikipedia|es|maxi", 3_100_000_000L);
        ZimWishlist.add(app, zim);

        BooksWishlist.add(app, "gutenberg-fiction", "Gutenberg — Fiction",
                "https://example.invalid/fiction.epub");

        KolibriWishlist.add(app, "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", 1,
                "Khan Academy — Math", 12_000_000_000L, null);

        Log.i(TAG, "Seeded pending orders: 2 ZIM, 1 Books, 1 Courses. "
                + "Open Settings > Pending downloads (stay off Home).");
        Toast.makeText(app, "Debug: seeded 4 pending orders", Toast.LENGTH_SHORT).show();
    }
}
