/*
 * ============================================================================
 * Name        : CloneSendSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4960. Process-scoped holder for an ACTIVE clone SEND session, so a recreated
 *               CloneFragment (rotation, tab re-select, reopen while the process lives) re-binds to
 *               the live "sharing" screen instead of the fork. Scoped to the process on purpose: the
 *               rsync share daemon is an app-process child kept alive by CloneShareService, so the
 *               daemon and this holder share the SAME lifetime — both are gone on true process death,
 *               so there is never a false resume. tempPass is stored because it is the QR secret and
 *               cannot be recomputed; a receiver that already scanned keeps valid credentials. Mirrors
 *               SyncProgressRepository (receive), which clone already used to re-bind.
 * ============================================================================
 */
package org.iiab.controller.redesign;

public final class CloneSendSession {

    private static volatile boolean active;
    private static boolean hotspot;              // true = HOTSPOT, false = WIFI
    private static String tempPass;              // rsync secret baked into the QR — not recomputable
    private static boolean hostHasRootfs;
    private static boolean shareAnyway;          // ADFA-4786 empty-library override
    private static LibrarySize.Split split;      // approx system/content sizes for the QR + size card

    private CloneSendSession() {}

    public static synchronized void begin(boolean hotspot, String tempPass, boolean hostHasRootfs,
                                          boolean shareAnyway, LibrarySize.Split split) {
        CloneSendSession.active = true;
        CloneSendSession.hotspot = hotspot;
        CloneSendSession.tempPass = tempPass;
        CloneSendSession.hostHasRootfs = hostHasRootfs;
        CloneSendSession.shareAnyway = shareAnyway;
        CloneSendSession.split = split;
    }

    public static synchronized void clear() {
        active = false;
        tempPass = null;
        split = null;
        hostHasRootfs = false;
        shareAnyway = false;
    }

    public static boolean isActive()        { return active; }
    public static boolean isHotspot()       { return hotspot; }
    public static String tempPass()         { return tempPass; }
    public static boolean hostHasRootfs()   { return hostHasRootfs; }
    public static boolean shareAnyway()     { return shareAnyway; }
    public static LibrarySize.Split split() { return split; }
}
