/*
 * ============================================================================
 * Name        : BusyMessage.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5146. The one place that turns "what holds the environment" into a refusal
 *               string, so the six deep-op gates name the real cause (a copy, a backup, a restore,
 *               an install, a download) instead of always saying "an install". One definition of
 *               "who holds it" lives in EnvironmentLock.currentHolder; this only maps it to a
 *               localized resource.
 * ============================================================================
 */
package org.iiab.controller.util;

import android.content.Context;

import org.iiab.controller.R;
import org.iiab.controller.env.EnvironmentLock;

public final class BusyMessage {

    private BusyMessage() {
    }

    /** The refusal string for whatever holds the environment right now. */
    public static int resFor(Context ctx) {
        switch (EnvironmentLock.currentHolder(ctx)) {
            case CLONE:    return R.string.k2go_busy_clone;
            case BACKUP:   return R.string.k2go_busy_backup;
            case RESTORE:  return R.string.k2go_busy_restore;
            case DOWNLOAD: return R.string.k2go_busy_download;
            case INSTALL:
            default:       return R.string.k2go_busy_install;   // NONE is not expected at a refusal
        }
    }
}
