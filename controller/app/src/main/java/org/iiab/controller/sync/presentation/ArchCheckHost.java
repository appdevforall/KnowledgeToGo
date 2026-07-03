/*
 * ============================================================================
 * Name        : ArchCheckHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between SyncFragment and ArchCheckController (ADFA-4506).
 *               The arch-label visibility depends on the sync mode toggle and the
 *               share/APK server flags, which stay owned by the Fragment (and,
 *               later, by the Share/APK controllers), so they are read back here.
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

public interface ArchCheckHost {
    /** True while the rsync daemon or the APK server is running. */
    boolean isServerRunning();

    /** True when the sync-mode toggle is on "Share" (vs "Receive"). */
    boolean isShareMode();
}
