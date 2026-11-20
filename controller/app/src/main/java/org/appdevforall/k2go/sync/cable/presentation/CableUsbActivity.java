/*
 * ============================================================================
 * Name        : CableUsbActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Target of the USB attach filters for the Clone cable (K2GO-584).
 *               When the user picks K2Go (and "Always") in the system dialog,
 *               Android grants the USB permission to the app; CableSession then
 *               opens the link without its own dialog. No UI: it closes at once.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.presentation;

import android.app.Activity;
import android.os.Bundle;

public final class CableUsbActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
