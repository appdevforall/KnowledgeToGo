/*
 * ============================================================================
 * Name        : InstallJobs.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4919. Single predicate for "an install job is already in flight", used to keep
 *               proot and REST from overlapping.
 *
 *               WHY this exists (for ADFA-4842 module management to reuse): proot module runroles
 *               act on the LIVE system, so they run one-at-a-time and EXCLUSIVE of REST. REST
 *               downloads (ZIM + Books) may run in parallel with each other, but only after the
 *               proot stage. The wizard already serializes proot->REST internally, but any NEW,
 *               independent entry point (Get More today; module management next) can start a proot
 *               module out of band, so it must first refuse if anything is already running. Kept in
 *               one place so 4842 reuses the exact same rule instead of re-deriving it.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import org.iiab.controller.install.presentation.ModuleQueueRepository;

public final class InstallJobs {

    private InstallJobs() {}

    /** True while a proot runrole is in flight OR a REST download session is still going. */
    public static boolean isBusy() {
        if (ModuleQueueRepository.get().isRunning()) return true;                          // proot module runrole
        if (ZimDownloadService.hasSession() && !ZimDownloadService.isComplete()) return true;
        if (BooksDownloadService.hasSession() && !BooksDownloadService.isComplete()) return true;
        return false;
    }
}
