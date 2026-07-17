package org.iiab.controller.rootfs.data;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.iiab.controller.rootfs.domain.RootfsAbi;
import org.iiab.controller.rootfs.domain.RootfsTier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Data-layer catalog: maps a tier+abi to its Deploy-server URL and provides the offline
 * fallback size.
 *
 * <p>The fallback size is read from {@code assets/rootfs_sizes.csv}, which the
 * {@code refreshRootfsSizes} Gradle task regenerates from the {@code latest_*.meta4} pointers at
 * package time — so the "last known" values are captured automatically, never hand-maintained.
 * The hardcoded constants below remain ONLY as an emergency net (missing/corrupt CSV) so the
 * lookup can never return 0. Values in bytes, captured 2026-06-17 (matches the seed CSV).
 */
public class RootfsCatalog {

    private static final String TAG = "RootfsCatalog";
    private static final String CSV_ASSET = "rootfs_sizes.csv";
    private static final String BASE_URL = "https://iiab.switnet.org/android/rootfs/";

    // Emergency-net fallbacks (used only if the CSV is missing/unreadable).
    private static final long FALLBACK_BASIC_ARM64 = 1_219_422_532L;    // 1.14 GiB
    private static final long FALLBACK_STANDARD_ARM64 = 1_428_970_336L; // 1.33 GiB
    private static final long FALLBACK_FULL_ARM64 = 2_926_676_923L;     // 2.73 GiB
    private static final long FALLBACK_BASIC_ARMV7 = 1_220_401_364L;    // 1.14 GiB
    private static final long FALLBACK_STANDARD_ARMV7 = 1_429_892_132L; // 1.33 GiB
    private static final long FALLBACK_FULL_ARMV7 = 2_917_715_443L;     // 2.72 GiB

    /** Parsed CSV, loaded once per process. Empty map => use the emergency-net constants. */
    private static volatile Map<String, Long> csvSizes;

    /** No-arg: emergency-net only (no CSV). Kept for callers without a Context. */
    public RootfsCatalog() { }

    /** Context-aware: loads the packaged CSV so fallbackBytes() returns the last-known size. */
    public RootfsCatalog(Context context) { ensureCsvLoaded(context); }

    private static void ensureCsvLoaded(Context context) {
        if (csvSizes != null || context == null) return;
        synchronized (RootfsCatalog.class) {
            if (csvSizes != null) return;
            Map<String, Long> m = new HashMap<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(CSV_ASSET)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(",");
                    if (p.length >= 3) {
                        try {
                            m.put(key(p[0].trim(), p[1].trim()), Long.parseLong(p[2].trim()));
                        } catch (NumberFormatException ignore) { /* skip malformed row */ }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "rootfs_sizes.csv not read (" + e.getMessage() + "); using emergency-net sizes");
            }
            csvSizes = m;
        }
    }

    private static String key(String tier, String abi) {
        return tier.toLowerCase(Locale.US) + "|" + abi.toLowerCase(Locale.US);
    }

    /** Builds the stable Metalink URL, e.g. {@code .../latest_basic_arm64-v8a.meta4}. */
    public String metaUrl(RootfsTier tier, RootfsAbi abi) {
        return BASE_URL + "latest_" + tier.name().toLowerCase(Locale.US) + "_" + abi.id() + ".meta4";
    }

    /** Last-known fallback size in bytes for a tier+abi: CSV first, constants as the net. */
    public long fallbackBytes(RootfsTier tier, RootfsAbi abi) {
        Map<String, Long> csv = csvSizes;
        if (csv != null) {
            Long v = csv.get(key(tier.name(), abi.id()));
            if (v != null && v > 0) return v;
        }
        if (abi == RootfsAbi.ARMEABI_V7A) {
            switch (tier) {
                case BASIC: return FALLBACK_BASIC_ARMV7;
                case STANDARD: return FALLBACK_STANDARD_ARMV7;
                case FULL: return FALLBACK_FULL_ARMV7;
            }
        } else {
            switch (tier) {
                case BASIC: return FALLBACK_BASIC_ARM64;
                case STANDARD: return FALLBACK_STANDARD_ARM64;
                case FULL: return FALLBACK_FULL_ARM64;
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
