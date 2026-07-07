/*
 * ============================================================================
 * Name        : HotspotAvailability.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure-ish helper for the ADFA-4520 fallback trigger. The proactive
 *               half of the AND: is there no SIM in the device? Combined by the UI
 *               with the reactive half (the native hotspot was tried and did not
 *               come up) to decide whether to recommend the LocalOnlyHotspot path.
 * ============================================================================
 */
package org.iiab.controller.hotspot;

import android.content.Context;
import android.telephony.TelephonyManager;

public final class HotspotAvailability {

    private HotspotAvailability() {}

    /**
     * True when no SIM is present (the case that gates the system hotspot on many
     * carrier/OEM builds). eSIM-only or SIM-present-but-inactive are intentionally
     * NOT treated as "absent" here — V1 targets the clear no-SIM case only.
     */
    public static boolean isSimAbsent(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getApplicationContext()
                    .getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return false;
            return tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT;
        } catch (Throwable t) {
            return false;
        }
    }
}
