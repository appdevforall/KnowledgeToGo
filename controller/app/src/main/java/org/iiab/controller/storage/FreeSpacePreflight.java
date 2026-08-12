/*
 * ============================================================================
 * Name        : FreeSpacePreflight.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. The one preflight every destructive route calls
 *               before it wipes: reads free space (StorageProbe, on the real
 *               write target), asks the single rule (StorageGuard), and returns
 *               a proceed/refuse result with the shortfall for the message.
 *
 *               Fail-safe by construction for destructive callers: it proceeds
 *               only on FITS, so both DOES_NOT_FIT and UNKNOWN free space refuse
 *               (a wipe that then runs out of disk leaves an unbootable rootfs).
 *               Advisory callers that want "allow on unknown" should read
 *               StorageGuard directly instead of this.
 * ============================================================================
 */
package org.iiab.controller.storage;

import android.content.Context;

public final class FreeSpacePreflight {

    private FreeSpacePreflight() {}

    /** Outcome of a destructive preflight. */
    public static final class Result {
        /** True only when the run fits with the margin; false means refuse. */
        public final boolean ok;
        /** Bytes the run needs (the "needed" that was checked). */
        public final long neededBytes;
        /** Bytes short of fitting (0 when it fits, or when free space is unknown). */
        public final long shortfallBytes;

        Result(boolean ok, long neededBytes, long shortfallBytes) {
            this.ok = ok;
            this.neededBytes = neededBytes;
            this.shortfallBytes = shortfallBytes;
        }

        /** The amount to surface in a refusal message: the shortfall if known, else the need. */
        public long amountToReport() {
            return shortfallBytes > 0 ? shortfallBytes : neededBytes;
        }
    }

    /** Check {@code neededBytes} against the free space on the app's files filesystem. */
    public static Result check(Context ctx, long neededBytes) {
        Long free = StorageProbe.freeBytes(ctx);
        boolean ok = StorageGuard.evaluate(free, neededBytes) == StorageGuard.Verdict.FITS;
        return new Result(ok, neededBytes, StorageGuard.shortfallBytes(free, neededBytes));
    }
}
