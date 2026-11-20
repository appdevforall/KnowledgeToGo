/*
 * ============================================================================
 * Name        : UsbCandidate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Which USB devices the Clone cable may touch (K2GO-584). A
 *               phone exposes MTP/PTP (class 6), vendor (class 255, also ADB)
 *               or USB tethering (CDC 2/10, wireless 0xE0, misc 0xEF)
 *               interfaces, or none in charge-only mode. A hub, drive,
 *               keyboard, webcam or audio device does not, so it gets no
 *               permission dialog and no accessory switch. A phone in MIDI
 *               mode looks like an audio device and is not switched.
 *               A candidate that has no accessory mode (an iPhone, or a phone
 *               that refuses the switch) cannot use Clone.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class UsbCandidate {

    private static final int[] PHONE_CLASSES = {
            6,      // still image: MTP, PTP
            255,    // vendor specific: ADB, vendor MTP
            2, 10,  // CDC control and data: USB tethering (NCM, ECM)
            0xE0,   // wireless controller: USB tethering (RNDIS)
            0xEF,   // miscellaneous: USB tethering (RNDIS on some phones)
    };

    /** Apple devices have no Android accessory mode: no permission dialog, no switch. */
    private static final int APPLE_VID = 0x05AC;

    private UsbCandidate() {
    }

    /** True when the device is known to have no accessory mode, before any permission. */
    public static boolean hasNoAccessoryMode(int vendorId) {
        return vendorId == APPLE_VID;
    }

    /** True when the device can be the other phone. */
    public static boolean canBePhone(boolean accessoryMode, int[] interfaceClasses) {
        if (accessoryMode || interfaceClasses.length == 0) return true;
        for (int c : interfaceClasses) {
            for (int p : PHONE_CLASSES) {
                if (c == p) return true;
            }
        }
        return false;
    }
}
