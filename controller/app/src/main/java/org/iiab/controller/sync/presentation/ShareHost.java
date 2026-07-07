/*
 * ============================================================================
 * Name        : ShareHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between SyncFragment and ShareController (ADFA-4506).
 *               The Share area (rsync daemon + APK server) is owned by the
 *               controller; the system-protection (Watchdog / phantom pre-flight)
 *               is shared with the Receive flow and stays on the Fragment, so the
 *               controller reaches it through this Host. isServerAlive() bridges
 *               the MainActivity coupling; the arch helpers delegate to
 *               ArchCheckController.
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

public interface ShareHost {
    /** True when the embedded IIAB server is running (via the app-level ServerStateRepository). */
    boolean isServerAlive();

    /** ADFA-4496 pre-flight: true when the phantom-process monitor is NOT active. */
    boolean isSystemOptimizedForSync();

    /** Show the informed phantom-process warning; run onContinue if the user proceeds. */
    void showPhantomWarningDialog(Runnable onContinue);

    /** Start/stop the Watchdog foreground service that protects long transfers. */
    void enableSystemProtection();
    void disableSystemProtection();

    /** Re-evaluate the guest arch label visibility (delegates to ArchCheckController). */
    void updateArchLabelsVisibility();

    /** This device's architecture width in bits, for the rsync QR payload. */
    int getArchBits();
}
