package org.iiab.controller.rootfs.data;

import android.os.Build;

import org.iiab.controller.rootfs.domain.RootfsAbi;
import org.iiab.controller.rootfs.domain.RootfsTier;

import java.util.Locale;

/**
 * Data-layer catalog: maps a tier+abi to its Deploy-server URL and provides the
 * hardcoded fallback sizes. Single source of truth for both.
 *
 * <p>Fallback sizes are in BYTES, captured from the {@code latest_*.meta4}
 * Metalink pointers on 2026-06-17. They are only used when the live lookup fails
 * (offline or server error). Update them whenever the published images change
 * substantially.
 */
public class RootfsCatalog {

    private static final String BASE_URL = "https://iiab.switnet.org/android/rootfs/";

    // arm64-v8a fallbacks
    private static final long FALLBACK_BASIC_ARM64 = 1_219_422_532L;    // 1.14 GiB
    private static final long FALLBACK_STANDARD_ARM64 = 1_428_970_336L; // 1.33 GiB
    private static final long FALLBACK_FULL_ARM64 = 2_926_676_923L;     // 2.73 GiB

    // armeabi-v7a fallbacks
    private static final long FALLBACK_BASIC_ARMV7 = 1_220_401_364L;    // 1.14 GiB
    private static final long FALLBACK_STANDARD_ARMV7 = 1_429_892_132L; // 1.33 GiB
    private static final long FALLBACK_FULL_ARMV7 = 2_917_715_443L;     // 2.72 GiB

    /** Builds the stable Metalink URL, e.g. {@code .../latest_basic_arm64-v8a.meta4}. */
    public String metaUrl(RootfsTier tier, RootfsAbi abi) {
        return BASE_URL + "latest_" + tier.name().toLowerCase(Locale.US) + "_" + abi.id() + ".meta4";
    }

    /** Known-good fallback size in bytes for a tier+abi. */
    public long fallbackBytes(RootfsTier tier, RootfsAbi abi) {
        if (abi == RootfsAbi.ARMEABI_V7A) {
            switch (tier) {
                case BASIC:
                    return FALLBACK_BASIC_ARMV7;
                case STANDARD:
                    return FALLBACK_STANDARD_ARMV7;
                case FULL:
                    return FALLBACK_FULL_ARMV7;
            }
        } else {
            switch (tier) {
                case BASIC:
                    return FALLBACK_BASIC_ARM64;
                case STANDARD:
                    return FALLBACK_STANDARD_ARM64;
                case FULL:
                    return FALLBACK_FULL_ARM64;
            }
        }
        return FALLBACK_BASIC_ARM64;
    }

    /**
     * Detects the device ABI for rootfs selection. Prefers 64-bit when available,
     * otherwise treats the device as 32-bit ARM.
     */
    public RootfsAbi detectAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if (RootfsAbi.ARM64_V8A.id().equals(abi)) {
                    return RootfsAbi.ARM64_V8A;
                }
            }
        }
        return RootfsAbi.ARMEABI_V7A;
    }
}
