package org.appdevforall.k2go.rootfs.domain;

import java.util.Locale;

/**
 * The deployment tiers a rootfs image is published for.
 *
 * <p>Note on naming: the product copy sometimes calls the middle tier "medium",
 * but the Deploy server and this codebase use {@code STANDARD}. Keep this enum
 * authoritative and map any "medium" wording to {@link #STANDARD}.
 *
 * <p>Pure domain type: no Android and no networking dependencies.
 */
public enum RootfsTier {
    BASIC,
    STANDARD,
    FULL;

    /** Server-side filename token for this tier (e.g. {@code basic} in {@code latest_basic_arm64-v8a.meta4}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
