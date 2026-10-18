/*
 * ============================================================================
 * Name        : CatalogFreshness.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Pure rules for the catalog refresh:
 *               when to check for an update, and whether the published catalog
 *               differs from the one in use. The app stores, per catalog, the
 *               active catalog's hash and the last successful check time; these
 *               rules decide when to hit the network and when to swap. No
 *               Android, no I/O => unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.domain;

public final class CatalogFreshness {

    /** How long before the app bothers checking the manifest again. */
    public static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** K2GO-476: after a failed pull (download or hash), wait this long before pulling again. */
    public static final long FAILURE_BACKOFF_MS = 24L * 60 * 60 * 1000;

    private CatalogFreshness() {
    }

    /**
     * True when the catalog is due for an update check: never checked
     * ({@code lastCheckMs <= 0}) or more than {@code ttlMs} has elapsed. A clock that ran
     * backwards ({@code now < lastCheck}) is treated as due, so a wrong device clock never wedges
     * the check off.
     */
    public static boolean dueForCheck(long lastCheckMs, long nowMs, long ttlMs) {
        if (lastCheckMs <= 0L) return true;
        if (nowMs < lastCheckMs) return true;
        return nowMs - lastCheckMs >= ttlMs;
    }

    /**
     * True when the published catalog differs from the one the app is using, i.e. it should be
     * pulled and swapped. The hash is the source of truth (the version string is only a label):
     * an absent remote hash means "cannot tell" — do not swap; a local hash that differs from a
     * present remote — swap; no local hash yet (never pulled) with a real remote — swap.
     */
    public static boolean changed(String localHash, String remoteHash) {
        if (remoteHash == null || remoteHash.isEmpty()) return false;
        if (localHash == null || localHash.isEmpty()) return true;
        return !localHash.equals(remoteHash);
    }

    /**
     * K2GO-476: true when a pulled overlay should be read instead of the APK asset. An app update
     * can ship an asset newer than an overlay pulled weeks before; the overlay must not win then.
     * Dates are the files' {@code generated} headers (ISO, so they compare as text). An asset
     * with no date predates them all; an overlay with no date loses to a dated asset.
     */
    /**
     * K2GO-476: a catalog's {@code generated} header ("2026-10-17" or "2026-10-17T23:48:31Z",
     * UTC) as epoch millis, or 0 when absent or unreadable.
     */
    public static long generatedMs(String generated) {
        if (generated == null || generated.isEmpty()) return 0L;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            try {
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                java.util.Date d = f.parse(generated, pos);
                if (d != null && pos.getIndex() == generated.length()) return d.getTime();
            } catch (RuntimeException ignore) {
                // try the next shape
            }
        }
        return 0L;
    }

    /** Whether a {@code generated} header carries a time of day, not only a date. */
    public static boolean generatedHasTime(String generated) {
        return generated != null && generated.length() > 10 && generatedMs(generated) > 0L;
    }

    public static boolean overlayIsCurrent(String overlayGenerated, String assetGenerated) {
        if (assetGenerated == null || assetGenerated.isEmpty()) return true;
        if (overlayGenerated == null || overlayGenerated.isEmpty()) return false;
        return overlayGenerated.compareTo(assetGenerated) >= 0;
    }
}
