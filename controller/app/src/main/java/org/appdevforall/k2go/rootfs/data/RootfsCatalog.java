package org.appdevforall.k2go.rootfs.data;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.appdevforall.k2go.rootfs.domain.RootfsAbi;
import org.appdevforall.k2go.rootfs.domain.RootfsTier;

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

    // Emergency-net COMPRESSED (download) fallbacks — used only if the CSV is missing/unreadable.
    // Captured from latest_*.meta4, build 2026.224 (sha 8d15d79, 2026-08-12).
    private static final long FALLBACK_BASIC_ARM64 = 1_936_814_083L;    // ~1.80 GiB
    private static final long FALLBACK_STANDARD_ARM64 = 2_146_100_011L; // ~2.00 GiB
    private static final long FALLBACK_FULL_ARM64 = 2_841_912_764L;     // ~2.65 GiB
    private static final long FALLBACK_BASIC_ARMV7 = 1_937_574_673L;    // ~1.80 GiB
    private static final long FALLBACK_STANDARD_ARMV7 = 2_146_813_777L; // ~2.00 GiB
    private static final long FALLBACK_FULL_ARMV7 = 2_833_106_837L;     // ~2.64 GiB

    // Emergency-net UNCOMPRESSED (installed) fallbacks — the destructive guard's "needed", measured
    // by the builder (ADFA-5110) and published as latest_*.installed. Same build as above.
    private static final long INSTALLED_BASIC_ARM64 = 3_294_993_334L;    // ~3.07 GiB
    private static final long INSTALLED_STANDARD_ARM64 = 3_675_639_489L; // ~3.42 GiB
    private static final long INSTALLED_FULL_ARM64 = 5_103_603_118L;     // ~4.75 GiB
    private static final long INSTALLED_BASIC_ARMV7 = 3_162_669_853L;    // ~2.95 GiB
    private static final long INSTALLED_STANDARD_ARMV7 = 3_542_808_901L; // ~3.30 GiB
    private static final long INSTALLED_FULL_ARMV7 = 4_856_267_277L;     // ~4.52 GiB

    // ADFA-5105: the destructive free-space guard needs the UNCOMPRESSED (installed) footprint —
    // what an extraction actually writes — not the compressed download above. It comes from the
    // installed_bytes CSV column (refreshed from the latest_*.installed sidecar the builder now
    // publishes, ADFA-5110), with the measured emergency-net constants above as the offline fallback.

    /** Parsed CSV of COMPRESSED download sizes, loaded once per process. Empty => emergency-net. */
    private static volatile Map<String, Long> csvSizes;
    /** Parsed CSV of UNCOMPRESSED sizes (optional 5th column, ADFA-5110). Absent => estimate. */
    private static volatile Map<String, Long> csvInstalled;

    /** No-arg: emergency-net only (no CSV). Kept for callers without a Context. */
    public RootfsCatalog() { }

    /** Context-aware: loads the packaged CSV so fallbackBytes() returns the last-known size. */
    public RootfsCatalog(Context context) { ensureCsvLoaded(context); }

    private static void ensureCsvLoaded(Context context) {
        if (csvSizes != null || context == null) return;
        synchronized (RootfsCatalog.class) {
            if (csvSizes != null) return;
            Map<String, Long> m = new HashMap<>();
            Map<String, Long> mi = new HashMap<>();
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
                    // Optional installed_bytes column (ADFA-5110). Absent in the current CSV, so this
                    // stays empty until the build publishes it; installedBytes() then estimates.
                    if (p.length >= 5) {
                        try {
                            mi.put(key(p[0].trim(), p[1].trim()), Long.parseLong(p[4].trim()));
                        } catch (NumberFormatException ignore) { /* not the installed column yet */ }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "rootfs_sizes.csv not read (" + e.getMessage() + "); using emergency-net sizes");
            }
            csvSizes = m;
            csvInstalled = mi;
        }
    }

    private static String key(String tier, String abi) {
        return tier.toLowerCase(Locale.US) + "|" + abi.toLowerCase(Locale.US);
    }

    /** Builds the stable Metalink URL, e.g. {@code .../latest_basic_arm64-v8a.meta4}. */
    public String metaUrl(RootfsTier tier, RootfsAbi abi) {
        return BASE_URL + "latest_" + tier.name().toLowerCase(Locale.US) + "_" + abi.id() + ".meta4";
    }

    /** Stable sidecar URL for the UNCOMPRESSED size (ADFA-5110),
     *  e.g. {@code .../latest_full_arm64-v8a.installed} — a single integer, bytes. */
    public String installedUrl(RootfsTier tier, RootfsAbi abi) {
        return BASE_URL + "latest_" + tier.name().toLowerCase(Locale.US) + "_" + abi.id() + ".installed";
    }

    /**
     * Uncompressed (installed) size in bytes for a tier+abi — what an extraction actually writes,
     * and the "needed" figure the destructive free-space guard (ADFA-5105) checks. Prefers the
     * measured value from the CSV installed column (ADFA-5110); until that is published it returns a
     * conservative estimate from the compressed download size. Never returns {@code <= 0}.
     */
    public long installedBytes(RootfsTier tier, RootfsAbi abi) {
        Map<String, Long> csv = csvInstalled;
        if (csv != null) {
            Long v = csv.get(key(tier.name(), abi.id()));
            if (v != null && v > 0) return v;
        }
        return installedFallbackBytes(tier, abi);
    }

    /**
     * Peak disk a fresh rootfs install needs (ADFA-5105): the compressed download and the
     * uncompressed tree COEXIST during extraction (the .tar.gz is only deleted after it finishes),
     * and the free-space gate runs before the download — so both get written after it. The peak the
     * device must hold is compressed + uncompressed, not just the final footprint.
     */
    public long peakInstallBytes(RootfsTier tier, RootfsAbi abi) {
        long peak = installedBytes(tier, abi) + fallbackBytes(tier, abi);
        return peak < 0 ? Long.MAX_VALUE : peak;   // saturate on the (impossible) overflow
    }

    /** Last-known measured uncompressed size (emergency net) for a tier+abi. Never {@code <= 0}. */
    private long installedFallbackBytes(RootfsTier tier, RootfsAbi abi) {
        if (abi == RootfsAbi.ARMEABI_V7A) {
            switch (tier) {
                case BASIC: return INSTALLED_BASIC_ARMV7;
                case STANDARD: return INSTALLED_STANDARD_ARMV7;
                case FULL: return INSTALLED_FULL_ARMV7;
            }
        } else {
            switch (tier) {
                case BASIC: return INSTALLED_BASIC_ARM64;
                case STANDARD: return INSTALLED_STANDARD_ARM64;
                case FULL: return INSTALLED_FULL_ARM64;
            }
        }
        return INSTALLED_BASIC_ARM64;
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
