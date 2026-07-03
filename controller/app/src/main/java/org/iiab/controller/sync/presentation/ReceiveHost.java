/*
 * ============================================================================
 * Name        : ReceiveHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between SyncFragment and ReceiveController (ADFA-4506).
 *               The receive flow (scan -> probe -> dry-run -> transfer) is owned
 *               by the controller; the QR scanner (registerForActivityResult),
 *               the mode toggle, the arch dialogs and the system-protection are
 *               owned/shared by the Fragment and reached through this Host.
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

public interface ReceiveHost {
    /** True when the embedded IIAB server is running (MainActivity.isServerAlive). */
    boolean isServerAlive();

    /** ADFA-4496 pre-flight: true when the phantom-process monitor is NOT active. */
    boolean isSystemOptimizedForSync();

    /** Show the informed phantom-process warning; run onContinue if the user proceeds. */
    void showPhantomWarningDialog(Runnable onContinue);

    /** Start/stop the Watchdog foreground service that protects long transfers. */
    void enableSystemProtection();
    void disableSystemProtection();

    /** This device's architecture width in bits (for the QR arch check). */
    int getArchBits();

    /** Arch (in)compatibility feedback — delegated to ArchCheckController. */
    void showArchIncompatibilityDialog(String message);
    void showArchCompatibilitySuccess(Runnable onComplete);

    /** Launch the QR scanner (the ActivityResult launcher lives on the Fragment). */
    void launchQrScanner();

    /** Ensure the mode toggle is on "Receive" (so the transfer UI is visible). */
    void selectReceiveMode();
}
