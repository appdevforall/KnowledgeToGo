/*
 * ============================================================================
 * Name        : ExtractProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4915. Pure rule for the rootfs Extract phase progress:
 *               percent = members_extracted / total, clamped to [0,99]. 100 is
 *               reserved for completion (set explicitly), so the bar never shows
 *               "done" mid-extraction. Pure JVM (no android.*) => unit-testable.
 * ============================================================================
 */
package org.iiab.controller.deploy.domain;

public final class ExtractProgress {

    private ExtractProgress() { }

    /**
     * Percent of archive members extracted so far, clamped to [0,99].
     * Returns 0 when nothing is known yet (total unknown/empty or done<=0).
     * 99 is the cap during extraction; the caller sets 100 only on completion.
     */
    public static int percent(long done, long total) {
        if (total <= 0L || done <= 0L) return 0;
        long p = done * 100L / total;
        if (p < 0L) return 0;
        if (p > 99L) return 99;
        return (int) p;
    }
}
