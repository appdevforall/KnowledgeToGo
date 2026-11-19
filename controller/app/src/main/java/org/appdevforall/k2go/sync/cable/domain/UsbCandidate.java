/*
 * ============================================================================
 * Name        : UsbCandidate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Which USB devices the Clone cable may touch (K2GO-584). A
 *               phone exposes MTP/PTP (class 6) or vendor (class 255)
 *               interfaces, or none in charge-only mode. A hub, drive,
 *               keyboard or audio device does not, so it gets no permission
 *               dialog and no accessory switch.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class UsbCandidate {

    private static final int CLASS_STILL_IMAGE = 6;   // MTP / PTP
    private static final int CLASS_VENDOR = 255;      // ADB, vendor MTP

    private UsbCandidate() {
    }

    /** True when the device can be the other phone. */
    public static boolean canBePhone(boolean accessoryMode, int[] interfaceClasses) {
        if (accessoryMode || interfaceClasses.length == 0) return true;
        for (int c : interfaceClasses) {
            if (c == CLASS_STILL_IMAGE || c == CLASS_VENDOR) return true;
        }
        return false;
    }
}
