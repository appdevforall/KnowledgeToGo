/*
 * ============================================================================
 * Name        : SyncStateViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Activity-scoped holder for the Share feature's TransportEngine,
 *               so an in-flight rsync transfer is NOT tied to a single
 *               SyncFragment instance and survives a configuration-change
 *               recreation (theme toggle / rotation). The transport holds a
 *               Process/socket (no Context/View), so it is leak-safe to keep
 *               here. ADFA-4492 (3b-2).
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

import androidx.lifecycle.ViewModel;

import org.iiab.controller.RsyncManager;
import org.iiab.controller.sync.transport.TransportEngine;

public class SyncStateViewModel extends ViewModel {

    private TransportEngine transport;

    /** The single transport instance for this Activity; created lazily, reused across recreations. */
    public TransportEngine getTransport() {
        if (transport == null) transport = new RsyncManager();
        return transport;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (transport != null) {
            transport.stop();
            transport = null;
        }
    }
}
