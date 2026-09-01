/*
 * ============================================================================
 * Name        : StorageGuard.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. The single free-space rule, asked before any
 *               destructive run (install / reinstall / restore / reset) and by
 *               the advisory "does this fit" labels on the content pickers.
 *
 *               Replaces sixteen hand-rolled readers with two magic margins
 *               (-5.0 GB absolute in the legacy install + clone-receive, +10%
 *               relative in the Kolibri seed plan). One margin, decided once
 *               (ADFA-5105): the headroom required is the LARGER of an absolute
 *               floor and a fraction of the operation size, so small ops are
 *               protected by the floor and large ops by the fraction.
 *
 *               Pure JVM (no android.*) so it is unit-testable — the Android
 *               StatFs read of the actual write target is a thin caller, kept
 *               out of here on purpose (same split as SeedPlan). Three-state
 *               verdict: destructive callers must treat UNKNOWN as "refuse"
 *               (fail-safe, since a wipe that runs out of disk leaves an
 *               unbootable rootfs); advisory labels may treat UNKNOWN as
 *               "allow" — one helper, the interpretation lives at the call site.
 * ============================================================================
 */
package org.appdevforall.k2go.storage;

public final class StorageGuard {

    /** Absolute headroom floor (2 GiB): the minimum slack left free after any op. */
    public static final long DEFAULT_FLOOR_BYTES = 2L * 1024 * 1024 * 1024;
    /** Relative headroom (10% of the operation size), used when it exceeds the floor. */
    public static final int DEFAULT_MARGIN_PERCENT = 10;

    public enum Verdict { FITS, DOES_NOT_FIT, UNKNOWN }

    private StorageGuard() {}

    /** Bytes that must be free for {@code neededBytes} to fit, headroom included. */
    public static long requiredBytes(long neededBytes) {
        return requiredBytes(neededBytes, DEFAULT_FLOOR_BYTES, DEFAULT_MARGIN_PERCENT);
    }

    /** As above with an explicit policy. Overflow-safe and never returns less than {@code needed}. */
    public static long requiredBytes(long neededBytes, long floorBytes, int marginPercent) {
        long need = Math.max(0L, neededBytes);
        long floor = Math.max(0L, floorBytes);
        int pct = Math.max(0, marginPercent);
        // need * pct / 100 without the intermediate overflow of a full multiply.
        long relative = (need / 100L) * pct + ((need % 100L) * pct) / 100L;
        long headroom = Math.max(floor, relative);
        long required = need + headroom;
        return required < need ? Long.MAX_VALUE : required;   // saturate on overflow
    }

    /** FITS / DOES_NOT_FIT / UNKNOWN (null or negative free space) with the default policy. */
    public static Verdict evaluate(Long freeBytes, long neededBytes) {
        return evaluate(freeBytes, neededBytes, DEFAULT_FLOOR_BYTES, DEFAULT_MARGIN_PERCENT);
    }

    public static Verdict evaluate(Long freeBytes, long neededBytes, long floorBytes, int marginPercent) {
        if (freeBytes == null || freeBytes < 0L) return Verdict.UNKNOWN;
        return freeBytes >= requiredBytes(neededBytes, floorBytes, marginPercent)
                ? Verdict.FITS : Verdict.DOES_NOT_FIT;
    }

    /** How many more bytes are needed to fit (0 when it fits, or when free space is unknown). */
    public static long shortfallBytes(Long freeBytes, long neededBytes) {
        return shortfallBytes(freeBytes, neededBytes, DEFAULT_FLOOR_BYTES, DEFAULT_MARGIN_PERCENT);
    }

    public static long shortfallBytes(Long freeBytes, long neededBytes, long floorBytes, int marginPercent) {
        if (freeBytes == null || freeBytes < 0L) return 0L;
        long deficit = requiredBytes(neededBytes, floorBytes, marginPercent) - freeBytes;
        return Math.max(0L, deficit);
    }
}
