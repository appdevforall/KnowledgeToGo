package org.iiab.controller.redesign;

import android.os.SystemClock;
import android.view.View;

/** Hidden A/B-test gesture: 5 taps on the title within 1.5s flips the Step-2 layout. */
final class AbFlip {
    private AbFlip() { }
    static void attach(View title, Runnable onFlip) {
        final int[] count = {0};
        final long[] last = {0};
        title.setOnClickListener(v -> {
            long now = SystemClock.uptimeMillis();
            if (now - last[0] > 1500) count[0] = 0;
            last[0] = now;
            if (++count[0] >= 5) { count[0] = 0; onFlip.run(); }
        });
    }
}
