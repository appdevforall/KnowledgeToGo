/*
 * ============================================================================
 * Name        : SwitchRefusals.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : When a USB device counts as one without accessory mode
 *               (K2GO-584): it failed the accessory switch TRIES times in a
 *               row. A phone that is still re-enumerating can fail one
 *               request. The owner clears the count when the device leaves.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class SwitchRefusals {

    static final int TRIES = 3;

    private String lastDevice;   // the device that failed the last switch
    private int inARow;
    private String unsupported;

    /** Records a failed switch of {@code device}. */
    public synchronized void failed(String device) {
        inARow = device.equals(lastDevice) ? inARow + 1 : 1;
        lastDevice = device;
        if (inARow >= TRIES) unsupported = device;
    }

    /** A switch request went through: the failures are no longer in a row. */
    public synchronized void succeeded() {
        lastDevice = null;
        inARow = 0;
    }

    /** True when {@code device} has no accessory mode, so it gets no dialog and no switch. */
    public synchronized boolean unsupported(String device) {
        return device.equals(unsupported);
    }

    /** {@code device} left the bus: a replug starts its count again. Other devices keep theirs. */
    public synchronized void forget(String device) {
        if (device.equals(lastDevice)) {
            lastDevice = null;
            inARow = 0;
        }
        if (device.equals(unsupported)) unsupported = null;
    }

    public synchronized void clear() {
        lastDevice = null;
        inARow = 0;
        unsupported = null;
    }
}
