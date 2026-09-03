/*
 * ============================================================================
 * Name        : RootfsIdentity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-372. Is this archive a rootfs, and is it ours to unpack?
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

/**
 * The identity half of the archive verdict: what the manifest alone can settle.
 *
 * <p>Separate from the rest of validation because it is answerable from a few KB — the manifest is
 * packed first — while the structural checks need the whole archive listed. Splitting it lets a
 * caller reject the wrong file before paying for a full decompression pass, which on a phone is
 * the difference between a one-second refusal and a five-minute one.
 *
 * <p>Pure: the ABI to compare against is passed in rather than read from {@code android.os}, so the
 * rule is unit-tested on the JVM instead of only on a device.
 */
public final class RootfsIdentity {

    /** What the manifest says about whether this archive may be unpacked here. */
    public enum Verdict {
        /** Nothing to reject: identity checks out, or there is no manifest to ask. */
        OK,
        /** The manifest says this is not a rootfs at all. */
        NOT_A_ROOTFS,
        /** A rootfs, but built for a different ABI than this app runs. */
        WRONG_ARCH
    }

    /**
     * The kinds a rootfs manifest may declare; anything else is a different sort of archive.
     *
     * <p>K2GO-90: two are accepted so the manifest can be renamed later without a broken window.
     * The reader has to ship before the writer changes — an app that only knew the new name would
     * reject every archive built before the switch, including the backups users are told to make
     * before migrating. The builder still writes {@code iiab-rootfs}; this only removes the reason
     * it cannot stop.
     */
    private static final java.util.List<String> KIND_ROOTFS =
            java.util.Arrays.asList("iiab-rootfs", "k2go-rootfs");

    private RootfsIdentity() {
    }

    /**
     * Decide whether the manifest rejects this archive.
     *
     * <p>An absent manifest is {@link Verdict#OK} rather than a rejection: older archives predate
     * it, and refusing them here would turn a missing hint into a hard failure. The structural
     * fallback decides those.
     *
     * <p>An empty or absent {@code arch} is likewise not a rejection — the manifest simply does not
     * claim one, so there is nothing to contradict.
     *
     * @param present whether the archive carried a manifest at all.
     * @param kind    the manifest's declared kind.
     * @param arch    the manifest's declared ABI.
     * @param appAbi  the ABI this app runs as; the archive must match it to be unpackable.
     */
    public static Verdict check(boolean present, String kind, String arch, String appAbi) {
        if (!present) {
            return Verdict.OK;
        }
        if (!KIND_ROOTFS.contains(kind)) {
            return Verdict.NOT_A_ROOTFS;
        }
        if (arch != null && !arch.isEmpty() && appAbi != null && !arch.equals(appAbi)) {
            return Verdict.WRONG_ARCH;
        }
        return Verdict.OK;
    }
}
