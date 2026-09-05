/*
 * ============================================================================
 * Name        : DiskGuardPolicy.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386. The pure runtime rule for "is free space critically
 *               low while the server is up?" — the general disk-fill safety net.
 *
 *               Barrier 2 of K2GO-386: a runaway box process (proven: php-fpm
 *               orphaned off proot, busy-looping into /var/log at ~600 MB/min)
 *               fills the device to ENOSPC, and the in-box healer cannot help —
 *               it dies with the box. Only an Android-side guard, independent of
 *               the box, catches it. And it catches ANY runaway, not just php:
 *               every disk-fill travels through one common surface, free space.
 *
 *               The critical floor is set BELOW StorageGuard's 2 GiB op-floor:
 *               every app-driven op reserves >= 2 GiB headroom (StorageGuard),
 *               so free space only crosses below ~2 GiB when something fills the
 *               disk WITHOUT that headroom check — i.e. a runaway. 1.5 GiB still
 *               leaves runway to act before ENOSPC (~2.5 min at ~600 MB/min).
 *
 *               Pure JVM (no android.*) so it is unit-testable; the StatFs read
 *               is the thin caller (StorageProbe), kept out on purpose.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard.domain;

public final class DiskGuardPolicy {

    /** Act when free space drops below this while the server is up. Below StorageGuard's 2 GiB
     *  op-floor on purpose: legit ops keep >= 2 GiB free, so only a runaway crosses this line. */
    public static final long CRITICAL_FLOOR_BYTES = 1536L * 1024 * 1024;   // 1.5 GiB

    public enum Level { OK, CRITICAL, UNKNOWN }

    private DiskGuardPolicy() {}

    /** Evaluate free space against the default critical floor. */
    public static Level evaluate(Long freeBytes) {
        return evaluate(freeBytes, CRITICAL_FLOOR_BYTES);
    }

    /** As above with an explicit floor. A null/negative read is UNKNOWN — the guard must NOT tear
     *  down the box on a failed read (fail-safe: the read is the uncertain half). */
    public static Level evaluate(Long freeBytes, long floorBytes) {
        if (freeBytes == null || freeBytes < 0L) return Level.UNKNOWN;
        return freeBytes < floorBytes ? Level.CRITICAL : Level.OK;
    }
}
